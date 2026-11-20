//! D-Bus side of the notification service: the `dbus-crossroads` server that owns
//! `org.freedesktop.Notifications`, plus the control calls the bar UI makes over the
//! same bus (`CloseNotification`, `KnInvokeAction`, DND toggles).

use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};

use dbus::arg::PropMap;
use dbus::blocking::stdintf::org_freedesktop_dbus::RequestNameReply;
use dbus::blocking::Connection;
use dbus::Message;
use dbus_crossroads::Crossroads;

use super::store::{close_notification, now_ms, pairs, ServerState};
use super::{is_server, DND, SERVER_ACTIVE};
use crate::{publish_notification, NotificationInfo};

const BUS_NAME: &str = "org.freedesktop.Notifications";

const OBJECT_PATH: &str = "/org/freedesktop/Notifications";

const INTERFACE: &str = "org.freedesktop.Notifications";

/// Expiry used when a client passes `-1` ("server default") as `expire_timeout`.
const DEFAULT_EXPIRE_MS: i32 = 5000;

/// Bus name to serve; `KN_NOTIF_BUS_NAME` overrides it for tests with a scratch name.
fn bus_name() -> String {
    std::env::var("KN_NOTIF_BUS_NAME").unwrap_or_else(|_| BUS_NAME.to_string())
}

/// Builds the `NotificationClosed` signal for the given notification id and reason.
fn closed_signal(id: u32, reason: u32) -> Message {
    Message::new_signal(OBJECT_PATH, INTERFACE, "NotificationClosed")
        .expect("signal")
        .append2(id, reason)
}

/// Builds the `ActionInvoked` signal for one action key.
fn action_signal(id: u32, action: &str) -> Message {
    Message::new_signal(OBJECT_PATH, INTERFACE, "ActionInvoked")
        .expect("signal")
        .append2(id, action.to_string())
}

/// First string value found under any of `keys` in the hints map, or an empty string.
fn hint_string(hints: &PropMap, keys: &[&str]) -> String {
    for key in keys {
        if let Some(value) = hints.get(*key) {
            if let Some(text) = value.0.as_str() {
                return text.to_string();
            }
        }
    }
    String::new()
}

