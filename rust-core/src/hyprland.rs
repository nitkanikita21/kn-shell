//! Hyprland focus events: the active window and the current submap.
//!
//! Uses the compositor event socket (`.socket2.sock`) for instant updates and `hyprctl -j`
//! for the initial state and as a safety refresh; when the socket is unavailable it polls.

use std::io::{BufRead, BufReader};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::sync::OnceLock;
use std::time::Duration;

use serde_json::Value;

use crate::{publish_active_window, publish_submap, ActiveWindow};

static STARTED: OnceLock<()> = OnceLock::new();

const FALLBACK_POLL: Duration = Duration::from_millis(500);
const SAFETY_TIMEOUT: Duration = Duration::from_secs(2);

pub(crate) fn ensure_started() {
    STARTED.get_or_init(|| {
        std::thread::Builder::new()
            .name("kn-hyprland-listener".into())
            .spawn(run)
            .expect("failed to spawn hyprland listener thread");
    });
}

fn run() {
    match connect_event_socket() {
        Some(stream) => {
            eprintln!("[rust/hyprland] event socket connected — focus updates are event-driven");
            listen_events(stream);
            eprintln!("[rust/hyprland] event socket closed — falling back to polling");
        }
        None => eprintln!("[rust/hyprland] event socket unavailable — polling every 500 ms"),
    }
    poll_forever(FALLBACK_POLL);
}

fn event_socket_path() -> Option<PathBuf> {
    let signature = std::env::var("HYPRLAND_INSTANCE_SIGNATURE").ok()?;
    let mut bases = Vec::new();
    if let Ok(runtime_dir) = std::env::var("XDG_RUNTIME_DIR") {
        bases.push(PathBuf::from(runtime_dir).join("hypr"));
    }
    bases.push(PathBuf::from("/tmp/hypr"));
    for base in bases {
        let path = base.join(&signature).join(".socket2.sock");
        if path.exists() {
            return Some(path);
        }
    }
    None
}

fn connect_event_socket() -> Option<UnixStream> {
    let stream = UnixStream::connect(event_socket_path()?).ok()?;
    stream.set_read_timeout(Some(SAFETY_TIMEOUT)).ok()?;
    Some(stream)
}

fn listen_events(stream: UnixStream) {
    let mut reader = BufReader::new(stream);
    let mut line = String::new();

    // Publish the current state immediately; the bar would otherwise stay empty until the
    // first focus change or the safety refresh.
    refresh_all();

    loop {
        match reader.read_line(&mut line) {
            Ok(0) => return,
            Ok(_) => {
                handle_event(&line);
                line.clear();
            }
            Err(error)
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                ) =>
            {
                line.clear();
                refresh_all();
            }
            Err(_) => return,
        }
    }
}

fn handle_event(line: &str) {
    if let Some(payload) = line.strip_prefix("activewindow>>") {
        // "class,title"
        let (class, title) = payload.trim_end().split_once(',').unwrap_or((payload, ""));
        publish_active_window(ActiveWindow {
            class: class.to_string(),
            title: title.to_string(),
        });
    } else if let Some(name) = line.strip_prefix("submap>>") {
        publish_submap(name.trim_end().to_string());
    }
}

fn poll_forever(interval: Duration) {
    let mut last_window: Option<String> = None;
    let mut last_submap: Option<String> = None;
    loop {
        refresh_active_window(&mut last_window);
        refresh_submap(&mut last_submap);
        std::thread::sleep(interval);
    }
}

fn refresh_all() {
    let mut window: Option<String> = None;
    let mut submap: Option<String> = None;
    refresh_active_window(&mut window);
    refresh_submap(&mut submap);
}

fn refresh_active_window(last: &mut Option<String>) {
    let Some(window) = query_active_window() else { return };
    let key = format!("{}\u{1f}{}", window.class, window.title);
    if last.as_deref() != Some(key.as_str()) {
        *last = Some(key);
        publish_active_window(window);
    }
}

fn refresh_submap(last: &mut Option<String>) {
    let Some(name) = query_submap() else { return };
    if last.as_deref() != Some(name.as_str()) {
        *last = Some(name.clone());
        publish_submap(name);
    }
}

fn hyprctl(args: &[&str]) -> Option<std::process::Output> {
    let mut command = std::process::Command::new("hyprctl");
    if let Ok(signature) = std::env::var("HYPRLAND_INSTANCE_SIGNATURE") {
        command.env("HYPRLAND_INSTANCE_SIGNATURE", signature);
    }
    let output = command.args(args).output().ok()?;
    output.status.success().then_some(output)
}

fn query_active_window() -> Option<ActiveWindow> {
    let output = hyprctl(&["-j", "activewindow"])?;
    let value: Value = serde_json::from_slice(&output.stdout).ok()?;
    Some(ActiveWindow {
        class: value.get("class").and_then(Value::as_str).unwrap_or_default().to_string(),
        title: value.get("title").and_then(Value::as_str).unwrap_or_default().to_string(),
    })
}

fn query_submap() -> Option<String> {
    let output = hyprctl(&["submap"])?;
    let name = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if name.is_empty() { None } else { Some(name) }
}
