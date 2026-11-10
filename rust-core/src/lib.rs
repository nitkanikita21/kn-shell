//! `kn-shell-core` — Rust backend of the KN Shell.
//!
//! Public API is exported to Kotlin/Native through UniFFI (proc-macro mode).
//! Every service follows the same pattern:
//!
//! * listeners are stored in a process-global registry (`Arc<dyn …>`) behind a `Mutex`,
//! * a single background thread is started lazily on first subscription,
//! * Rust → Kotlin callbacks are invoked from that thread; UniFFI callback interfaces
//!   are `Send + Sync`, and Kotlin's new memory model makes the cross-thread call safe.
//!
//! The UI thread never calls into the services from GTK callbacks directly; state is
//! written from the Rust threads and Compose recomposition happens on the GLib loop.

mod audio;
mod media;
mod system;
mod windows;
mod workspaces;

use std::sync::{Arc, Mutex, OnceLock};

uniffi::setup_scaffolding!();

/// Information about a single toplevel window exposed by the compositor.
#[derive(Debug, Clone, uniffi::Record)]
pub struct WindowInfo {
    pub title: String,
    pub app_id: String,
}

/// Current state of the active MPRIS media player.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MediaState {
    pub title: String,
    pub artist: String,
    pub playing: bool,
    pub player: String,
    pub available: bool,
}

/// A Hyprland workspace as reported by the compositor IPC.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct WorkspaceInfo {
    pub id: i32,
    pub name: String,
    pub active: bool,
}

/// CPU / memory usage of the machine.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SystemStats {
    pub cpu_percent: i32,
    pub mem_percent: i32,
}

#[uniffi::export(callback_interface)]
pub trait WindowListener: Send + Sync {
    fn on_windows(&self, windows: Vec<WindowInfo>);
}

#[uniffi::export(callback_interface)]
pub trait MediaListener: Send + Sync {
    fn on_media(&self, state: MediaState);
}

#[uniffi::export(callback_interface)]
pub trait VolumeListener: Send + Sync {
    fn on_volume(&self, volume: i32);
}

#[uniffi::export(callback_interface)]
pub trait WorkspaceListener: Send + Sync {
    fn on_workspaces(&self, workspaces: Vec<WorkspaceInfo>);
}

#[uniffi::export(callback_interface)]
pub trait SystemListener: Send + Sync {
    fn on_stats(&self, stats: SystemStats);
}

// ---------------------------------------------------------------------------
// Listener registries
// ---------------------------------------------------------------------------

pub(crate) fn window_listeners() -> &'static Mutex<Vec<Arc<dyn WindowListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn WindowListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

pub(crate) fn media_listeners() -> &'static Mutex<Vec<Arc<dyn MediaListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn MediaListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

pub(crate) fn volume_listeners() -> &'static Mutex<Vec<Arc<dyn VolumeListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn VolumeListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

pub(crate) fn workspace_listeners() -> &'static Mutex<Vec<Arc<dyn WorkspaceListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn WorkspaceListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

pub(crate) fn system_listeners() -> &'static Mutex<Vec<Arc<dyn SystemListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn SystemListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

fn broadcast_windows(windows: Vec<WindowInfo>) {
    let listeners: Vec<Arc<dyn WindowListener>> = window_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_windows(windows.clone());
    }
}

fn broadcast_media(state: MediaState) {
    let listeners: Vec<Arc<dyn MediaListener>> = media_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_media(state.clone());
    }
}

fn broadcast_volume(volume: i32) {
    let listeners: Vec<Arc<dyn VolumeListener>> = volume_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_volume(volume);
    }
}

fn broadcast_workspaces(workspaces: Vec<WorkspaceInfo>) {
    let listeners: Vec<Arc<dyn WorkspaceListener>> = workspace_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_workspaces(workspaces.clone());
    }
}

fn broadcast_system_stats(stats: SystemStats) {
    let listeners: Vec<Arc<dyn SystemListener>> = system_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_stats(stats.clone());
    }
}

// ---------------------------------------------------------------------------
// UniFFI exports
// ---------------------------------------------------------------------------

/// Subscribes to the list of toplevel windows.
///
/// The first call spawns a dedicated Wayland thread that binds
/// `ext-foreign-toplevel-list-v1` and pushes the full window list on every change.
#[uniffi::export]
pub fn subscribe_windows(listener: Box<dyn WindowListener>) {
    window_listeners().lock().unwrap().push(Arc::from(listener));
    windows::ensure_started();
}

#[uniffi::export]
pub fn subscribe_media(listener: Box<dyn MediaListener>) {
    media_listeners().lock().unwrap().push(Arc::from(listener));
    media::ensure_started();
}

#[uniffi::export]
pub fn subscribe_volume(listener: Box<dyn VolumeListener>) {
    volume_listeners().lock().unwrap().push(Arc::from(listener));
    audio::ensure_started();
}

#[uniffi::export]
pub fn subscribe_workspaces(listener: Box<dyn WorkspaceListener>) {
    workspace_listeners().lock().unwrap().push(Arc::from(listener));
    workspaces::ensure_started();
}

#[uniffi::export]
pub fn subscribe_system(listener: Box<dyn SystemListener>) {
    system_listeners().lock().unwrap().push(Arc::from(listener));
    system::ensure_started();
}

#[uniffi::export]
pub fn toggle_play_pause() {
    media::toggle_play_pause();
}

/// Switches the focused Hyprland workspace (click on a workspace chip in the bar).
///
/// Hyprland >= 0.5x evaluates dispatchers as Lua (`hl.dsp.focus({ workspace = "..." })`);
/// older releases use the legacy `dispatch workspace <name>` form. We try the modern
/// spelling first and fall back to the legacy one.
#[uniffi::export]
pub fn focus_workspace(name: String) {
    let escaped = name.replace('\\', "\\\\").replace('"', "\\\"");
    let lua = format!("hl.dsp.focus({{ workspace = \"{escaped}\" }})");

    let modern_ok = std::process::Command::new("hyprctl")
        .args(["dispatch", &lua])
        .status()
        .map(|status| status.success())
        .unwrap_or(false);

    if !modern_ok {
        let _ = std::process::Command::new("hyprctl")
            .args(["dispatch", "workspace", &name])
            .status();
    }
}

/// Sets the volume of the default sink (0-150 %).
#[uniffi::export]
pub fn set_volume(volume: i32) {
    audio::set_volume(volume.clamp(0, 150));
}

/// Phase 0 proof that Kotlin can call into Rust.
#[uniffi::export]
pub fn hello() -> String {
    "Hello from Rust via UniFFI".to_string()
}

pub(crate) use broadcast_media as publish_media;
pub(crate) use broadcast_system_stats as publish_system_stats;
pub(crate) use broadcast_volume as publish_volume;
pub(crate) use broadcast_windows as publish_windows;
pub(crate) use broadcast_workspaces as publish_workspaces;
