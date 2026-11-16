use criterion::{black_box, criterion_group, criterion_main, Criterion};
use palette_core::{extract_from_bytes, PaletteOptions};
use std::io::Cursor;

/// Generate an in-memory PNG of the given size filled with a gradient, so the
/// benchmark needs no fixture files.
fn make_png(w: u32, h: u32) -> Vec<u8> {
    let mut img = image::RgbImage::new(w, h);
    for (x, y, p) in img.enumerate_pixels_mut() {
        let r = ((x * 255 / w.max(1)) as u8).wrapping_add((y % 7) as u8);
        let g = ((y * 255 / h.max(1)) as u8).wrapping_add((x % 5) as u8);
        let b = ((x + y) % 255) as u8;
        *p = image::Rgb([r, g, b]);
    }
    let mut buf = Vec::new();
    image::DynamicImage::ImageRgb8(img)
        .write_to(&mut Cursor::new(&mut buf), image::ImageFormat::Png)
        .unwrap();
    buf
}

fn bench_extract(c: &mut Criterion) {
    let png = make_png(512, 512);
    c.bench_function("extract_512px", |b| {
        b.iter(|| {
            let _ = extract_from_bytes(black_box(&png), PaletteOptions::default());
        })
    });

    let small = make_png(64, 64);
    c.bench_function("extract_64px", |b| {
        b.iter(|| {
            let _ = extract_from_bytes(black_box(&small), PaletteOptions::default());
        })
    });
}

criterion_group!(benches, bench_extract);
criterion_main!(benches);
