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
//!
//! # Layout
//!
//! * records shared with Kotlin — `types`,
//! * callback interfaces plus their registries and `publish_*` helpers — `listeners`,
//! * the exported `subscribe_*` / action functions — `api`,
//! * one module per service (`windows`, `media`, `audio`, …), each owning its thread.
//!
//! Everything that used to live at the crate root is re-exported below, so
//! `kn_shell_core::WindowInfo` and friends keep resolving.

mod api;
mod audio;
mod battery;
mod box_layout;
// Public extension host: external crates (e.g. palette-core's `custom_ext`
// bridge) implement `CustomExtension` and call `register_extension`.
pub mod custom;
mod hyprland;
mod listeners;
mod media;
mod network;
mod notifications;
mod spectrum;
mod system;
mod types;
mod windows;
mod workspaces;

uniffi::setup_scaffolding!();

pub use api::*;
pub use listeners::{
    BatteryListener, HyprlandListener, MediaListener, NetworkListener, NotificationListener,
    SpectrumListener, SystemListener, VolumeListener, WindowListener, WorkspaceListener,
};
pub use types::*;

// Service modules publish through these aliases; they stay crate-private.
pub(crate) use listeners::{
    publish_active_window, publish_battery, publish_media, publish_network, publish_notification,
    publish_notification_closed, publish_spectrum, publish_submap, publish_system_stats,
    publish_volume, publish_windows, publish_workspaces,
};
