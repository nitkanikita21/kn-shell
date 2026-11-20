//! Plain-data records exchanged between Rust and Kotlin through UniFFI.
//!
//! Every type here derives `uniffi::Record`, so the Kotlin side gets an equivalent data
//! class in `dev.knshell.core`. Records are value types only — the behaviour around them
//! lives in the listener traits and in the export functions, which the crate root
//! re-exports next to these types.

/// Information about a single toplevel window exposed by the compositor.
#[derive(Debug, Clone, uniffi::Record)]
pub struct WindowInfo {
    /// Window title as reported by the compositor (may be empty).
    pub title: String,
    /// Application id / Wayland `app_id` (e.g. `kitty`), may be empty.
    pub app_id: String,
}

/// One action attached to a notification (`key` is sent back on invoke, `label` is shown).
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NotificationAction {
    /// Identifier reported back to `notifications_invoke_action`.
    pub key: String,
    /// Human-readable button text.
    pub label: String,
}

/// One desktop notification observed on the session bus.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NotificationInfo {
    /// Server-assigned notification id, used to close or invoke actions on it.
    pub id: u64,
    /// Application name passed to `Notify`.
    pub app: String,
    /// Short one-line summary shown as the notification title.
    pub summary: String,
    /// Body text (may be empty and may contain newlines).
    pub body: String,
    /// `1` normal, `2` critical (freedesktop hints).
    pub urgency: u8,
    /// Unix epoch milliseconds at which the notification was accepted.
    pub timestamp_ms: u64,
    /// `image-path` hint or the app icon (file path or icon name, may be empty).
    pub icon: String,
    /// Action buttons in display order.
    pub actions: Vec<NotificationAction>,
}

/// Current network throughput across all non-loopback interfaces.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct NetworkState {
    /// Receive rate measured over the last poll interval.
    pub rx_bytes_per_sec: u64,
    /// Transmit rate measured over the last poll interval.
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
    /// Whether a battery device exists at all (desktops report `false`).
    pub present: bool,
    /// Charge level in percent (`0..=100`); stays `0` when no battery reports it.
    pub percent: i32,
    /// `true` while the battery is charging or full.
    pub charging: bool,
    /// Raw kernel `status` string (e.g. `Discharging`, `Charging`, `Full`).
    pub status: String,
}

/// Currently focused Hyprland window.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ActiveWindow {
    /// Window class as reported by Hyprland (e.g. `kitty`).
    pub class: String,
    /// Window title, empty when no window is focused.
    pub title: String,
}

/// Current state of the active MPRIS media player.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct MediaState {
    /// Track title.
    pub title: String,
    /// Track artist(s).
    pub artist: String,
    /// `true` when the player is in the `Playing` state.
    pub playing: bool,
    /// MPRIS player name (e.g. `spotify`).
    pub player: String,
    /// `false` when no player is running; the other fields are then empty.
    pub available: bool,
}

/// A Hyprland workspace as reported by the compositor IPC.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct WorkspaceInfo {
    /// Numeric workspace id (may be negative for special workspaces).
    pub id: i32,
    /// Workspace name as shown in the bar (e.g. `1`, `special:magic`).
    pub name: String,
    /// `true` for the currently focused workspace.
    pub active: bool,
}

/// CPU / memory usage of the machine.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct SystemStats {
    /// Total CPU utilisation over the last poll interval, in percent.
    pub cpu_percent: i32,
    /// Used RAM as a percentage of total RAM.
    pub mem_percent: i32,
}
