//! FFI shim for the C `GtkLayoutManager` that packs every `Row`/`Column`/`Box` of the DSL.
//!
//! The implementation lives in `kn_box_layout.c` (see the comment there for why it is C and not
//! gtk4-rs — attaching a Rust subclass corrupts the widget it is given). One manager serves both
//! jobs:
//!
//!  * plain box packing — orientation, spacing, homogeneous, per-child `weight`, main-axis
//!    alignment via a cell (so Kotlin never has to switch on `hexpand` to align something);
//!  * the Compose-owned animated main-axis size — the manager only applies the value Compose
//!    pushes and reports the natural content size back. The animation itself (spec, retarget,
//!    interruption) and the clock live in Compose, see
//!    `docs/tasks/compose-owned-animated-size.md`.
//!
//! UniFFI cannot carry pointers, so widget and child handles travel as `u64`; every call must
//! happen on the GTK main thread (asserted in debug builds, logged otherwise).
use core::ffi::{c_double, c_int, c_void};
use std::sync::OnceLock;

extern "C" {
    fn kn_box_attach(widget: *mut c_void, orientation: c_int, spacing: c_int, homogeneous: c_int);
    fn kn_box_set_orientation(widget: *mut c_void, orientation: c_int);
    fn kn_box_set_spacing(widget: *mut c_void, spacing: c_int);
    fn kn_box_set_homogeneous(widget: *mut c_void, homogeneous: c_int);
    fn kn_box_set_weight(widget: *mut c_void, child: *mut c_void, weight: c_double);
    fn kn_box_get_weight(widget: *mut c_void, child: *mut c_void) -> c_double;
    fn kn_box_forget_child(widget: *mut c_void, child: *mut c_void);
    fn kn_box_content(widget: *mut c_void) -> f64;
    fn kn_box_set_current(widget: *mut c_void, current: f64);
    fn kn_box_set_fixed(widget: *mut c_void, fixed: c_int);
    fn kn_box_set_cross(widget: *mut c_void, fixed: c_int);
    fn kn_box_is_ready(widget: *mut c_void) -> c_int;
    // Legacy name kept for the animated-size call sites (same job, now orientation-aware).
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
        "[rust/box_layout] {operation} called off the GTK main thread; \
         the box layout manager is main-thread only"
    );
    debug_assert!(false, "{operation} called off the GTK main thread");
}

fn handle(widget: u64) -> *mut c_void {
    if widget == 0 {
        std::ptr::null_mut()
    } else {
        widget as *mut c_void
    }
}

/// Makes `widget` a box container (raw `GtkWidget*` as `u64`) with the given orientation,
/// spacing and homogeneous flag. Idempotent: re-attaching is a no-op, so the composable may
/// call it on every recomposition.
#[uniffi::export]
pub fn attach_box_layout(widget: u64, orientation: i32, spacing: i32, homogeneous: bool) {
    if widget == 0 {
        return;
    }
    check_main_thread("attach_box_layout");
    unsafe {
        kn_box_attach(
            handle(widget),
            orientation,
            spacing.max(0),
            c_int::from(homogeneous),
        )
    }
}

/// Re-orients an existing box container (Row <-> Column) without recreating its children.
#[uniffi::export]
pub fn set_box_orientation(widget: u64, orientation: i32) {
    if widget == 0 {
        return;
    }
    check_main_thread("set_box_orientation");
    unsafe { kn_box_set_orientation(handle(widget), orientation) }
}

/// Sets the gap between children in px. This is the ONLY spacing channel of a managed box:
/// `gtk_box_set_spacing()` writes to the stock `GtkBoxLayout`, which is dead once the manager
/// is attached (see docs/layout-model.md).
#[uniffi::export]
pub fn set_box_spacing(widget: u64, spacing: i32) {
    if widget == 0 {
        return;
    }
    check_main_thread("set_box_spacing");
    unsafe { kn_box_set_spacing(handle(widget), spacing.max(0)) }
}

