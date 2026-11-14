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

mod animated_layout;
mod audio;
mod battery;
mod hyprland;
mod network;
mod notifications;
mod media;
mod spectrum;
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

/// One action attached to a notification (`key` is sent back on invoke, `label` is shown).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NotificationAction {
    pub key: String,
    pub label: String,
}

/// One desktop notification observed on the session bus.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NotificationInfo {
    pub id: u64,
    pub app: String,
    pub summary: String,
    pub body: String,
    /// `1` normal, `2` critical (freedesktop hints).
    pub urgency: u8,
    pub timestamp_ms: u64,
    /// `image-path` hint or the app icon (file path or icon name, may be empty).
    pub icon: String,
    /// Action buttons in display order.
    pub actions: Vec<NotificationAction>,
}

/// Current network throughput across all non-loopback interfaces.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NetworkState {
    pub rx_bytes_per_sec: u64,
    pub tx_bytes_per_sec: u64,
    /// Cumulative bytes received on the default-route interface since boot.
    pub total_rx_bytes: u64,
    /// Cumulative bytes sent on the default-route interface since boot.
    pub total_tx_bytes: u64,
    /// Interface the counters belong to (e.g. `wlo1`).
    pub interface: String,
    /// `wifi`, `ethernet` or `disconnected`.
    pub connection_type: String,
    /// SSID when on wifi, empty otherwise.
    pub ssid: String,
    /// Signal strength in percent (0 when not on wifi).
    pub signal_strength: i32,
    /// IPv4 address of the interface, empty when unknown.
    pub ip_address: String,
}

/// Battery state from `/sys/class/power_supply`.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct BatteryState {
    pub present: bool,
    pub percent: i32,
    pub charging: bool,
    pub status: String,
}

/// Currently focused Hyprland window.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ActiveWindow {
    pub class: String,
    pub title: String,
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

/// Runs a raw Hyprland dispatcher payload through `hyprctl dispatch`.
///
/// Hyprland >= 0.56 evaluates dispatchers as Lua, so callers pass a full Lua
/// expression such as `hl.dsp.focus({ workspace = "2" })`.
#[uniffi::export]
pub fn hyprctl_dispatch(lua: String) {
    if !run_hyprctl_dispatch(&lua) {
        eprintln!("[rust/actions] hyprctl dispatch failed: {lua}");
    }
}

/// Launches `sh -c <command>` detached from the shell process.
///
/// Output is discarded so the child never blocks on the shell's stdio; a reaper thread
/// waits for the child to avoid leaving a zombie behind.
#[uniffi::export]
pub fn spawn_command(command: String) {
    match std::process::Command::new("sh")
        .arg("-c")
        .arg(&command)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
    {
        Ok(mut child) => {
            std::thread::spawn(move || {
                let _ = child.wait();
            });
        }
        Err(error) => eprintln!("[rust/actions] failed to spawn `{command}`: {error}"),
    }
}

/// Runs `hyprctl dispatch <lua>`; returns whether the dispatcher was accepted.
fn run_hyprctl_dispatch(lua: &str) -> bool {
    match std::process::Command::new("hyprctl")
        .args(["dispatch", lua])
        .output()
    {
        Ok(output) if output.status.success() => true,
        Ok(output) => {
            eprintln!(
                "[rust/actions] hyprctl dispatch rejected: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            );
            false
        }
        Err(error) => {
            eprintln!("[rust/actions] failed to run hyprctl: {error}");
            false
        }
    }
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

    if run_hyprctl_dispatch(&lua) {
        return;
    }

    // Legacy dispatcher syntax for older Hyprland releases.
    let _ = std::process::Command::new("hyprctl")
        .args(["dispatch", "workspace", &name])
        .status();
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


/// Audio spectrum frames from CAVA (values in `0..=100`, one per bar).
#[uniffi::export(callback_interface)]
pub trait SpectrumListener: Send + Sync {
    fn on_spectrum(&self, bars: Vec<i32>);
}

pub(crate) fn spectrum_listeners() -> &'static Mutex<Vec<Arc<dyn SpectrumListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn SpectrumListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

fn broadcast_spectrum(bars: Vec<i32>) {
    let listeners: Vec<Arc<dyn SpectrumListener>> = spectrum_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_spectrum(bars.clone());
    }
}

pub(crate) use broadcast_spectrum as publish_spectrum;

#[uniffi::export]
pub fn subscribe_spectrum(listener: Box<dyn SpectrumListener>) {
    spectrum_listeners().lock().unwrap().push(Arc::from(listener));
    spectrum::ensure_started();
}


/// Battery updates (20..=100 %), event-free polling in Rust.
#[uniffi::export(callback_interface)]
pub trait BatteryListener: Send + Sync {
    fn on_battery(&self, state: BatteryState);
}

pub(crate) fn battery_listeners() -> &'static Mutex<Vec<Arc<dyn BatteryListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn BatteryListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

fn broadcast_battery(state: BatteryState) {
    let listeners: Vec<Arc<dyn BatteryListener>> = battery_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_battery(state.clone());
    }
}

