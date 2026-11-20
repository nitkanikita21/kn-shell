//! Passive fallback used when another daemon owns `org.freedesktop.Notifications`:
//! `dbus-monitor` is spawned to observe `Notify` calls, giving the bar a history with
//! no ability to close or invoke actions.

use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::Duration;

use super::store::{now_ms, pairs};
use crate::{publish_notification, NotificationInfo};

const WATCH_MATCH: &str =
    "type='method_call',interface='org.freedesktop.Notifications',member='Notify'";

/// Restarts `dbus-monitor` forever, with a short pause between attempts.
pub(crate) fn watch_loop() {
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

/// Starts `dbus-monitor` for the `Notify` match rule, capturing stdout.
fn spawn_monitor() -> Option<Child> {
    Command::new("dbus-monitor")
        .args(["--session", WATCH_MATCH])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()
}

/// Parser state for one `Notify` call as printed by `dbus-monitor`.
#[derive(Default)]
struct Capture {
    /// Whether the current block is a `Notify` method call.
    active: bool,
    /// app name, app icon, summary, body
    strings: Vec<String>,
    /// Flat action list (`key`, `label`, …).
    actions: Vec<String>,
    /// Set once the four leading strings have been seen (actions may follow).
    after_body: bool,
    /// Parsing the hints dict.
    in_hints: bool,
    /// Urgency parsed from the hints dict (`1` when absent).
    urgency: u8,
    /// The previous string was the `urgency` hint key.
    expecting_urgency_value: bool,
}

/// Expands the `\n` / `\t` / `\x` escapes `dbus-monitor` prints inside strings.
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

/// Extracts the first `string "…"` literal of a `dbus-monitor` line.
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

/// Reads `dbus-monitor` output until the child closes stdout, publishing one
/// [`NotificationInfo`] per observed `Notify` call.
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
        if trimmed.is_empty() {
            continue;
        }

        if trimmed.starts_with("array [") {
            // First array after the four strings is `actions`, the second is `hints`.
            if capture.strings.len() >= 4 {
                capture.after_body = true;
            }
            continue;
        }
        if trimmed.starts_with("dict entry(") {
            if capture.after_body {
                capture.in_hints = true;
            }
            continue;
        }

        if let Some(value) = quoted(trimmed) {
            if capture.in_hints {
                if value == "urgency" {
                    capture.expecting_urgency_value = true;
                }
                continue;
            }
            if capture.after_body {
                capture.actions.push(value);
            } else if capture.strings.len() < 4 {
                capture.strings.push(value);
            }
            continue;
        }

        if capture.expecting_urgency_value && trimmed.contains("byte ") {
            capture.urgency = trimmed
                .rsplit("byte ")
                .next()
                .unwrap_or("1")
                .trim()
                .parse()
                .unwrap_or(1);
            capture.expecting_urgency_value = false;
            continue;
        }

        if trimmed.starts_with("int32 ") && capture.strings.len() >= 4 {
            let app = capture.strings.first().cloned().unwrap_or_default();
            let icon = capture.strings.get(1).cloned().unwrap_or_default();
            let summary = capture.strings.get(2).cloned().unwrap_or_default();
            let body = capture.strings.get(3).cloned().unwrap_or_default();
            publish_notification(NotificationInfo {
                id: FALLBACK_ID.fetch_add(1, Ordering::Relaxed) as u64 + 1,
                app,
                summary,
                body,
                urgency: capture.urgency,
                timestamp_ms: now_ms(),
                icon,
                actions: pairs(capture.actions.clone()),
            });
            capture = Capture::default();
        }
    }
}
