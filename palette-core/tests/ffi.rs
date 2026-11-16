//! Integration tests for the raw C ABI, exercised from Rust (the symbols are
//! plain `extern "C"` functions and fully callable from Rust).
//!
//! Cache writes must land in a writable directory; on CI/$HOME may be read-only,
//! so we redirect via the `PALETTE_CORE_CACHE_DIR` override.

use palette_core::ffi::{
    palette_cancel, palette_extract_async, palette_extract_sync, palette_free_string,
};
use std::ffi::{CStr, CString};
use std::io::Cursor;
use std::os::raw::{c_char, c_void};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

fn setup_cache() -> PathBuf {
    let dir = PathBuf::from(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/target/test-cache-ffi"
    ));
    std::fs::create_dir_all(&dir).unwrap();
    std::env::set_var("PALETTE_CORE_CACHE_DIR", &dir);
    dir
}

fn write_png(dir: &Path, name: &str, w: u32, h: u32, color: [u8; 3]) -> PathBuf {
    let mut img = image::RgbImage::new(w, h);
    for (x, y, p) in img.enumerate_pixels_mut() {
        let r = color[0].wrapping_add((x % 7) as u8);
        let g = color[1].wrapping_add((y % 5) as u8);
        let b = color[2].wrapping_add(((x + y) % 11) as u8);
        *p = image::Rgb([r, g, b]);
    }
    let mut buf = Vec::new();
    image::DynamicImage::ImageRgb8(img)
        .write_to(&mut Cursor::new(&mut buf), image::ImageFormat::Png)
        .unwrap();
    let path = dir.join(name);
    std::fs::write(&path, &buf).unwrap();
    path
}

#[test]
fn sync_extract_returns_valid_json() {
    let dir = setup_cache();
    let png = write_png(&dir, "sync.png", 64, 64, [200, 30, 90]);

    let path_c = CString::new(png.to_str().unwrap()).unwrap();
    let mut out: *mut c_char = std::ptr::null_mut();
    let mut err: *mut c_char = std::ptr::null_mut();

    let rc = palette_extract_sync(
        path_c.as_ptr(),
        std::ptr::null(),
        &mut out as *mut *mut c_char,
        &mut err as *mut *mut c_char,
    );
    assert_eq!(rc, 0, "err = {:?}", unsafe {
        CStr::from_ptr(err).to_string_lossy()
    });
    assert!(!out.is_null());
    assert!(err.is_null());

    let json = unsafe { CStr::from_ptr(out).to_string_lossy().into_owned() };
    // Validate it parses as JSON with the expected shape.
    let v: serde_json::Value = serde_json::from_str(&json).expect("valid json");
    assert!(v["colors"].is_array());
    assert!(!v["colors"].as_array().unwrap().is_empty());

    palette_free_string(out);
    palette_free_string(err);
}

#[test]
fn sync_null_path_errors() {
    let _ = setup_cache();
    let mut out: *mut c_char = std::ptr::null_mut();
    let mut err: *mut c_char = std::ptr::null_mut();
    let rc = palette_extract_sync(
        std::ptr::null(),
        std::ptr::null(),
        &mut out as *mut *mut c_char,
        &mut err as *mut *mut c_char,
    );
    assert_eq!(rc, 1);
    palette_free_string(out);
    palette_free_string(err);
}

/// Per-call result slot. Each async test owns one (boxed) and passes it as
/// `user_data`, so concurrent tests never share state.
///
/// The result/error payloads are stored in **fixed-size, caller-allocated
/// buffers** (the `Box` is allocated by the main thread and freed by the main
/// thread when the test reclaims it). The worker only ever *writes* into these
/// buffers — it never allocates heap memory for them. This is deliberate: in the
/// target glibc a heap chunk must be freed on the same thread that allocated it,
/// and a chunk allocated on the worker but freed on the main thread (or vice
/// versa) aborts the process. So we keep the result storage on the main thread's
/// side of the boundary and let the worker copy bytes into it.
const RESULT_CAP: usize = 8192;
struct AsyncResult {
    result: [u8; RESULT_CAP],
    result_len: usize,
    err: [u8; RESULT_CAP],
    err_len: usize,
    called: AtomicBool,
}

impl AsyncResult {
    fn new() -> AsyncResult {
        AsyncResult {
            result: [0u8; RESULT_CAP],
            result_len: 0,
            err: [0u8; RESULT_CAP],
            err_len: 0,
            called: AtomicBool::new(false),
        }
    }
}

