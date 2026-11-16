//! sRGB <-> OKLab <-> OKLCH conversions (Björn Ottosson's formulas).
//!
//! Working precision is `f64` so that the srgb -> oklab -> srgb round trip stays
//! well under the 1e-4 tolerance required by the tests. The public [`crate::OklchColor`]
//! stores `f32`, which is the on-wire format.

const EPSILON: f64 = 1e-4;

#[inline]
fn srgb_channel_to_linear(c: f64) -> f64 {
    if c <= 0.04045 {
        c / 12.92
    } else {
        ((c + 0.055) / 1.055).powf(2.4)
    }
}

#[inline]
fn linear_to_srgb_channel(c: f64) -> f64 {
    let c = c.clamp(0.0, 1.0);
    if c <= 0.0031308 {
        c * 12.92
    } else {
        1.055 * c.powf(1.0 / 2.4) - 0.055
    }
}

/// Convert an sRGB byte triple to OKLab `(L, a, b)` in `f64`.
pub fn srgb_u8_to_oklab(r: u8, g: u8, b: u8) -> (f64, f64, f64) {
    let r = srgb_channel_to_linear(r as f64 / 255.0);
    let g = srgb_channel_to_linear(g as f64 / 255.0);
    let b = srgb_channel_to_linear(b as f64 / 255.0);

    let l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b;
    let m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b;
    let s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b;

    let l_ = l.cbrt();
    let m_ = m.cbrt();
    let s_ = s.cbrt();

    let l_ok = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_;
    let a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_;
    let b = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_;
    (l_ok, a, b)
}

/// Convert OKLab `(L, a, b)` back to sRGB bytes (round-trip helper / test util).
pub fn oklab_to_srgb_u8(l: f64, a: f64, b: f64) -> (u8, u8, u8) {
    let l_ = l + 0.3963377774 * a + 0.2158037573 * b;
    let m_ = l - 0.1055613458 * a - 0.0638541728 * b;
    let s_ = l - 0.0894841775 * a - 1.2914855480 * b;

    let l = l_ * l_ * l_;
    let m = m_ * m_ * m_;
    let s = s_ * s_ * s_;

    let r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
    let g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
    let b = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;

    let r = linear_to_srgb_channel(r);
    let g = linear_to_srgb_channel(g);
    let b = linear_to_srgb_channel(b);

    (
        (r * 255.0).round().clamp(0.0, 255.0) as u8,
        (g * 255.0).round().clamp(0.0, 255.0) as u8,
        (b * 255.0).round().clamp(0.0, 255.0) as u8,
    )
}

/// Convert OKLab `(L, a, b)` to OKLCH `(L, C, H_deg)`.
///
/// When the chroma is below [`EPSILON`] the hue is set to `f64::NAN` because the
/// hue is undefined for achromatic colors.
pub fn oklab_to_oklch(l: f64, a: f64, b: f64) -> (f64, f64, f64) {
    let c = (a * a + b * b).sqrt();
    if c < EPSILON {
        (l, 0.0, f64::NAN)
    } else {
        let mut h = b.atan2(a).to_degrees();
        if h < 0.0 {
            h += 360.0;
        }
        (l, c, h)
    }
}

/// Convert OKLCH `(L, C, H_deg)` to OKLab `(L, a, b)`.
///
/// A `NaN` hue is treated as achromatic (a = b = 0).
pub fn oklch_to_oklab(l: f64, c: f64, h: f64) -> (f64, f64, f64) {
    if h.is_nan() {
        (l, 0.0, 0.0)
    } else {
        let hr = h.to_radians();
        (l, c * hr.cos(), c * hr.sin())
    }
}

/// Distance between two OKLab points (Euclidean). Used to dedupe near-identical
/// k-means clusters.
pub fn oklab_distance(a: (f64, f64, f64), b: (f64, f64, f64)) -> f64 {
    let dl = a.0 - b.0;
    let da = a.1 - b.1;
    let db = a.2 - b.2;
    (dl * dl + da * da + db * db).sqrt()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn srgb_oklab_srgb_roundtrip() {
        for _ in 0..1000 {
            let r = (rand_u8() as f64 / 255.0 * 255.0) as u8;
            let g = (rand_u8() as f64 / 255.0 * 255.0) as u8;
            let b = (rand_u8() as f64 / 255.0 * 255.0) as u8;
            let (l, a, bb) = srgb_u8_to_oklab(r, g, b);
            let (r2, g2, b2) = oklab_to_srgb_u8(l, a, bb);
            let dr = (r as i32 - r2 as i32).abs();
            let dg = (g as i32 - g2 as i32).abs();
            let db = (b as i32 - b2 as i32).abs();
            assert!(
                dr <= 1 && dg <= 1 && db <= 1,
                "roundtrip off by ({dr},{dg},{db}) for ({r},{g},{b})"
            );
        }
    }

    // Tiny deterministic LCG so the round-trip test needs no `rand` dependency.
    fn rand_u8() -> u8 {
        use std::sync::atomic::{AtomicU64, Ordering};
        static S: AtomicU64 = AtomicU64::new(0x9E3779B97F4A7C15);
        let v = S.fetch_add(0x2545F4914F6CDD1D, Ordering::Relaxed);
        let n = v
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        (n >> 32) as u8
    }

    #[test]
    fn oklch_hue_undefined_for_achromatic() {
        let (_, c, h) = oklab_to_oklch(0.5, 1e-6, 1e-6);
        assert!(c.abs() < 1e-4);
        assert!(h.is_nan());
    }

    #[test]
    fn oklch_red_hue_around_29_deg() {
        // sRGB red (1,0,0) should land near hue 29 deg with meaningful chroma.
        let (l, a, b) = srgb_u8_to_oklab(255, 0, 0);
        let (_l, c, h) = oklab_to_oklch(l, a, b);
        assert!(c > 0.2, "chroma {c}");
        assert!((h - 29.23).abs() < 1.0, "hue {h}");
    }
}
