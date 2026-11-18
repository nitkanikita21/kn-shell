//! Image decoding: format detection by magic bytes, EXIF orientation (JPEG),
//! and downsampling to `max_input_dim` on the longest side.

use crate::PaletteError;
use image::{DynamicImage, GenericImageView, ImageFormat, RgbImage};
use std::io::Cursor;

/// Detect the image format from the first bytes.
pub fn detect_format(bytes: &[u8]) -> Result<ImageFormat, PaletteError> {
    if bytes.len() >= 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF {
        Ok(ImageFormat::Jpeg)
    } else if bytes.len() >= 8
        && bytes[0] == 0x89
        && bytes[1] == 0x50
        && bytes[2] == 0x4E
        && bytes[3] == 0x47
        && bytes[4] == 0x0D
        && bytes[5] == 0x0A
        && bytes[6] == 0x1A
        && bytes[7] == 0x0A
    {
        Ok(ImageFormat::Png)
    } else if bytes.len() >= 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        Ok(ImageFormat::WebP)
    } else {
        Err(PaletteError::UnsupportedFormat(format!(
            "unknown magic bytes: {:02x?}",
            &bytes[..bytes.len().min(8)]
        )))
    }
}

/// Decode `bytes` into an sRGB `RgbImage`, applying EXIF orientation for JPEG,
/// then downsample so the longest side is at most `max_input_dim`.
pub fn decode(bytes: &[u8], max_input_dim: u32) -> Result<RgbImage, PaletteError> {
    let format = detect_format(bytes)?;

    let img = image::load_from_memory_with_format(bytes, format)
        .map_err(|e| PaletteError::Decode(format!("failed to decode {format:?}: {e}")))?;

    // EXIF orientation is meaningful only for JPEG.
    let oriented = if format == ImageFormat::Jpeg {
        match read_orientation(bytes) {
            Some(o) if o != 1 => apply_orientation(img, o),
            _ => img,
        }
    } else {
        img
    };

    let (w, h) = oriented.dimensions();
    if w == 0 || h == 0 {
        return Err(PaletteError::EmptyImage);
    }

    let resized = if w.max(h) > max_input_dim {
        let scale = max_input_dim as f64 / w.max(h) as f64;
        let nw = (w as f64 * scale).round().max(1.0) as u32;
        let nh = (h as f64 * scale).round().max(1.0) as u32;
        // Box filter: each destination pixel is the area-average of the source
        // pixels it covers. This is the downsample the palette engine wants
        // (cheap, no sharpening artifacts) rather than a triangular resample.
        box_downsample(&oriented.into_rgb8(), nw, nh)
    } else {
        oriented
    };

    Ok(resized.into_rgb8())
}

/// Area-average (box) downsample: every output pixel covers a contiguous
/// `(w/nw) x (h/nh)` block of source pixels and takes their mean.
fn box_downsample(src: &RgbImage, nw: u32, nh: u32) -> DynamicImage {
    let (w, h) = src.dimensions();
    if w == 0 || h == 0 || nw == 0 || nh == 0 {
        return DynamicImage::ImageRgb8(src.clone());
    }
    let mut out = RgbImage::new(nw, nh);
    let sx = w as f64 / nw as f64;
    let sy = h as f64 / nh as f64;
    for oy in 0..nh {
        let y0 = (oy as f64 * sy) as u32;
        let y1 = (((oy + 1) as f64 * sy).ceil() as u32).min(h).max(y0 + 1);
        for ox in 0..nw {
            let x0 = (ox as f64 * sx) as u32;
            let x1 = (((ox + 1) as f64 * sx).ceil() as u32).min(w).max(x0 + 1);
            let mut r = 0u64;
            let mut g = 0u64;
            let mut b = 0u64;
            let mut count = 0u64;
            for yy in y0..y1 {
                for xx in x0..x1 {
                    let p = src.get_pixel(xx, yy);
                    r += p[0] as u64;
                    g += p[1] as u64;
                    b += p[2] as u64;
                    count += 1;
                }
            }
            let inv = 1.0 / count as f64;
            out.put_pixel(
                ox,
                oy,
                image::Rgb([
                    (r as f64 * inv).round() as u8,
                    (g as f64 * inv).round() as u8,
                    (b as f64 * inv).round() as u8,
                ]),
            );
        }
    }
    DynamicImage::ImageRgb8(out)
}

