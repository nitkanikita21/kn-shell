# palette-core

Standalone Rust engine that turns an image file into a small, perceptually
uniform palette of dominant colors.

It is **self-contained**: by default it has zero dependency on the rest of the
shell (`rust-core`). The only thing it needs is the image bytes and a writable
cache directory.

```
image file ──▶ decode (JPEG/PNG/WebP) ──▶ downsample ≤64px
            ──▶ sRGB → OKLab/OKLCH ──▶ k-means++ (rayon)
            ──▶ merge near-equal clusters ──▶ JSON palette (cached by sha256)
```

## What it does

- **Decodes** JPEG, PNG, and WebP. JPEG EXIF `Orientation` is honored. The pure-Rust
  `image` decoder is used, so no system `libwebp`/`libjpeg` is required.
- **Downsamples** so the longest side is at most `max_input_dim` (default 64 px)
  before clustering — enough for color statistics, cheap to compute.
- **Clusters in OKLab** (per Björn Ottosson) using k-means++ seeding and a rayon
  parallel assignment step. The hue/chroma are reported as OKLCH so the UI gets
  perceptually even spacing and an explicit achromatic flag.
- **Caches** the result keyed by the **sha256** of the file contents under
  `$XDG_CACHE_HOME/palette-core/`. A cache hit skips decode + clustering entirely.
  The cache survives process restarts (and shell restarts): same file, same hash,
  instant result.
- **Cancels** cleanly. The extraction loop checks a cancellation flag between
  steps, so a decode or k-means run can be aborted mid-flight (used by the async
  API) and frees its work instead of leaking.

## Rust API

```rust
use palette_core::{extract_from_path, extract_from_bytes, PaletteOptions};

// From a path:
let palette = extract_from_path("wallpaper.png", PaletteOptions::default())?;

// From memory:
let bytes = std::fs::read("wallpaper.png")?;
let palette = extract_from_bytes(&bytes, PaletteOptions::default())?;

for c in &palette.colors {
    // c.l, c.c are f64 in OKLCH; c.h is f64 but NaN when the color is achromatic.
    // c.weight is the cluster's share of the image (0..=1), in descending order.
}
```

`PaletteOptions`:

| field          | type  | default | meaning                                          |
| -------------- | ----- | ------- | ------------------------------------------------ |
| `k`            | `u8`  | `6`     | number of clusters (palette size)                |
| `max_input_dim`| `u32` | `64`    | downsample so the longest side ≤ this           |
| `min_weight`   | `f32` | `0.03`  | drop clusters below this weight                  |
| `chroma_floor` | `f32` | `0.0`   | ignore pixels with OKLCH chroma below this       |
| `seed`         | `Option<u64>` | `None` | fixed RNG seed for reproducible results       |

Cancellation (used internally by the async C ABI) is available via
`extract_with_cancel(bytes, opts, &flag)` where `flag: Option<&AtomicBool>`.

## C ABI (`palette_core.h`)

The crate's library target (`crate-type = ["lib", "staticlib"]`) exposes a flat C
ABI for Kotlin/Native (or any C caller). The `staticlib` is linked directly into
the Kotlin/Native executable (mirroring how `rust-core` is consumed), so there is
no runtime `.so` to deploy. See [`palette_core.h`](palette_core.h) for the full
contract, including the exact string-ownership rules.

```c
int32_t palette_extract_sync(const char *path, const char *opts_json,
                             char **out_json, char **err_out);

uint64_t palette_extract_async(const char *path, const char *opts_json,
                               void (*callback)(const char *result,
                                                const char *err,
                                                void *user_data),
                               void *user_data);

void palette_cancel(uint64_t handle);
void palette_free_string(char *s);
```

* **Synchronous**: `out_json` / `err_out` receive freshly allocated strings you
  free with `palette_free_string` on the **same thread**.
* **Asynchronous**: the callback runs on a worker thread and receives two freshly
  allocated strings; it **must** free both with `palette_free_string` *inside*
  the callback (same thread). The pointers are valid only for the callback's
  duration — copy anything you need first. Cross-thread frees are unsafe on the
  target glibc (a tcache-chunk free aborts the process), which is exactly why
  the contract requires same-thread freeing.

The library is non-blocking on the calling thread for the async path: decoding
and clustering happen on a dedicated worker, so a slow/large image never stalls
the UI or compositor.

## Cache

* Location: `ProjectDirs`-resolved `XDG_CACHE_HOME/palette-core/` (Linux), or
  `$HOME/Library/Caches/...` (macOS) / `%LOCALAPPDATA%/...` (Windows).
* Override for testing or sandboxes via the `PALETTE_CORE_CACHE_DIR` environment
  variable (honored before the platform default).
* Key: `sha256(file bytes)`; value is a versioned, bounds-checked binary blob.
  Writes go through a unique `.tmp{pid}.{seq}` file and are atomically renamed,
  so concurrent extractions of the same file never corrupt the cache.

## Build & features

```sh
cargo test                 # 18 unit tests + 5 FFI integration tests
cargo clippy --all-targets -- -D warnings
cargo fmt --check
cargo bench                # criterion micro-benchmarks
```

* `default`: standalone engine, no `rust-core` dependency.
* `custom-host`: enables `src/custom_ext.rs`, a bridge that registers palette-core
  as a `CustomExtension` in the `rust-core` `custom` extension host (publishing
  each color as a `CustomEntry`). Off by default so the core engine stays
  dependency-free and fast to build.

### In the KN-shell repo (Gradle / Gobley)

Inside this repository `palette-core` is also a first-class Gradle module
(`:palette-core`), built exactly like `:rust-core` — Gobley's `cargo` plugin drives
`cargo build`, with the repository-local rustup toolchain and the same cargoBuild
input-wiring so edits are never silently stale. `:shell-app` binds the hand-written
`palette_core.h` via a Kotlin/Native `cinterop` (package `dev.knshell.palette`) and
links the produced `libpalette_core.a` into the executable; the link task depends on
`:palette-core`'s `cargoBuild`. This keeps both Rust crates structurally consistent:
each is a Gradle module whose native archive is linked into the shell.

## Safety

`#![deny(unsafe_code)]` is enforced everywhere **except** `src/ffi.rs`, which
contains the raw C ABI. The C ABI functions are written to be safe to call (they
null-check their pointers and never expose dangling state); the only genuinely
`unsafe` operations are the FFI pointer reconstructions, all confined to that
module.

## License

MIT
