//! Kotlin-callable actions: the `#[uniffi::export]` functions of the built-in services.
//!
//! Every `subscribe_*` function boxes the Kotlin callback interface into the matching
//! registry (see the listener module) and starts the backing service thread lazily, so
//! subscriptions are idempotent and safe to issue from any thread. The remaining
//! functions are one-shot actions — media key, workspace switch, raw Hyprland dispatch,
//! detached process spawn and default-sink volume — and never touch listener state.
//!
//! The notification control API lives with the notification service and is exported from
//! there; it is part of the same Kotlin surface.

use std::sync::Arc;

use crate::listeners::{
    battery_listeners, hyprland_listeners, media_listeners, network_listeners,
    notification_listeners, spectrum_listeners, system_listeners, volume_listeners,
    window_listeners, workspace_listeners, BatteryListener, HyprlandListener, MediaListener,
    NetworkListener, NotificationListener, SpectrumListener, SystemListener, VolumeListener,
    WindowListener, WorkspaceListener,
};
use crate::{audio, battery, hyprland, media, network, notifications, spectrum, system, windows, workspaces};

// ---------------------------------------------------------------------------
// Subscriptions
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

/// Subscribes to MPRIS media state.
///
/// The first call spawns the `playerctl --follow` watcher thread.
#[uniffi::export]
pub fn subscribe_media(listener: Box<dyn MediaListener>) {
    media_listeners().lock().unwrap().push(Arc::from(listener));
    media::ensure_started();
}

/// Subscribes to default-sink volume changes.
///
/// The first call spawns the `pactl` polling thread (one sample every 500 ms).
#[uniffi::export]
pub fn subscribe_volume(listener: Box<dyn VolumeListener>) {
    volume_listeners().lock().unwrap().push(Arc::from(listener));
    audio::ensure_started();
}

/// Subscribes to the Hyprland workspace list.
///
/// The first call starts the workspace watcher, which prefers the compositor event
/// socket and falls back to polling `hyprctl`.
#[uniffi::export]
pub fn subscribe_workspaces(listener: Box<dyn WorkspaceListener>) {
    workspace_listeners().lock().unwrap().push(Arc::from(listener));
    workspaces::ensure_started();
}

/// Subscribes to CPU / memory statistics.
///
/// The first call spawns the sampler thread, which publishes once per second.
#[uniffi::export]
pub fn subscribe_system(listener: Box<dyn SystemListener>) {
    system_listeners().lock().unwrap().push(Arc::from(listener));
    system::ensure_started();
}

/// Subscribes to audio spectrum frames.
///
/// The first call spawns CAVA (respawning it if it dies) and forwards one frame per
/// CAVA update.
#[uniffi::export]
pub fn subscribe_spectrum(listener: Box<dyn SpectrumListener>) {
    spectrum_listeners().lock().unwrap().push(Arc::from(listener));
    spectrum::ensure_started();
}

/// Subscribes to battery state changes.
///
/// The first call spawns the `/sys/class/power_supply` polling thread (every 2 s);
/// values are published only when they change.
#[uniffi::export]
pub fn subscribe_battery(listener: Box<dyn BatteryListener>) {
    battery_listeners().lock().unwrap().push(Arc::from(listener));
    battery::ensure_started();
}

/// Subscribes to Hyprland focus events (active window and submap).
///
/// The first call starts the event-socket listener, with a polling fallback when the
/// socket is unavailable.
#[uniffi::export]
pub fn subscribe_hyprland(listener: Box<dyn HyprlandListener>) {
    hyprland_listeners().lock().unwrap().push(Arc::from(listener));
    hyprland::ensure_started();
}

/// Subscribes to network throughput samples.
///
/// The first call spawns the `/proc/net/dev` sampler thread (once per second).
#[uniffi::export]
pub fn subscribe_network(listener: Box<dyn NetworkListener>) {
    network_listeners().lock().unwrap().push(Arc::from(listener));
    network::ensure_started();
}

/// Subscribes to desktop notifications.
///
/// The first call starts the notification service: it either becomes the session
/// notification server or, if another daemon owns the bus name, observes notifications
/// passively.
#[uniffi::export]
pub fn subscribe_notifications(listener: Box<dyn NotificationListener>) {
    notification_listeners().lock().unwrap().push(Arc::from(listener));
    notifications::ensure_started();
}

// ---------------------------------------------------------------------------
// One-shot actions
// ---------------------------------------------------------------------------

/// Sends `playerctl play-pause` to the active MPRIS player.
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
