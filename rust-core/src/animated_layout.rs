//! FFI shim for the C `GtkLayoutManager` that owns a container's content size.
//!
//! The implementation lives in `kn_animated_size_layout.c` (see the comment there for why
//! it is C and not gtk4-rs). It is deliberately "dumb": it applies the animated main-axis
//! value pushed from Kotlin and reports the natural size of the content back. The animation
//! itself (spec, retarget, interruption) and the clock live in Compose — see
//! `docs/tasks/compose-owned-animated-size.md`.
//!
//! UniFFI cannot carry pointers, so the widget handle travels as `u64`; every call must
//! happen on the GTK main thread (asserted in debug builds, logged otherwise).

use core::ffi::{c_int, c_void};
use std::sync::OnceLock;

extern "C" {
    fn kn_animated_size_layout_set_fixed(widget: *mut c_void, fixed: i32);
    fn kn_animated_size_layout_set_cross(widget: *mut c_void, fixed: i32);
    fn kn_animated_size_layout_set_current(widget: *mut c_void, current: f64);
    fn kn_animated_size_layout_content(widget: *mut c_void) -> f64;

    fn kn_attach_animated_size_layout(
        widget: *mut c_void,
        duration_ms: u32,
        easing: u32,
        spacing: u32,
        axis: u32,
    );
}

// GLib main-context API, used only to validate the "main thread only" contract below.
// `gboolean` is a C `int`.
extern "C" {
    fn g_main_context_default() -> *mut c_void;
    fn g_main_context_is_owner(context: *mut c_void) -> c_int;
    fn g_thread_self() -> *mut c_void;
}

/// `GThread*` of the UI thread (as an integer), pinned by the first layout-manager call.
///
/// `g_main_context_is_owner()` is only TRUE while the main context is being dispatched by
/// its owner, and this shell composes its first window *before* `g_main_loop_run()` (see
/// `ShellRuntime.createWindow` / `run`), i.e. on a main thread that does not own a context
/// yet. `g_thread_self()` is stable per thread, so the first call — `attach`, made from the
/// thread that builds the widget tree — pins the UI thread and every later call is compared
/// against it. A call from another thread still compares unequal, so real violations are
/// caught instead of silently accepted.
static UI_THREAD: OnceLock<usize> = OnceLock::new();

fn current_thread() -> usize {
    unsafe { g_thread_self() as usize }
}

/// Whether the current thread is the GTK UI thread.
fn is_gtk_main_thread() -> bool {
    unsafe {
        let context = g_main_context_default();
        if !context.is_null() && g_main_context_is_owner(context) != 0 {
            // Dispatching the main loop: definitively the UI thread.
            let _ = UI_THREAD.set(current_thread());
            return true;
        }
    }
    match UI_THREAD.get() {
        Some(ui_thread) => *ui_thread == current_thread(),
        None => {
            // First call, outside a dispatch iteration. It is the attach from the
            // widget-building thread, so this thread is the UI thread; pin it.
            let _ = UI_THREAD.set(current_thread());
            true
        }
    }
}

/// Guards the "GTK main thread only" contract of every layout-manager call.
///
/// A GTK widget must only be touched from the UI thread — the thread that owns the default
/// main context while the main loop dispatches. Calling `gtk_widget_queue_resize()` from
/// another thread corrupts the frame clock and the layout state, so a debug build asserts;
/// release builds log and keep going (the call is still forwarded — the caller's state is
/// already wrong at that point).
fn check_main_thread(operation: &str) {
    if is_gtk_main_thread() {
        return;
    }
    eprintln!(
        "[rust/animated_layout] {operation} called off the GTK main thread; \
         the animated-size layout manager is main-thread only"
    );
    debug_assert!(false, "{operation} called off the GTK main thread");
}

/// Attaches the animated layout manager to `widget` (raw `GtkWidget*` as `u64`).
///
/// `duration_ms` and `easing` are DEPRECATED and ignored: the animation is owned by Compose
/// now, so only `spacing` and `axis` are applied here. The parameters stay in the signature
/// so existing Kotlin call sites keep compiling (and the C entry point keeps its ABI).
#[uniffi::export]
pub fn attach_animated_size_layout(
    widget: u64,
    duration_ms: u32,
    easing: u32,
    spacing: u32,
    axis: u32,
) {
    if widget == 0 {
        return;
    }
    check_main_thread("attach_animated_size_layout");
    unsafe {
        kn_attach_animated_size_layout(
            widget as *mut c_void,
            duration_ms,
            easing,
            spacing,
            axis,
        )
    }
}

/// Records an explicit main-axis size (`fixed >= 0`), or restores content-driven sizing
/// (`fixed < 0`).
///
/// After the first [`set_animated_size_current`] this value no longer drives `measure()`:
/// Compose derives the target from the content size or from this value and animates
/// `current` towards it, so switching content <-> explicit cannot jump by itself.
#[uniffi::export]
pub fn set_animated_size_fixed(widget: u64, fixed: i32) {
    if widget == 0 {
        return;
    }
    check_main_thread("set_animated_size_fixed");
    unsafe { kn_animated_size_layout_set_fixed(widget as *mut c_void, fixed) }
}

/// Fixes the container's cross axis to an explicit size (`fixed >= 0`), or restores
/// content-driven sizing (`fixed < 0`).
#[uniffi::export]
pub fn set_animated_size_cross(widget: u64, fixed: i32) {
    if widget == 0 {
        return;
    }
    check_main_thread("set_animated_size_cross");
    unsafe { kn_animated_size_layout_set_cross(widget as *mut c_void, fixed) }
}

/// Applies the Compose-owned animated main-axis size (px) and marks the layout as ready.
///
/// Call once per frame from the GTK frame clock; values closer than 0.01 px are coalesced in
/// C (no resize pass). The first call switches the container from content-driven to
/// explicitly applied size, so Compose is responsible for snapping it to the initial target.
#[uniffi::export]
pub fn set_animated_size_current(widget: u64, current: f64) {
    if widget == 0 || !current.is_finite() || current < 0.0 {
        return;
    }
    check_main_thread("set_animated_size_current");
    unsafe { kn_animated_size_layout_set_current(widget as *mut c_void, current) }
}

/// Natural main-axis size of the container's content (children + spacing), in px.
///
/// Returns `0.0` for a null handle or for a widget that is not an animated-size container.
/// This is the animation target for content-driven sizing; it is measured with
/// `for_size = -1`, so it does not depend on the current animated value and probing it every
/// frame cannot feed back into the animation.
#[uniffi::export]
pub fn animated_size_content(widget: u64) -> f64 {
    if widget == 0 {
        return 0.0;
    }
    check_main_thread("animated_size_content");
    unsafe { kn_animated_size_layout_content(widget as *mut c_void) }
}
