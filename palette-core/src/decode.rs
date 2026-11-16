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
        DynamicImage::ImageRgb8(image::imageops::resize(
            &oriented.into_rgb8(),
            nw,
            nh,
            image::imageops::FilterType::Triangle,
        ))
    } else {
        oriented
    };

    Ok(resized.into_rgb8())
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