pub(crate) use broadcast_battery as publish_battery;

#[uniffi::export]
pub fn subscribe_battery(listener: Box<dyn BatteryListener>) {
    battery_listeners().lock().unwrap().push(Arc::from(listener));
    battery::ensure_started();
}

/// Hyprland focus events: active window and submap.
#[uniffi::export(callback_interface)]
pub trait HyprlandListener: Send + Sync {
    fn on_active_window(&self, window: ActiveWindow);
    fn on_submap(&self, name: String);
}

pub(crate) fn hyprland_listeners() -> &'static Mutex<Vec<Arc<dyn HyprlandListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn HyprlandListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

fn broadcast_active_window(window: ActiveWindow) {
    let listeners: Vec<Arc<dyn HyprlandListener>> = hyprland_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_active_window(window.clone());
    }
}

fn broadcast_submap(name: String) {
    let listeners: Vec<Arc<dyn HyprlandListener>> = hyprland_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_submap(name.clone());
    }
}

pub(crate) use broadcast_active_window as publish_active_window;
pub(crate) use broadcast_submap as publish_submap;

#[uniffi::export]
pub fn subscribe_hyprland(listener: Box<dyn HyprlandListener>) {
    hyprland_listeners().lock().unwrap().push(Arc::from(listener));
    hyprland::ensure_started();
}


/// Network throughput updates (once per second).
#[uniffi::export(callback_interface)]
pub trait NetworkListener: Send + Sync {
    fn on_network(&self, state: NetworkState);
}

pub(crate) fn network_listeners() -> &'static Mutex<Vec<Arc<dyn NetworkListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn NetworkListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

fn broadcast_network(state: NetworkState) {
    let listeners: Vec<Arc<dyn NetworkListener>> = network_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_network(state.clone());
    }
}

pub(crate) use broadcast_network as publish_network;

#[uniffi::export]
pub fn subscribe_network(listener: Box<dyn NetworkListener>) {
    network_listeners().lock().unwrap().push(Arc::from(listener));
    network::ensure_started();
}


/// Desktop notifications observed on the session bus.
#[uniffi::export(callback_interface)]
pub trait NotificationListener: Send + Sync {
    fn on_notification(&self, notification: NotificationInfo);
    fn on_notification_closed(&self, id: u64, reason: u32);
}

pub(crate) fn notification_listeners() -> &'static Mutex<Vec<Arc<dyn NotificationListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn NotificationListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

fn broadcast_notification(notification: NotificationInfo) {
    let listeners: Vec<Arc<dyn NotificationListener>> = notification_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_notification(notification.clone());
    }
}

pub(crate) use broadcast_notification as publish_notification;

fn broadcast_notification_closed(id: u64, reason: u32) {
    let listeners: Vec<Arc<dyn NotificationListener>> = notification_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_notification_closed(id, reason);
    }
}

pub(crate) use broadcast_notification_closed as publish_notification_closed;

#[uniffi::export]
pub fn subscribe_notifications(listener: Box<dyn NotificationListener>) {
    notification_listeners().lock().unwrap().push(Arc::from(listener));
    notifications::ensure_started();
}