/// Tries to become the session notification server and serve it until the connection
/// drops. Returns `true` when the server ran (or was stopped by the bus), `false` when
/// the bus name could not be acquired — the caller then falls back to the watcher.
pub(crate) fn run_server() -> bool {
    let Ok(connection) = Connection::new_session() else {
        return false;
    };
    let name = bus_name();
    match connection.request_name(name.as_str(), true, true, false) {
        Ok(RequestNameReply::PrimaryOwner) | Ok(RequestNameReply::AlreadyOwner) => {}
        Ok(reply) => {
            eprintln!("[rust/notifications] {name} is taken ({reply:?})");
            return false;
        }
        Err(error) => {
            eprintln!("[rust/notifications] request_name failed: {error}");
            return false;
        }
    }

    SERVER_ACTIVE.store(true, Ordering::Relaxed);
    eprintln!("[rust/notifications] serving {name} (control enabled)");

    let state = Arc::new(Mutex::new(ServerState::default()));
    let mut crossroads = Crossroads::new();

    let iface = crossroads.register(INTERFACE, |builder| {
        builder.method(
            "GetCapabilities",
            (),
            ("capabilities",),
            |_ctx, _state, ()| -> Result<(Vec<String>,), dbus_crossroads::MethodErr> {
                Ok((vec!["body".into(), "actions".into(), "persistence".into()],))
            },
        );

        builder.method(
            "GetServerInformation",
            (),
            ("name", "vendor", "version", "spec_version"),
            |_ctx, _state, ()| -> Result<(String, String, String, String), dbus_crossroads::MethodErr> {
                Ok((
                    "kn-shell".into(),
                    "KN Shell".into(),
                    "0.1".into(),
                    "1.2".into(),
                ))
            },
        );

        builder.method(
            "Notify",
            (
                "app_name",
                "replaces_id",
                "app_icon",
                "summary",
                "body",
                "actions",
                "hints",
                "expire_timeout",
            ),
            ("id",),
            |ctx,
             state: &mut Arc<Mutex<ServerState>>,
             (app_name, replaces_id, app_icon, summary, body, actions, hints, expire_timeout): (
                String,
                u32,
                String,
                String,
                String,
                Vec<String>,
                PropMap,
                i32,
            )|
             -> Result<(u32,), dbus_crossroads::MethodErr> {
                let image = hint_string(&hints, &["image-path", "image_path"]);
                let icon = if image.is_empty() { app_icon.clone() } else { image };
                let action_items = pairs(actions.clone());
                let urgency = hints
                    .get("urgency")
                    .and_then(|variant| variant.0.as_i64())
                    .map(|value| value.clamp(1, 2) as u8)
                    .unwrap_or(1);

                let mut guard = state.lock().unwrap();
                let id = if replaces_id != 0 && guard.items.contains_key(&replaces_id) {
                    replaces_id
                } else {
                    guard.next_id = guard.next_id.wrapping_add(1).max(1);
                    guard.next_id
                };
                guard.items.insert(
                    id,
                    super::store::Item {
                        app: app_name.clone(),
                        summary: summary.clone(),
                        body: body.clone(),
                        urgency,
                        actions: actions.clone(),
                        timestamp_ms: now_ms(),
                    },
                );
                drop(guard);

                // Do-not-disturb: accept the notification (clients get a valid id) but do not
                // surface it — close it immediately from the server side.
                if DND.load(Ordering::Relaxed) {
                    eprintln!("[rust/notifications] suppressed by DND id={id}");
                    close_notification(state, id, 3);
                    ctx.push_msg(closed_signal(id, 3));
                    return Ok((id,));
                }

                eprintln!(
                    "[rust/notifications] Notify id={id} app=\"{app_name}\" summary=\"{summary}\""
                );
                publish_notification(NotificationInfo {
                    id: id as u64,
                    app: app_name,
                    summary,
                    body,
                    urgency,
                    timestamp_ms: now_ms(),
                    icon,
                    actions: action_items,
                });

                // Expiry: 0 means "never", -1 means the server default.
                if expire_timeout != 0 {
                    let timeout = if expire_timeout < 0 {
                        DEFAULT_EXPIRE_MS
                    } else {
                        expire_timeout
                    };
                    std::thread::spawn(move || {
                        std::thread::sleep(std::time::Duration::from_millis(timeout as u64));
                        proxy_call("KnExpireNotification", (id,));
                    });
                }
                let _ = ctx;
                Ok((id,))
            },
        );

        builder.method(
            "CloseNotification",
            ("id",),
            (),
            |ctx, state: &mut Arc<Mutex<ServerState>>, (id,): (u32,)| -> Result<(), dbus_crossroads::MethodErr> {
                if close_notification(state, id, 2) {
                    ctx.push_msg(closed_signal(id, 2));
                }
                Ok(())
            },
        );

        // Non-standard helper so the bar UI can invoke an action without a DBus client.
        builder.method(
            "KnInvokeAction",
            ("id", "action_key"),
            (),
            |ctx,
             state: &mut Arc<Mutex<ServerState>>,
             (id, action_key): (u32, String)|
             -> Result<(), dbus_crossroads::MethodErr> {
                ctx.push_msg(action_signal(id, &action_key));
                if close_notification(state, id, 2) {
                    ctx.push_msg(closed_signal(id, 2));
                }
                Ok(())
            },
        );

        builder.method(
            "KnExpireNotification",
            ("id",),
            (),
            |ctx, state: &mut Arc<Mutex<ServerState>>, (id,): (u32,)| -> Result<(), dbus_crossroads::MethodErr> {
                if close_notification(state, id, 1) {
                    ctx.push_msg(closed_signal(id, 1));
                }
                Ok(())
            },
        );

        builder.method(
            "KnSetDnd",
            ("enabled",),
            (),
            |_ctx, _state: &mut Arc<Mutex<ServerState>>, (enabled,): (bool,)| -> Result<(), dbus_crossroads::MethodErr> {
                DND.store(enabled, Ordering::Relaxed);
                eprintln!("[rust/notifications] DND = {enabled}");
                Ok(())
            },
        );

        builder.method(
            "KnGetDnd",
            (),
            ("enabled",),
            |_ctx, _state: &mut Arc<Mutex<ServerState>>, ()| -> Result<(bool,), dbus_crossroads::MethodErr> {
                Ok((DND.load(Ordering::Relaxed),))
            },
        );

        builder.signal::<(u32, u32), _>("NotificationClosed", ("id", "reason"));
        builder.signal::<(u32, String), _>("ActionInvoked", ("id", "action_key"));
    });

    crossroads.insert(OBJECT_PATH, &[iface], state);
    match crossroads.serve(&connection) {
        Ok(()) => false,
        Err(error) => {
            eprintln!("[rust/notifications] server stopped: {error}");
            SERVER_ACTIVE.store(false, Ordering::Relaxed);
            false
        }
    }
}

// ---------------------------------------------------------------------------
// Control API (called from Kotlin through UniFFI)
// ---------------------------------------------------------------------------

/// Calls one method on our own server over the session bus.
///
/// Returns `false` when the session bus is unavailable or the call fails.
fn proxy_call<A: dbus::arg::AppendAll>(method: &str, args: A) -> bool {
    let Ok(connection) = Connection::new_session() else {
        return false;
    };
    let proxy = connection.with_proxy(bus_name(), OBJECT_PATH, std::time::Duration::from_millis(1000));
    proxy
        .method_call::<(), A, _, _>(INTERFACE, method, args)
        .is_ok()
}

/// Closes a notification owned by our server (no-op in observer mode).
#[uniffi::export]
pub fn notifications_close(id: u64) -> bool {
    if !is_server() {
        return false;
    }
    proxy_call("CloseNotification", (id as u32,))
}

/// Emits ActionInvoked for [action] and closes the notification.
#[uniffi::export]
pub fn notifications_invoke_action(id: u64, action: String) -> bool {
    if !is_server() {
        return false;
    }
    proxy_call("KnInvokeAction", (id as u32, action))
}

/// Whether the shell currently owns the notification bus name.
#[uniffi::export]
pub fn notifications_is_server() -> bool {
    is_server()
}

/// Enables/disables do-not-disturb: while enabled, `Notify` calls are accepted but
/// immediately closed with reason 3 and never surfaced.
#[uniffi::export]
pub fn notifications_set_dnd(enabled: bool) {
    DND.store(enabled, Ordering::Relaxed);
    if is_server() {
        proxy_call("KnSetDnd", (enabled,));
    }
}

/// Current do-not-disturb state.
#[uniffi::export]
pub fn notifications_is_dnd() -> bool {
    DND.load(Ordering::Relaxed)
}
