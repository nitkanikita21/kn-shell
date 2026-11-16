//! File-hash based cache for extracted palettes.
//!
//! Cache key: `sha256(file_bytes)` (the raw file bytes, so re-encoding the same
//! wallpaper with a different encoder still hits the cache). Store location:
//! `$XDG_CACHE_HOME/palette-core` (or `~/.cache/palette-core` fallback).
//!
//! Binary format (little-endian):
//!   u8  version (= 1)
//!   u8  count
//!   count * [ f32 l, f32 c, f32 h, f32 weight ]

use crate::{OklchColor, Palette, PaletteError};
use sha2::{Digest, Sha256};
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

pub const CACHE_VERSION: u8 = 1;

/// Resolve the cache directory, creating it if needed.
///
/// Resolution order:
///   1. `$PALETTE_CORE_CACHE_DIR` (explicit override, useful for tests/diagnostics).
///   2. `$XDG_CACHE_HOME/palette-core` (or `~/.cache/palette-core` fallback).
///   3. Under `cfg(test)` the cache lives under the crate's `target/test-cache`
///      so the suite never depends on a writable `$HOME`.
pub fn cache_dir() -> PathBuf {
    let base = if let Some(p) = std::env::var_os("PALETTE_CORE_CACHE_DIR") {
        PathBuf::from(p)
    } else {
        default_cache_base()
    };
    if let Err(e) = std::fs::create_dir_all(&base) {
        // Best-effort: extraction still works, the cache just won't persist.
        eprintln!("palette-core: could not create cache dir {base:?}: {e}");
    }
    base
}

#[cfg(test)]
fn default_cache_base() -> PathBuf {
    PathBuf::from(concat!(env!("CARGO_MANIFEST_DIR"), "/target/test-cache"))
}

#[cfg(not(test))]
fn default_cache_base() -> PathBuf {
    directories::ProjectDirs::from("", "", "palette-core")
        .map(|d| d.cache_dir().to_path_buf())
        .unwrap_or_else(|| {
            std::env::var_os("HOME")
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("."))
                .join(".cache")
                .join("palette-core")
        })
}

fn cache_path(hash: &str) -> PathBuf {
    cache_dir().join(format!("{hash}.pal"))
}

/// Hash a byte slice with SHA-256, returning a hex string.
pub fn hash_bytes(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    hex(&hasher.finalize())
}

fn hex(d: &[u8]) -> String {
    let mut s = String::with_capacity(d.len() * 2);
    for b in d {
        s.push(char::from_digit((b >> 4) as u32, 16).unwrap());
        s.push(char::from_digit((b & 0xf) as u32, 16).unwrap());
    }
    s
}

/// Read a cached palette, if present and well-formed.
pub fn read_cache(hash: &str) -> Option<Palette> {
    let path = cache_path(hash);
    let bytes = std::fs::read(&path).ok()?;
    decode_cache(&bytes).ok()
}

/// Write a palette to the cache atomically (.tmp + rename).
///
/// The intermediate file has a per-call unique name so concurrent writes for the
/// same hash (e.g. two callers extracting the same wallpaper) cannot clobber each
/// other's temp file before the final atomic rename.
pub fn write_cache(hash: &str, palette: &Palette) -> Result<(), PaletteError> {
    let path = cache_path(hash);
    let data = encode_cache(palette);
    let tmp = path.with_extension(format!(
        "tmp{}.{}",
        std::process::id(),
        TMP_SEQ.fetch_add(1, Ordering::Relaxed)
    ));
    {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(&data)?;
        f.sync_all()?;
    }
    std::fs::rename(&tmp, &path)?;
    Ok(())
}

static TMP_SEQ: AtomicU64 = AtomicU64::new(0);

fn encode_cache(palette: &Palette) -> Vec<u8> {
    let mut out = Vec::with_capacity(2 + palette.colors.len() * 16);
    out.push(CACHE_VERSION);
    out.push(palette.colors.len().min(255) as u8);
    for c in &palette.colors {
        out.extend_from_slice(&c.l.to_le_bytes());
        out.extend_from_slice(&c.c.to_le_bytes());
        // NaN hue is preserved exactly through f32 bits.
        out.extend_from_slice(&c.h.to_le_bytes());
        out.extend_from_slice(&c.weight.to_le_bytes());
    }
    out
}

fn decode_cache(bytes: &[u8]) -> Result<Palette, PaletteError> {
    if bytes.len() < 2 {
        return Err(PaletteError::Internal("cache too short".into()));
    }
    if bytes[0] != CACHE_VERSION {
        return Err(PaletteError::Internal("cache version mismatch".into()));
    }
    let count = bytes[1] as usize;
    if bytes.len() < 2 + count * 16 {
        return Err(PaletteError::Internal("cache truncated".into()));
    }
    let mut colors = Vec::with_capacity(count);
    let mut off = 2;
    for _ in 0..count {
        let l = f32::from_le_bytes(bytes[off..off + 4].try_into().unwrap());
        let c = f32::from_le_bytes(bytes[off + 4..off + 8].try_into().unwrap());
        let h = f32::from_le_bytes(bytes[off + 8..off + 12].try_into().unwrap());
        let weight = f32::from_le_bytes(bytes[off + 12..off + 16].try_into().unwrap());
        colors.push(OklchColor { l, c, h, weight });
        off += 16;
    }
    Ok(Palette { colors })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cache_roundtrip() {
        let pal = Palette {
            colors: vec![
                OklchColor {
                    l: 0.5,
                    c: 0.2,
                    h: 29.0,
                    weight: 0.6,
                },
                OklchColor {
                    l: 0.7,
                    c: 0.0,
                    h: f32::NAN,
                    weight: 0.4,
                },
            ],
        };
        let hash = "test-roundtrip-hash";
        write_cache(hash, &pal).unwrap();
        let read = read_cache(hash).expect("cache read");
        assert_eq!(read.colors.len(), 2);
        assert!((read.colors[0].l - 0.5).abs() < 1e-6);
        assert!((read.colors[0].h - 29.0).abs() < 1e-6);
        assert!(read.colors[1].h.is_nan());
        let _ = std::fs::remove_file(cache_path(hash));
    }

    #[test]
    fn hash_is_stable() {
        let a = hash_bytes(b"hello palette");
        let b = hash_bytes(b"hello palette");
        assert_eq!(a, b);
        assert_ne!(a, hash_bytes(b"different"));
        assert_eq!(a.len(), 64);
    }
}
