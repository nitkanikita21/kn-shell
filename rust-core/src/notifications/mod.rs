//! Desktop notifications: the shell optionally **is** the notification server.
//!
//! Mode 1 (preferred): take over `org.freedesktop.Notifications` with a full
//! `dbus-crossroads` server and serve the freedesktop spec:
//!   methods   Notify, CloseNotification, GetCapabilities, GetServerInformation
//!             (+ non-standard KnInvokeAction used by the bar UI)
//!   signals   NotificationClosed, ActionInvoked
//! If another daemon owns the name and refuses replacement, mode 2 runs a passive
//! `dbus-monitor` watcher that only observes `Notify` calls (history without control).
//!
//! Expiry timeouts are not implemented yet: notifications stay until closed/dismissed.
//!
//! Layout: `dbus` owns the server and the control API, `store` the accepted
//! notification state, `watch` the passive fallback. This module holds the shared
//! switches (server-active, do-not-disturb) and the thread entry point.

mod dbus;
mod store;
mod watch;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;

/// Guard that makes the notification thread spawn at most once.
static STARTED: OnceLock<()> = OnceLock::new();

/// Whether this process currently owns `org.freedesktop.Notifications`.
pub(crate) static SERVER_ACTIVE: AtomicBool = AtomicBool::new(false);

/// Do-not-disturb switch, shared by the server and the control API.
pub(crate) static DND: AtomicBool = AtomicBool::new(false);

/// Starts the notification thread on the first call; later calls are no-ops.
pub(crate) fn ensure_started() {
    STARTED.get_or_init(|| {
        std::thread::Builder::new()
            .name("kn-notification-listener".into())
            .spawn(run)
            .expect("failed to spawn notification listener thread");
    });
}

/// Thread entry point: become the server when the bus name is free, otherwise observe.
fn run() {
    if dbus::run_server() {
        return;
    }
    eprintln!("[rust/notifications] bus name busy — observing notifications only");
    watch::watch_loop();
}

/// True when the shell owns the notification bus name (control API is available).
pub(crate) fn is_server() -> bool {
    SERVER_ACTIVE.load(Ordering::Relaxed)
}
