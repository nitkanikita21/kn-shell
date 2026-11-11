//! FFI shim for the C `GtkLayoutManager` that animates a container's width.
//!
//! The implementation lives in `kn_animated_size_layout.c` (see the comment there for why
//! it is C and not gtk4-rs). UniFFI cannot carry pointers, so the widget handle travels as
//! `u64`; the call must happen on the GTK main thread.

extern "C" {
    fn kn_attach_animated_size_layout(
        widget: *mut core::ffi::c_void,
        duration_ms: u32,
        easing: u32,
    );
}

/// Attaches the animated layout manager to `widget` (raw `GtkWidget*` as `u64`).
#[uniffi::export]
pub fn attach_animated_size_layout(widget: u64, duration_ms: u32, easing: u32) {
    if widget == 0 {
        return;
    }
    unsafe { kn_attach_animated_size_layout(widget as *mut core::ffi::c_void, duration_ms, easing) }
}
