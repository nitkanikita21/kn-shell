#![allow(unsafe_code)]
//! Raw C ABI for Kotlin/Native.
//!
//! Synchronous output/error strings are allocated with `CString::into_raw` and
//! must be freed by the caller (on the same thread that called
//! `palette_extract_sync`) via [`palette_free_string`].
//!
//! For `palette_extract_async`, the `callback` is invoked from the worker thread
//! that also allocated the `out`/`err` strings. The callback MUST free both
//! pointers with [`palette_free_string`] *before returning* — i.e. on that same
//! worker thread. Freeing them on any other thread is unsafe in the target
//! glibc (a cross-thread free of a tcache chunk aborts the process), so the
//! pointers are only valid for the duration of the callback. Copy anything you
//! need out of them first.

use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_void};
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use crate::{extract_from_path, extract_with_cancel, PaletteResult, PaletteError, PaletteOptions};

type CancelMap = Mutex<HashMap<u64, Arc<AtomicBool>>>;
static CANCELS: OnceLock<CancelMap> = OnceLock::new();
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

fn cancels() -> &'static CancelMap {
    CANCELS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn cstr_opt(p: *const c_char) -> Option<String> {
    if p.is_null() {
        return None;
    }
    unsafe { CStr::from_ptr(p).to_str().ok().map(|s| s.to_string()) }
}

fn cstr_lossy(p: *const c_char) -> String {
    cstr_opt(p).unwrap_or_default()
}

fn to_cstring(s: &str) -> *mut c_char {
    CString::new(s)
        .map(|c| c.into_raw())
        .unwrap_or(ptr::null_mut())
}

fn parse_opts(json: *const c_char) -> Result<PaletteOptions, String> {
    match cstr_opt(json) {
        None => Ok(PaletteOptions::default()),
        Some(s) if s.is_empty() => Ok(PaletteOptions::default()),
        Some(s) => serde_json::from_str(&s).map_err(|e| format!("bad options json: {e}")),
    }
}

fn palette_to_json(p: &PaletteResult) -> String {
    serde_json::to_string(p).unwrap_or_else(|_| {
        "{\"colors\":[],\"suggested_hue\":null,\"suggested_chroma\":0,\"suggested_mode\":\"light\",\"is_fallback\":true,\"source_hash\":\"\"}".to_string()
    })
}

fn error_code(e: &PaletteError) -> i32 {
    match e {
        PaletteError::Io(_) => 4,
        PaletteError::Decode(_) => 3,
        PaletteError::UnsupportedFormat(_) => 2,
        PaletteError::EmptyImage => 5,
        PaletteError::Internal(_) => 6,
    }
}

/// Synchronous extraction.
///
/// Blocks the calling thread for the whole decode + cluster pipeline. Prefer
/// [`palette_extract_async`] on a UI thread.
///
/// - `path`: NUL-terminated UTF-8 path to the image. NULL, an empty string, or
///   non-UTF-8 bytes are treated alike and rejected with status `1`.
/// - `opts_json`: NUL-terminated UTF-8 JSON encoding of [`PaletteOptions`]. NULL,
///   an empty string, or non-UTF-8 bytes select the defaults; well-formed UTF-8
///   that is not valid JSON of the expected shape yields status `2`.
///   Missing fields take their documented defaults.
/// - `out_json`: out-parameter for a newly allocated NUL-terminated JSON result
///   (see [`PaletteResult`]). Written only on success; left untouched on error.
///   May be NULL if the caller does not want the payload. A non-NULL value must
///   be released exactly once with [`palette_free_string`] on the thread that
///   called this function.
/// - `err_out`: out-parameter for a newly allocated NUL-terminated human-readable
///   error message. Written only on failure; left untouched on success. May be
///   NULL. Must also be freed with [`palette_free_string`]. Callers should
///   initialise both out-parameters to NULL before the call.
///
/// Returns `0` on success and a non-zero status on failure: `1` for a NULL/empty/
/// non-UTF-8 `path`, `2` for malformed options JSON or an unsupported image
/// format, `3` for a decode failure, `4` for an I/O failure, `5` for a
/// zero-sized image, `6` for an internal failure (mirrors [`PaletteError`]).
///
/// The error text is in `*err_out` when that pointer is non-NULL. Invalid input
/// is reported through the status code and `err_out`; the function does not
/// panic on it.
// These wrappers validate their pointers and are intended to be safe to call
// from any caller (including Rust tests and the FFI boundary), so they are not
// marked `unsafe fn`; clippy would otherwise require `unsafe`.
#[allow(clippy::not_unsafe_ptr_arg_deref)]
#[no_mangle]
pub extern "C" fn palette_extract_sync(
    path: *const c_char,
    opts_json: *const c_char,
    out_json: *mut *mut c_char,
    err_out: *mut *mut c_char,
) -> i32 {
    let path_s = cstr_lossy(path);
    if path_s.is_empty() {
        unsafe {
            if !err_out.is_null() {
                *err_out = to_cstring("null or empty path");
            }
        }
        return 1;
    }

    let opts = match parse_opts(opts_json) {
        Ok(o) => o,
        Err(e) => {
            unsafe {
                if !err_out.is_null() {
                    *err_out = to_cstring(&e);
                }
            }
            return 2;
        }
    };

    match extract_from_path(std::path::Path::new(&path_s), opts) {
        Ok(pal) => {
            unsafe {
                if !out_json.is_null() {
                    *out_json = to_cstring(&palette_to_json(&pal));
                }
            }
            0
        }
        Err(e) => {
            let code = error_code(&e);
            unsafe {
                if !err_out.is_null() {
                    *err_out = to_cstring(&e.to_string());
                }
            }
            code
        }
    }
}

