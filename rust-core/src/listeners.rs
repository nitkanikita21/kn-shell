//! Listener traits and their process-global registries.
//!
//! Every service follows the same shape: Kotlin passes a callback interface into a
//! `subscribe_*` export (see the API module), it is boxed into an `Arc<dyn …Listener>`
//! and appended to a `OnceLock<Mutex<Vec<_>>>` registry defined here. The service
//! thread never locks the registry while calling out — it clones the vector first — so
//! callbacks may subscribe or unsubscribe from inside a callback without deadlocking.
//!
//! `publish_*` is the crate-internal alias each service module calls to fan a value out
//! to every registered listener; it is the only entry point into these registries.

use std::sync::{Arc, Mutex, OnceLock};

use crate::types::{
    ActiveWindow, BatteryState, MediaState, NetworkState, NotificationInfo, SystemStats,
    WindowInfo, WorkspaceInfo,
};

// ---------------------------------------------------------------------------
// Watchdog-free value callbacks
// ---------------------------------------------------------------------------

/// Receives the full toplevel window list on every change.
#[uniffi::export(callback_interface)]
pub trait WindowListener: Send + Sync {
    /// Called with a snapshot of all toplevel windows currently exposed by the compositor.
    fn on_windows(&self, windows: Vec<WindowInfo>);
}

/// Receives the state of the active MPRIS player.
#[uniffi::export(callback_interface)]
pub trait MediaListener: Send + Sync {
    /// Called on every `playerctl --follow` update.
    fn on_media(&self, state: MediaState);
}

/// Receives the volume of the default sink.
#[uniffi::export(callback_interface)]
pub trait VolumeListener: Send + Sync {
    /// Called when the volume changes; value is a percentage of the sink scale.
    fn on_volume(&self, volume: i32);
}

/// Receives the Hyprland workspace list.
#[uniffi::export(callback_interface)]
pub trait WorkspaceListener: Send + Sync {
    /// Called with a snapshot of all workspaces whenever one is added, removed or focused.
    fn on_workspaces(&self, workspaces: Vec<WorkspaceInfo>);
}

/// Receives periodic CPU / memory statistics.
#[uniffi::export(callback_interface)]
pub trait SystemListener: Send + Sync {
    /// Called once per poll interval with the freshly sampled stats.
    fn on_stats(&self, stats: SystemStats);
}

/// Audio spectrum frames from CAVA (values in `0..=100`, one per bar).
#[uniffi::export(callback_interface)]
pub trait SpectrumListener: Send + Sync {
    /// Called per CAVA frame; `bars` holds one amplitude per visualiser bar.
    fn on_spectrum(&self, bars: Vec<i32>);
}

/// Battery updates (20..=100 %), event-free polling in Rust.
#[uniffi::export(callback_interface)]
pub trait BatteryListener: Send + Sync {
    /// Called only when the sampled battery state differs from the previous one.
    fn on_battery(&self, state: BatteryState);
}

/// Hyprland focus events: active window and submap.
#[uniffi::export(callback_interface)]
pub trait HyprlandListener: Send + Sync {
    /// Called when the focused window changes (title/class of the new window).
    fn on_active_window(&self, window: ActiveWindow);
    /// Called when the compositor enters a different submap (empty name = default).
    fn on_submap(&self, name: String);
}

/// Network throughput updates (once per second).
#[uniffi::export(callback_interface)]
pub trait NetworkListener: Send + Sync {
    /// Called once per second with rates plus interface/wifi details.
    fn on_network(&self, state: NetworkState);
}

/// Desktop notifications observed on the session bus.
#[uniffi::export(callback_interface)]
pub trait NotificationListener: Send + Sync {
    /// Called for every notification the server accepts (suppressed by DND) or observes.
    fn on_notification(&self, notification: NotificationInfo);
    /// Called when a notification disappears; `reason` follows the freedesktop spec
    /// (1 expired, 2 dismissed by the user, 3 closed by a call, 4 undefined).
    fn on_notification_closed(&self, id: u64, reason: u32);
}

// ---------------------------------------------------------------------------
// Listener registries
// ---------------------------------------------------------------------------

/// Global registry of [`WindowListener`]s.
pub(crate) fn window_listeners() -> &'static Mutex<Vec<Arc<dyn WindowListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn WindowListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

/// Global registry of [`MediaListener`]s.
pub(crate) fn media_listeners() -> &'static Mutex<Vec<Arc<dyn MediaListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn MediaListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

/// Global registry of [`VolumeListener`]s.
pub(crate) fn volume_listeners() -> &'static Mutex<Vec<Arc<dyn VolumeListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn VolumeListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

/// Global registry of [`WorkspaceListener`]s.
pub(crate) fn workspace_listeners() -> &'static Mutex<Vec<Arc<dyn WorkspaceListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn WorkspaceListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

/// Global registry of [`SystemListener`]s.
pub(crate) fn system_listeners() -> &'static Mutex<Vec<Arc<dyn SystemListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn SystemListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