/// Equal cells for every child (the `GtkBox` `homogeneous` property, reimplemented here because
/// the stock layout is replaced).
#[uniffi::export]
pub fn set_box_homogeneous(widget: u64, homogeneous: bool) {
    if widget == 0 {
        return;
    }
    check_main_thread("set_box_homogeneous");
    unsafe { kn_box_set_homogeneous(handle(widget), c_int::from(homogeneous)) }
}

/// Sets the Android-style `weight` of `child` inside `widget`. `weight <= 0` clears it, which
/// matters: a `Modifier` chain is rebuilt from scratch every recomposition, so "absent" has to
/// mean "default" (plan L9).
#[uniffi::export]
pub fn set_box_child_weight(widget: u64, child: u64, weight: f64) {
    if widget == 0 || child == 0 {
        return;
    }
    check_main_thread("set_box_child_weight");
    unsafe { kn_box_set_weight(handle(widget), handle(child), weight) }
}

/// Current weight of `child` (0.0 = no weight). Diagnostics and tests.
#[uniffi::export]
pub fn get_box_child_weight(widget: u64, child: u64) -> f64 {
    if widget == 0 || child == 0 {
        return 0.0;
    }
    check_main_thread("get_box_child_weight");
    unsafe { kn_box_get_weight(handle(widget), handle(child)) as f64 }
}

/// Drops the per-child record of a child that left the container. Called from
/// `GtkApplier.removeChild` so the manager's table never outlives a node.
#[uniffi::export]
pub fn forget_box_child(widget: u64, child: u64) {
    if widget == 0 || child == 0 {
        return;
    }
    check_main_thread("forget_box_child");
    unsafe { kn_box_forget_child(handle(widget), handle(child)) }
}

// ---------------------------------------------------------------------------
// Animated main-axis size (Compose-owned). These keep their historical names so
// `dev.knshell.animation` does not change; they now drive the SAME manager.
// ---------------------------------------------------------------------------

/// Attaches/updates the animated axis of a container. `duration_ms` and `easing` are DEPRECATED
/// and ignored: the animation is owned by Compose, so only `spacing` and `axis` are applied.
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
    unsafe { kn_attach_animated_size_layout(handle(widget), duration_ms, easing, spacing, axis) }
}

/// Records an explicit main-axis size (`fixed >= 0`), or restores content-driven sizing
/// (`fixed < 0`). After the first [`set_animated_size_current`] this value no longer drives
/// `measure()`, so switching content <-> explicit cannot jump by itself.
#[uniffi::export]
pub fn set_animated_size_fixed(widget: u64, fixed: i32) {
    if widget == 0 {
        return;
    }
    check_main_thread("set_animated_size_fixed");
    unsafe { kn_box_set_fixed(handle(widget), fixed) }
}

/// Fixes the container's cross axis to an explicit size (`fixed >= 0`), or restores
/// content-driven sizing (`fixed < 0`).
#[uniffi::export]
pub fn set_animated_size_cross(widget: u64, fixed: i32) {
    if widget == 0 {
        return;
    }
    check_main_thread("set_animated_size_cross");
    unsafe { kn_box_set_cross(handle(widget), fixed) }
}

/// Applies the Compose-owned animated main-axis size (px) and marks the layout as ready.
#[uniffi::export]
pub fn set_animated_size_current(widget: u64, current: f64) {
    if widget == 0 || !current.is_finite() || current < 0.0 {
        return;
    }
    check_main_thread("set_animated_size_current");
    unsafe { kn_box_set_current(handle(widget), current) }
}

/// Natural main-axis size of the container's content (children + spacing), in px, measured with
/// `for_size = -1` so it cannot feed back into the animation.
#[uniffi::export]
pub fn animated_size_content(widget: u64) -> f64 {
    if widget == 0 {
        return 0.0;
    }
    check_main_thread("animated_size_content");
    unsafe { kn_box_content(handle(widget)) }
}

/// Whether Compose has already pushed at least one `current` (diagnostics).
#[uniffi::export]
pub fn animated_size_is_ready(widget: u64) -> bool {
    if widget == 0 {
        return false;
    }
    unsafe { kn_box_is_ready(handle(widget)) != 0 }
}