/// Asynchronous, non-blocking extraction.
///
/// The work runs on a dedicated thread (k-means itself uses the rayon pool). The
/// caller-supplied `callback` is invoked exactly once — with a result, an error,
/// or `"cancelled"` — from that worker thread. Returns immediately with a
/// non-zero handle (0 is reserved and never returned) that can be passed to
/// [`palette_cancel`].
///
/// - `path`: NUL-terminated UTF-8 path. Unlike the sync entry point a NULL, empty
///   or non-UTF-8 path is not rejected up front: the worker's read fails and the
///   callback receives an I/O error string.
/// - `opts_json`: NULL, empty or non-UTF-8 selects defaults; malformed JSON also
///   silently falls back to the defaults (the sync path reports status `2`
///   instead).
/// - `callback`: must be a non-NULL function pointer; passing NULL is undefined
///   behaviour. It receives `(result, err, user_data)` where exactly one of
///   `result` / `err` is non-NULL — `result` is NUL-terminated JSON (see
///   [`PaletteResult`]) on success, `err` is a NUL-terminated message otherwise
///   (the literal `"cancelled"` when [`palette_cancel`] won the race). Both
///   pointers are allocated on the worker thread and are valid only for the
///   duration of the callback: the callback MUST free BOTH with
///   [`palette_free_string`] *before returning*, i.e. on that same worker thread.
///   Freeing on any other thread is unsafe in the target glibc (a cross-thread
///   free of a tcache chunk aborts the process). Copy anything you need out of
///   them first.
/// - `user_data`: opaque pointer echoed back to the callback unchanged. May be
///   NULL. palette-core never dereferences it, so it may be any pointer the
///   caller can legally hold across threads until the callback fires.
///
/// The callback is one-shot per handle: it fires even when cancellation was
/// requested, and a second call for the same handle never happens. Cancelling
/// after the callback returned is a no-op.
#[allow(clippy::not_unsafe_ptr_arg_deref)]
#[no_mangle]
pub extern "C" fn palette_extract_async(
    path: *const c_char,
    opts_json: *const c_char,
    callback: extern "C" fn(*const c_char, *const c_char, *mut c_void),
    user_data: *mut c_void,
) -> u64 {
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    let cancel = Arc::new(AtomicBool::new(false));
    cancels().lock().unwrap().insert(handle, cancel.clone());

    let path_s = cstr_lossy(path);
    let opts = parse_opts(opts_json).unwrap_or_default();
    // `*mut c_void` is not `Send`; carry it across the thread boundary as an
    // address-sized integer and cast back at the call site.
    let user_data_addr = user_data as usize;

    std::thread::spawn(move || {
        let result = if cancel.load(Ordering::Relaxed) {
            Err(crate::error::cancelled())
        } else {
            match std::fs::read(&path_s) {
                Ok(bytes) => extract_with_cancel(&bytes, opts, &cancel),
                Err(e) => Err(PaletteError::Io(e)),
            }
        };

        let (out, err) = match result {
            Ok(pal) => (to_cstring(&palette_to_json(&pal)), ptr::null_mut()),
            Err(PaletteError::Internal(msg)) if msg == crate::error::CANCELLED => {
                (ptr::null_mut(), to_cstring("cancelled"))
            }
            Err(e) => (ptr::null_mut(), to_cstring(&e.to_string())),
        };

        callback(out, err, user_data_addr as *mut c_void);

        cancels().lock().unwrap().remove(&handle);
    });

    handle
}

/// Request cancellation of an in-flight async extraction.
///
/// Safe to call with an unknown/stale handle (it is a no-op), and safe to call
/// more than once. Cancelling sets a flag that the extraction loop checks between
/// steps (after decode and between k-means iterations), so the abort is
/// cooperative: a run that has already finished still delivers its normal
/// result. The callback still fires exactly once, with `"cancelled"` as the
/// error string.
///
/// Cancellation is not retroactive: handles are forgotten as soon as their
/// callback has run, so calling this afterwards does nothing.
#[allow(clippy::not_unsafe_ptr_arg_deref)]
#[no_mangle]
pub extern "C" fn palette_cancel(handle: u64) {
    if let Some(c) = cancels().lock().unwrap().get(&handle) {
        c.store(true, Ordering::Relaxed);
    }
}

/// Free a string previously returned by palette-core. Safe to call with NULL.
///
/// `s` must be a pointer returned by [`palette_extract_sync`] (written to
/// `out_json` / `err_out`) or passed to a [`palette_extract_async`] callback, and
/// it must be freed exactly once. Freeing any other pointer, freeing twice, or
/// freeing it on a thread other than the one that allocated it is undefined
/// behaviour — for the async path that means inside the callback, on the worker
/// thread.
#[allow(clippy::not_unsafe_ptr_arg_deref)]
#[no_mangle]
pub extern "C" fn palette_free_string(s: *mut c_char) {
    if s.is_null() {
        return;
    }
    unsafe {
        drop(CString::from_raw(s));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn free_null_is_safe() {
        palette_free_string(ptr::null_mut());
    }
}
