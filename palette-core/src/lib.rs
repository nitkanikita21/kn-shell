#![deny(unsafe_code)]
//! palette-core: extract a small, weighted palette of dominant OKLCH colors from
//! a wallpaper image.
//!
//! This crate deliberately knows nothing about themes, tokens, UI or reactivity.
//! It decodes an image, downsamples it, clusters its pixels in OKLab with k-means,
//! converts the cluster centers to OKLCH, and caches the result by the SHA-256 of
//! the source bytes. A raw C ABI ([`ffi`]) exposes the engine to Kotlin/Native.

mod cache;
mod color;
mod decode;
mod error;
pub mod ffi;
mod kmeans;

#[cfg(feature = "custom-host")]
pub mod custom_ext;

pub use color::{oklab_to_oklch, oklab_to_srgb_u8, oklch_to_oklab, srgb_u8_to_oklab};
pub use error::PaletteError;

use serde::{Deserialize, Serialize};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};

/// A single color in the palette, expressed in OKLCH.
///
/// `h` is `NaN` for achromatic colors (where hue is undefined). When serialized
/// to JSON, a `NaN` hue becomes `null`.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct OklchColor {
    /// OKLab lightness, 0..=1.
    pub l: f32,
    /// OKLCH chroma, 0..=~0.4.
    pub c: f32,
    /// OKLCH hue in degrees, 0..=360, or `NaN` if achromatic.
    pub h: f32,
    /// Relative area weight, 0..=1 (all colors in a palette sum to ~1).
    pub weight: f32,
}

/// An ordered list of dominant colors (sorted by `weight` descending).
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, Default)]
pub struct Palette {
    pub colors: Vec<OklchColor>,
}

/// Options controlling extraction.
#[derive(Clone, Debug, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct PaletteOptions {
    /// Number of clusters. Defaults to 6.
    pub k: u8,
    /// Longest side of the downsampled image, in pixels. Defaults to 64.
    pub max_input_dim: u32,
    /// Drop clusters whose weight falls below this. Defaults to 0.03.
    pub min_weight: f32,
    /// Drop pixels whose OKLCH chroma is below this (0 = keep all). Defaults to 0.
    pub chroma_floor: f32,
    /// Deterministic seed. `None` = non-deterministic.
    pub seed: Option<u64>,
}

impl PaletteOptions {
    fn effective(&self) -> PaletteOptions {
        let mut o = self.clone();
        if o.k == 0 {
            o.k = 6;
        }
        if o.max_input_dim == 0 {
            o.max_input_dim = 64;
        }
        o
    }
}

/// Extract a palette from a file on disk.
pub fn extract_from_path(path: &Path, options: PaletteOptions) -> Result<Palette, PaletteError> {
    let bytes = std::fs::read(path)?;
    extract_from_bytes(&bytes, options)
}

/// Extract a palette from raw image bytes (PNG / JPEG / WebP).
pub fn extract_from_bytes(bytes: &[u8], options: PaletteOptions) -> Result<Palette, PaletteError> {
    extract_internal(bytes, &options, None)
}

/// Same as [`extract_from_bytes`] but observes a cancellation flag. Used by the
/// async FFI worker; never exposed in the public (non-cancellable) API.
pub(crate) fn extract_with_cancel(
    bytes: &[u8],
    options: PaletteOptions,
    cancel: &AtomicBool,
) -> Result<Palette, PaletteError> {
    extract_internal(bytes, &options, Some(cancel))
}

fn extract_internal(
    bytes: &[u8],
    options: &PaletteOptions,
    cancel: Option<&AtomicBool>,
) -> Result<Palette, PaletteError> {
    let opts = options.effective();
    let hash = cache::hash_bytes(bytes);

    // Fast path: cache hit.
    if let Some(p) = cache::read_cache(&hash) {
        return Ok(p);
    }

    let img = decode::decode(bytes, opts.max_input_dim)?;
    if cancelled(cancel) {
        return Err(error::cancelled());
    }

    let pixels = decode::to_pixels(&img);

    // Convert to OKLab and filter insignificant pixels:
    //   - lightness outside [0.10, 0.95] (near-black / near-white carry little hue)
    //   - chroma below the optional floor
    let mut lab: Vec<(f64, f64, f64)> = Vec::with_capacity(pixels.len());
    for (r, g, b) in &pixels {
        let (l, a, bb) = color::srgb_u8_to_oklab(*r, *g, *b);
        let (_l, c, _h) = color::oklab_to_oklch(l, a, bb);
        if (0.10..=0.95).contains(&l) && c >= opts.chroma_floor as f64 {
            lab.push((l, a, bb));
        }
    }

    if lab.len() < 64 {
        let fb = fallback_palette();
        cache::write_cache(&hash, &fb)?;
        return Ok(fb);
    }

    let k = (opts.k as usize).max(1).min(lab.len());
    let clusters = kmeans::kmeans_oklab(&lab, k, opts.seed, cancel)?;
    if cancelled(cancel) {
        return Err(error::cancelled());
    }

    let merged = kmeans::merge_clusters(clusters, 1e-3);

    let mut colors: Vec<OklchColor> = merged
        .into_iter()
        .filter_map(|cl| {
            if cl.weight < opts.min_weight as f64 {
                return None;
            }
            let (l, c, h) = color::oklab_to_oklch(cl.center.0, cl.center.1, cl.center.2);
            Some(OklchColor {
                l: l as f32,
                c: c as f32,
                h: h as f32,
                weight: cl.weight as f32,
            })
        })
        .collect();

    if colors.is_empty() {
        let fb = fallback_palette();
        cache::write_cache(&hash, &fb)?;
        return Ok(fb);
    }

    // Achromatic images (e.g. a gray gradient) have no meaningful hue: collapse
    // to a single neutral swatch.
    if colors.iter().all(|c| c.c < 1e-4) {
        let fb = fallback_palette();
        cache::write_cache(&hash, &fb)?;
        return Ok(fb);
    }

    colors.sort_by(|a, b| {
        b.weight
            .partial_cmp(&a.weight)
            .unwrap_or(std::cmp::Ordering::Equal)
    });

    let palette = Palette { colors };
    cache::write_cache(&hash, &palette)?;
    Ok(palette)
}