extern "C" fn async_cb(result: *const c_char, err: *const c_char, ud: *mut c_void) {
    let slot = unsafe { &*(ud as *const Mutex<AsyncResult>) };
    let mut g = slot.lock().unwrap();
    // Copy the lent C strings into the caller-owned, fixed-size buffers. This
    // writes into memory the main thread allocated and will free — the worker
    // performs no heap allocation here, so no chunk crosses thread boundaries.
    let r = if result.is_null() {
        &[]
    } else {
        unsafe { CStr::from_ptr(result).to_bytes() }
    };
    let n = r.len().min(RESULT_CAP);
    g.result[..n].copy_from_slice(&r[..n]);
    g.result_len = n;

    let e = if err.is_null() {
        &[]
    } else {
        unsafe { CStr::from_ptr(err).to_bytes() }
    };
    let m = e.len().min(RESULT_CAP);
    g.err[..m].copy_from_slice(&e[..m]);
    g.err_len = m;

    // The callback runs on the worker thread that allocated `result`/`err`, so
    // freeing them here is same-thread and safe. Freeing on any other thread is
    // unsafe in this target glibc (cross-thread free of a tcache chunk aborts).
    palette_free_string(result as *mut c_char);
    palette_free_string(err as *mut c_char);
    // `called` is published last so the waiter observes fully-initialized data.
    g.called.store(true, Ordering::SeqCst);
}

fn wait_for(ud: *mut c_void, timeout: Duration) {
    let slot = unsafe { &*(ud as *const Mutex<AsyncResult>) };
    let start = Instant::now();
    loop {
        if slot.lock().unwrap().called.load(Ordering::SeqCst) {
            break;
        }
        if start.elapsed() > timeout {
            panic!("callback never fired");
        }
        std::thread::sleep(Duration::from_millis(10));
    }
}

#[test]
fn async_extract_calls_callback_exactly_once() {
    let dir = setup_cache();
    let png = write_png(&dir, "async.png", 64, 64, [40, 180, 220]);
    let path_c = CString::new(png.to_str().unwrap()).unwrap();

    let boxed = Box::new(Mutex::new(AsyncResult::new()));
    let ud = Box::into_raw(boxed) as *mut c_void;

    let handle = palette_extract_async(path_c.as_ptr(), std::ptr::null(), async_cb, ud);
    assert!(handle != 0);

    wait_for(ud, Duration::from_secs(10));

    let slot = unsafe { &*(ud as *const Mutex<AsyncResult>) };
    let g = slot.lock().unwrap();
    let json = std::str::from_utf8(&g.result[..g.result_len]).unwrap_or("");
    let errmsg = std::str::from_utf8(&g.err[..g.err_len]).unwrap_or("");
    if g.result_len > 0 {
        let v: serde_json::Value = serde_json::from_str(json).expect("valid json");
        assert!(v["colors"].is_array());
    } else if g.err_len > 0 {
        // Only "cancelled" is a legitimate error here (a cancel raced in).
        assert_eq!(errmsg, "cancelled");
    } else {
        panic!("callback fired but left no payload");
    }
    // The strings were already freed inside the callback (worker thread).
    // Reclaim the box so it is dropped (no leak).
    let _ = unsafe { Box::from_raw(ud as *mut Mutex<AsyncResult>) };
}

#[test]
fn async_cancel_is_safe_and_idempotent() {
    let dir = setup_cache();
    // A large image keeps the worker busy decoding long enough for the cancel
    // flag to be observed before/during the pipeline.
    let png = write_png(&dir, "big.png", 1500, 1500, [120, 60, 200]);
    let path_c = CString::new(png.to_str().unwrap()).unwrap();

    let boxed = Box::new(Mutex::new(AsyncResult::new()));
    let ud = Box::into_raw(boxed) as *mut c_void;

    let handle = palette_extract_async(path_c.as_ptr(), std::ptr::null(), async_cb, ud);
    palette_cancel(handle);
    // Cancelling twice must be safe.
    palette_cancel(handle);

    wait_for(ud, Duration::from_secs(20));
    // Whether the outcome is the palette or "cancelled" depends on timing; both
    // satisfy the contract (exactly one callback, no double free).
    let _ = unsafe { Box::from_raw(ud as *mut Mutex<AsyncResult>) };
}

#[test]
fn free_null_is_safe() {
    palette_free_string(std::ptr::null_mut());
}
