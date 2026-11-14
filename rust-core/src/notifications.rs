//! Desktop notifications, observed from the session bus.
//!
//! The shell does not own `org.freedesktop.Notifications` (the user's daemon — mako,
//! dunst, swaync — keeps that name). Instead `dbus-monitor` is spawned with a match rule
//! for the `Notify` method call: every notification any client sends is parsed from the
//! monitor output (app name, summary, body, urgency hint) and published to Kotlin.
//!
//! This keeps the favicon without a DBus binding dependency and works with every daemon
//! that implements the standard interface. Notification history is kept in Kotlin.

use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::OnceLock;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::{publish_notification, NotificationInfo};

static STARTED: OnceLock<()> = OnceLock::new();

const MATCH: &str = "type='method_call',interface='org.freedesktop.Notifications',member='Notify'";

pub fn ensure_started() {
    STARTED.get_or_init(|| {
        std::thread::Builder::new()
            .name("kn-notification-listener".into())
            .spawn(run)
            .expect("failed to spawn notification listener thread");
    });
}

fn run() {
    loop {
        match spawn_monitor() {
            Some(mut child) => {
                eprintln!("[rust/notifications] dbus-monitor started");
                read_events(&mut child);
                let _ = child.kill();
                let _ = child.wait();
                eprintln!("[rust/notifications] dbus-monitor exited; restarting in 2 s");
            }
            None => eprintln!("[rust/notifications] dbus-monitor not available; retrying"),
        }
        std::thread::sleep(Duration::from_secs(2));
    }
}

fn spawn_monitor() -> Option<Child> {
    Command::new("dbus-monitor")
        .args(["--session", MATCH])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// State of one `Notify` argument block.
#[derive(Default)]
struct Capture {
    active: bool,
    app: String,
    replaces_id: u64,
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

fn finish(capture: &mut Capture) {
    if capture.active && capture.strings.len() >= 4 {
        let mut strings = capture.strings.iter();
        let app = strings.next().cloned().unwrap_or_default();
        let _icon = strings.next();
        let summary = strings.next().cloned().unwrap_or_default();
        let body = strings.next().cloned().unwrap_or_default();
        eprintln!(
            "[rust/notifications] app=\"{}\" summary=\"{}\" urgency={}",
            app, summary, capture.urgency
        );
        publish_notification(NotificationInfo {
            id: capture.replaces_id,
            app,
            summary,
            body,
            urgency: capture.urgency,
            timestamp_ms: now_ms(),
        });
    }
    *capture = Capture::default();
}

fn read_events(child: &mut Child) {
    let Some(stdout) = child.stdout.take() else { return };
    let mut capture = Capture::default();

    for line in BufReader::new(stdout).lines() {
        let Ok(line) = line else { return };

        if line.starts_with("method call") || line.starts_with("signal") || line.starts_with("method return") {
            finish(&mut capture);
            capture.active = line.contains("member=Notify");
            continue;
        }
        if !capture.active {
            continue;
        }

        let trimmed = line.trim_start();
        if trimmed.is_empty() {
            continue;
        }

        if trimmed.starts_with("array [") {
            // arrays start after the four plain strings; hints come in the second array
            continue;
        }

        if trimmed.starts_with("dict entry(") {
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

        if trimmed.starts_with("uint32 ") && capture.strings.is_empty() {
            capture.replaces_id = trimmed["uint32 ".len()..].trim().parse().unwrap_or(0);
            continue;
        }

        if capture.expecting_urgency_value && trimmed.contains("byte ") {
            let value = trimmed.rsplit("byte ").next().unwrap_or("1").trim();
            capture.urgency = value.parse().unwrap_or(1);
            capture.expecting_urgency_value = false;
        }
    }
    finish(&mut capture);
}