fn cancelled(cancel: Option<&AtomicBool>) -> bool {
    cancel.map(|c| c.load(Ordering::Relaxed)).unwrap_or(false)
}

/// The neutral fallback used when an image is too small / too flat / achromatic.
fn fallback_palette() -> Palette {
    Palette {
        colors: vec![OklchColor {
            l: 0.55,
            c: 0.0,
            h: f32::NAN,
            weight: 1.0,
        }],
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::decode::make_png;
    use std::io::Write;

    fn tmp_path(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join("palette_core_tests");
        let _ = std::fs::create_dir_all(&dir);
        dir.join(name)
    }

    #[test]
    fn solid_red_is_single_cluster() {
        let png = make_png(32, 32, [255, 0, 0]);
        let pal = extract_from_bytes(&png, PaletteOptions::default()).unwrap();
        assert_eq!(pal.colors.len(), 1, "palette: {:?}", pal.colors);
        let c = &pal.colors[0];
        assert!(c.weight > 0.99, "weight {}", c.weight);
        assert!(c.c > 0.2, "chroma {}", c.c);
        assert!((c.h - 29.23).abs() < 1.0, "hue {}", c.h);
    }

    #[test]
    fn grayscale_falls_back_to_single_neutral() {
        let png = make_png(48, 48, [128, 128, 128]);
        let pal = extract_from_bytes(&png, PaletteOptions::default()).unwrap();
        assert_eq!(pal.colors.len(), 1);
        assert!(pal.colors[0].c.abs() < 1e-4);
        assert!(pal.colors[0].h.is_nan());
    }

    #[test]
    fn two_tone_yields_two_clusters() {
        // Top half blue, bottom half yellow.
        let w = 32u32;
        let h = 32u32;
        let mut img = image::RgbImage::new(w, h);
        for y in 0..h {
            for x in 0..w {
                let c = if y < h / 2 {
                    [0, 80, 255]
                } else {
                    [255, 220, 0]
                };
                img.put_pixel(x, y, image::Rgb(c));
            }
        }
        let mut buf = Vec::new();
        image::DynamicImage::ImageRgb8(img)
            .write_to(&mut std::io::Cursor::new(&mut buf), image::ImageFormat::Png)
            .unwrap();
        let pal = extract_from_bytes(&buf, PaletteOptions::default()).unwrap();
        assert_eq!(pal.colors.len(), 2, "palette: {:?}", pal.colors);
        for c in &pal.colors {
            assert!((c.weight - 0.5).abs() < 0.05, "weight {}", c.weight);
        }
    }

    #[test]
    fn options_are_respected() {
        let png = make_png(32, 32, [255, 0, 0]);
        let opts = PaletteOptions {
            k: 3,
            min_weight: 0.5,
            ..Default::default()
        };
        let pal = extract_from_bytes(&png, opts).unwrap();
        // Solid color -> still one merged cluster (>= min_weight).
        assert_eq!(pal.colors.len(), 1);
    }

    #[test]
    fn cache_is_used_across_calls() {
        let path = tmp_path("cache_test.png");
        let mut f = std::fs::File::create(&path).unwrap();
        f.write_all(&make_png(64, 64, [10, 200, 120])).unwrap();
        drop(f);
        let opts = PaletteOptions::default();
        let a = extract_from_path(&path, opts.clone()).unwrap();
        let b = extract_from_path(&path, opts.clone()).unwrap();
        assert_eq!(a.colors.len(), b.colors.len());
        // Same hash -> identical weights.
        for (x, y) in a.colors.iter().zip(b.colors.iter()) {
            assert!((x.weight - y.weight).abs() < 1e-6);
        }
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn large_image_does_not_panic() {
        let png = make_png(256, 256, [200, 30, 90]);
        let _ = extract_from_bytes(&png, PaletteOptions::default()).unwrap();
    }

    #[test]
    fn unsupported_format_errors() {
        let res = extract_from_bytes(b"not an image at all", PaletteOptions::default());
        assert!(matches!(res, Err(PaletteError::UnsupportedFormat(_))));
    }
}