/// Global registry of [`SpectrumListener`]s.
pub(crate) fn spectrum_listeners() -> &'static Mutex<Vec<Arc<dyn SpectrumListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn SpectrumListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

/// Global registry of [`BatteryListener`]s.
pub(crate) fn battery_listeners() -> &'static Mutex<Vec<Arc<dyn BatteryListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn BatteryListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

/// Global registry of [`HyprlandListener`]s.
pub(crate) fn hyprland_listeners() -> &'static Mutex<Vec<Arc<dyn HyprlandListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn HyprlandListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

/// Global registry of [`NetworkListener`]s.
pub(crate) fn network_listeners() -> &'static Mutex<Vec<Arc<dyn NetworkListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn NetworkListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

/// Global registry of [`NotificationListener`]s.
pub(crate) fn notification_listeners() -> &'static Mutex<Vec<Arc<dyn NotificationListener>>> {
    static REGISTRY: OnceLock<Mutex<Vec<Arc<dyn NotificationListener>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(Vec::new()))
}

// ---------------------------------------------------------------------------
// Broadcast helpers
// ---------------------------------------------------------------------------

/// Delivers a window-list snapshot to every registered listener.
pub(crate) fn broadcast_windows(windows: Vec<WindowInfo>) {
    let listeners: Vec<Arc<dyn WindowListener>> = window_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_windows(windows.clone());
    }
}

/// Delivers a media state to every registered listener.
pub(crate) fn broadcast_media(state: MediaState) {
    let listeners: Vec<Arc<dyn MediaListener>> = media_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_media(state.clone());
    }
}

/// Delivers the default-sink volume to every registered listener.
pub(crate) fn broadcast_volume(volume: i32) {
    let listeners: Vec<Arc<dyn VolumeListener>> = volume_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_volume(volume);
    }
}

/// Delivers a workspace-list snapshot to every registered listener.
pub(crate) fn broadcast_workspaces(workspaces: Vec<WorkspaceInfo>) {
    let listeners: Vec<Arc<dyn WorkspaceListener>> = workspace_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_workspaces(workspaces.clone());
    }
}

/// Delivers CPU / memory stats to every registered listener.
pub(crate) fn broadcast_system_stats(stats: SystemStats) {
    let listeners: Vec<Arc<dyn SystemListener>> = system_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_stats(stats.clone());
    }
}

/// Delivers one spectrum frame to every registered listener.
pub(crate) fn broadcast_spectrum(bars: Vec<i32>) {
    let listeners: Vec<Arc<dyn SpectrumListener>> = spectrum_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_spectrum(bars.clone());
    }
}

/// Delivers a battery state to every registered listener.
pub(crate) fn broadcast_battery(state: BatteryState) {
    let listeners: Vec<Arc<dyn BatteryListener>> = battery_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_battery(state.clone());
    }
}

/// Delivers the newly focused window to every registered listener.
pub(crate) fn broadcast_active_window(window: ActiveWindow) {
    let listeners: Vec<Arc<dyn HyprlandListener>> = hyprland_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_active_window(window.clone());
    }
}

/// Delivers a submap change to every registered listener.
pub(crate) fn broadcast_submap(name: String) {
    let listeners: Vec<Arc<dyn HyprlandListener>> = hyprland_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_submap(name.clone());
    }
}

/// Delivers a network-state sample to every registered listener.
pub(crate) fn broadcast_network(state: NetworkState) {
    let listeners: Vec<Arc<dyn NetworkListener>> = network_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_network(state.clone());
    }
}

/// Delivers a new notification to every registered listener.
pub(crate) fn broadcast_notification(notification: NotificationInfo) {
    let listeners: Vec<Arc<dyn NotificationListener>> =
        notification_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_notification(notification.clone());
    }
}

/// Delivers a notification-closed event to every registered listener.
pub(crate) fn broadcast_notification_closed(id: u64, reason: u32) {
    let listeners: Vec<Arc<dyn NotificationListener>> =
        notification_listeners().lock().unwrap().clone();
    for listener in listeners {
        listener.on_notification_closed(id, reason);
    }
}

// ---------------------------------------------------------------------------
// Crate-internal names used by the service modules
// ---------------------------------------------------------------------------

pub(crate) use broadcast_active_window as publish_active_window;
pub(crate) use broadcast_battery as publish_battery;
pub(crate) use broadcast_media as publish_media;
pub(crate) use broadcast_network as publish_network;
pub(crate) use broadcast_notification as publish_notification;
pub(crate) use broadcast_notification_closed as publish_notification_closed;
pub(crate) use broadcast_spectrum as publish_spectrum;
pub(crate) use broadcast_submap as publish_submap;
pub(crate) use broadcast_system_stats as publish_system_stats;
pub(crate) use broadcast_volume as publish_volume;
pub(crate) use broadcast_windows as publish_windows;
pub(crate) use broadcast_workspaces as publish_workspaces;
