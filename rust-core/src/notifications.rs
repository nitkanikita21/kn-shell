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

use std::collections::HashMap;
use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use dbus::arg::PropMap;
use dbus::blocking::stdintf::org_freedesktop_dbus::RequestNameReply;
use dbus::blocking::Connection;
use dbus::Message;
use dbus_crossroads::{Context, Crossroads};

use crate::{publish_notification, publish_notification_closed, NotificationInfo};

static STARTED: OnceLock<()> = OnceLock::new();
static SERVER_ACTIVE: AtomicBool = AtomicBool::new(false);

const BUS_NAME: &str = "org.freedesktop.Notifications";

/// Bus name to serve; `KN_NOTIF_BUS_NAME` overrides it for tests with a scratch name.
fn bus_name() -> String {
    std::env::var("KN_NOTIF_BUS_NAME").unwrap_or_else(|_| BUS_NAME.to_string())
}
const OBJECT_PATH: &str = "/org/freedesktop/Notifications";
const INTERFACE: &str = "org.freedesktop.Notifications";
const WATCH_MATCH: &str =
    "type='method_call',interface='org.freedesktop.Notifications',member='Notify'";

/// True when the shell owns the notification bus name (control API is available).
pub fn is_server() -> bool {
    SERVER_ACTIVE.load(Ordering::Relaxed)
}

pub fn ensure_started() {
    STARTED.get_or_init(|| {
        std::thread::Builder::new()
            .name("kn-notification-listener".into())
            .spawn(run)
            .expect("failed to spawn notification listener thread");
    });
}

fn run() {
    if run_server() {
        return;
    }
    eprintln!("[rust/notifications] bus name busy — observing notifications only");
    watch_loop();
}

#[derive(Default)]
struct ServerState {
    next_id: u32,
    items: HashMap<u32, Item>,
}

struct Item {
    app: String,
    summary: String,
    body: String,
    urgency: u8,
    actions: Vec<String>,
    timestamp_ms: u64,
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn run_server() -> bool {
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
             (app_name, replaces_id, _icon, summary, body, actions, hints, _expire): (
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
                    Item {
                        app: app_name.clone(),
                        summary: summary.clone(),
                        body: body.clone(),
                        urgency,
                        actions: actions.clone(),
                        timestamp_ms: now_ms(),
                    },
                );
                drop(guard);

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
                });
                let _ = ctx;
                Ok((id,))
            },
        );

        builder.method(
            "CloseNotification",
            ("id",),
            (),
            |_ctx, state: &mut Arc<Mutex<ServerState>>, (id,): (u32,)| -> Result<(), dbus_crossroads::MethodErr> {
                close_notification(state, id, 2);
                Ok(())
            },
        );

        // Non-standard helper so the bar UI can invoke an action without a DBus client.
        builder.method(
            "KnInvokeAction",
            ("id", "action_key"),
            (),
            |_ctx,
             state: &mut Arc<Mutex<ServerState>>,
             (id, action_key): (u32, String)|
             -> Result<(), dbus_crossroads::MethodErr> {
                close_notification(state, id, 2);
                Ok(())
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

fn close_notification(state: &mut Arc<Mutex<ServerState>>, id: u32, reason: u32) {
    let mut guard = state.lock().unwrap();
    let Some(item) = guard.items.remove(&id) else {
        return;
    };
    drop(guard);
    eprintln!(
        "[rust/notifications] closed id={id} reason={reason} app=\"{}\"",
        item.app
    );
    publish_notification_closed(id as u64, reason);
}

// ---------------------------------------------------------------------------
// Control API (called from Kotlin through UniFFI)
// ---------------------------------------------------------------------------

fn proxy_call<A: dbus::arg::AppendAll>(method: &str, args: A) -> bool {
    let Ok(connection) = Connection::new_session() else {
        return false;
    };
    let proxy = connection.with_proxy(bus_name(), OBJECT_PATH, Duration::from_millis(1000));
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

// ---------------------------------------------------------------------------
// Fallback: passive watcher (no bus name -> no control)
// ---------------------------------------------------------------------------

fn watch_loop() {
    loop {
        match spawn_monitor() {
            Some(mut child) => {
                read_events(&mut child);
                let _ = child.kill();
                let _ = child.wait();
            }
            None => {}
        }
        std::thread::sleep(Duration::from_secs(2));
    }
}

fn spawn_monitor() -> Option<Child> {
    Command::new("dbus-monitor")
        .args(["--session", WATCH_MATCH])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()
}

#[derive(Default)]
struct Capture {
    active: bool,
    strings: Vec<String>,
    urgency: u8,
    expecting_urgency_value: bool,
}

fn unescape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut chars = value.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('n') => out.push('\n'),
            Some('t') => out.push('\t'),
            Some(other) => out.push(other),
            None => break,
        }
    }
    out
}

fn quoted(line: &str) -> Option<String> {
    let start = line.find("string \"")? + "string \"".len();
    let rest = &line[start..];
    let mut value = String::new();
    let mut chars = rest.chars();
    while let Some(c) = chars.next() {
        match c {
            '"' => return Some(unescape(&value)),
            '\\' => {
                if let Some(next) = chars.next() {
                    value.push('\\');
                    value.push(next);
                }
            }
            other => value.push(other),
        }
    }
    None
}

fn read_events(child: &mut Child) {
    let Some(stdout) = child.stdout.take() else { return };
    let mut capture = Capture::default();
    static FALLBACK_ID: AtomicU32 = AtomicU32::new(0);

    for line in BufReader::new(stdout).lines() {
        let Ok(line) = line else { return };
        if line.starts_with("method call") || line.starts_with("signal") || line.starts_with("method return") {
            capture = Capture::default();
            capture.active = line.contains("member=Notify");
            continue;
        }
        if !capture.active {
            continue;
        }
        let trimmed = line.trim_start();
        if trimmed.is_empty() || trimmed.starts_with("array [") || trimmed.starts_with("dict entry(") {
            continue;
        }
        if let Some(value) = quoted(trimmed) {
            if value == "urgency" {
                capture.expecting_urgency_value = true;
                continue;
            }
            if capture.strings.len() < 4 {
                capture.strings.push(value);
            }
            continue;
        }
        if capture.expecting_urgency_value && trimmed.contains("byte ") {
            capture.urgency = trimmed.rsplit("byte ").next().unwrap_or("1").trim().parse().unwrap_or(1);
            capture.expecting_urgency_value = false;
            continue;
        }
        if trimmed.starts_with("int32 ") && capture.strings.len() >= 4 {
            let mut strings = capture.strings.iter();
            let app = strings.next().cloned().unwrap_or_default();
            let _icon = strings.next();
            let summary = strings.next().cloned().unwrap_or_default();
            let body = strings.next().cloned().unwrap_or_default();
            publish_notification(NotificationInfo {
                id: FALLBACK_ID.fetch_add(1, Ordering::Relaxed) as u64 + 1,
                app,
                summary,
                body,
                urgency: capture.urgency,
                timestamp_ms: now_ms(),
            });
            capture = Capture::default();
        }
    }
}