/// Read the EXIF `Orientation` tag (1-8) from JPEG bytes, if present.
fn read_orientation(bytes: &[u8]) -> Option<u8> {
    let reader = exif::Reader::new();
    let exif = reader.read_from_container(&mut Cursor::new(bytes)).ok()?;
    exif.get_field(exif::Tag::Orientation, exif::In::PRIMARY)
        .and_then(|f| f.value.get_uint(0))
        .map(|v| v as u8)
}

/// Apply the standard EXIF orientation transform to an image.
fn apply_orientation(img: DynamicImage, orientation: u8) -> DynamicImage {
    use image::imageops::{
        flip_horizontal_in_place, flip_vertical_in_place, rotate180, rotate270, rotate90,
    };
    let mut rgb: RgbImage = img.into_rgb8();
    match orientation {
        2 => {
            flip_horizontal_in_place(&mut rgb);
            DynamicImage::ImageRgb8(rgb)
        }
        3 => DynamicImage::ImageRgb8(rotate180(&rgb)),
        4 => {
            flip_vertical_in_place(&mut rgb);
            DynamicImage::ImageRgb8(rgb)
        }
        5 => {
            // transpose: flip horizontal then rotate 90
            flip_horizontal_in_place(&mut rgb);
            DynamicImage::ImageRgb8(rotate90(&rgb))
        }
        6 => DynamicImage::ImageRgb8(rotate90(&rgb)),
        7 => {
            // transverse: flip horizontal then rotate 270
            flip_horizontal_in_place(&mut rgb);
            DynamicImage::ImageRgb8(rotate270(&rgb))
        }
        8 => DynamicImage::ImageRgb8(rotate270(&rgb)),
        _ => DynamicImage::ImageRgb8(rgb),
    }
}

/// Convert a decoded image into a flat list of `(r,g,b)` pixels.
pub fn to_pixels(img: &RgbImage) -> Vec<(u8, u8, u8)> {
    let mut out = Vec::with_capacity(img.width() as usize * img.height() as usize);
    for p in img.pixels() {
        out.push((p[0], p[1], p[2]));
    }
    out
}

/// Build a tiny solid-color PNG in memory (no fixture files needed).
/// Test-only helper, reachable from the rest of the crate's test code.
#[cfg(test)]
pub(crate) fn make_png(w: u32, h: u32, color: [u8; 3]) -> Vec<u8> {
    let mut buf = Vec::new();
    let mut img = RgbImage::new(w, h);
    for p in img.pixels_mut() {
        *p = image::Rgb(color);
    }
    DynamicImage::ImageRgb8(img)
        .write_to(&mut Cursor::new(&mut buf), ImageFormat::Png)
        .unwrap();
    buf
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_formats() {
        assert_eq!(
            detect_format(&[0xFF, 0xD8, 0xFF, 0xE0]).unwrap(),
            ImageFormat::Jpeg
        );
        assert_eq!(
            detect_format(&[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]).unwrap(),
            ImageFormat::Png
        );
        assert_eq!(detect_format(b"RIFFxxxxWEBP").unwrap(), ImageFormat::WebP);
        assert!(detect_format(b"not an image").is_err());
    }

    #[test]
    fn decode_png_ok() {
        let img = make_png(16, 16, [200u8, 30, 30]);
        let rgb = decode(&img, 64).unwrap();
        assert_eq!(rgb.dimensions(), (16, 16));
    }
}
