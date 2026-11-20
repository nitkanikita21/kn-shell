//! Workspace list from the Hyprland IPC.
//!
//! Fast path: subscribes to the compositor event socket (`.socket2.sock`) and re-reads the
//! workspace state on `workspace`/`movewindow`/… events. Latency is then ~1 frame instead
//! of the polling interval.
//!
//! Fallback: if the event socket is unavailable (e.g. `HYPRLAND_INSTANCE_SIGNATURE` is not
//! set) the service polls `hyprctl -j` every 400 ms, like the previous implementation. A
//! 2 s safety poll also runs while listening, to catch events that were missed.
//!
//! `hyprctl` refuses to run at all when `HYPRLAND_INSTANCE_SIGNATURE` is missing, so the
//! polling path resolves the instance directory itself and passes the signature down to the
//! child process. Without that the fallback would log the right message but publish nothing.

use std::io::{BufRead, BufReader};
use std::os::unix::net::UnixStream;
use std::path::PathBuf;
use std::sync::OnceLock;
use std::time::Duration;

use serde_json::Value;

use crate::{publish_workspaces, WorkspaceInfo};

static STARTED: OnceLock<()> = OnceLock::new();

/// Poll interval used when no event socket is available.
const FALLBACK_POLL: Duration = Duration::from_millis(400);
/// Safety poll while listening on the event socket (missed events, compositor reloads).
const SAFETY_POLL: Duration = Duration::from_secs(2);

pub(crate) fn ensure_started() {
    STARTED.get_or_init(|| {
        std::thread::Builder::new()
            .name("kn-workspace-listener".into())
            .spawn(run)
            .expect("failed to spawn workspace listener thread");
    });
}

fn run() {
    match connect_event_socket() {
        Some(stream) => {
            eprintln!("[rust/workspaces] event socket connected — workspace updates are event-driven");
            listen_events(stream);
            eprintln!("[rust/workspaces] event socket closed — falling back to polling");
        }
        None => {
            eprintln!("[rust/workspaces] event socket unavailable — polling every 400 ms");
        }
    }
    poll_forever(FALLBACK_POLL);
}

/// Every directory that can contain a Hyprland instance, newest lookup order first.
fn instance_bases() -> Vec<PathBuf> {
    let mut bases = Vec::new();
    if let Ok(runtime_dir) = std::env::var("XDG_RUNTIME_DIR") {
        bases.push(PathBuf::from(runtime_dir).join("hypr"));
    }
    bases.push(PathBuf::from("/tmp/hypr"));
    bases
}

fn event_socket_path() -> Option<PathBuf> {
    // Deliberately strict: only the variable the compositor exports identifies the event
    // socket. A missing variable therefore means "no event stream" and selects the polling
    // fallback, which is the documented contract of this module.
    let signature = std::env::var("HYPRLAND_INSTANCE_SIGNATURE").ok()?;
    for base in instance_bases() {
        let path = base.join(&signature).join(".socket2.sock");
        if path.exists() {
            return Some(path);
        }
    }
    None
}

/// Signature used for `hyprctl` calls.
///
/// Normally inherited from the environment. When the shell was started outside the
/// compositor's session (or `HYPRLAND_INSTANCE_SIGNATURE` was dropped for a test) the
/// signature is discovered from the runtime directory instead, because `hyprctl` exits with
/// "HYPRLAND_INSTANCE_SIGNATURE not set" and the service would stop publishing anything.
fn instance_signature() -> Option<&'static str> {
    static SIGNATURE: OnceLock<Option<String>> = OnceLock::new();
    SIGNATURE
        .get_or_init(|| {
            if let Ok(signature) = std::env::var("HYPRLAND_INSTANCE_SIGNATURE") {
                if !signature.is_empty() {
                    return Some(signature);
                }
            }
            discover_signature()
        })
        .as_deref()
}

/// Picks a live instance: a stale instance directory keeps its socket files around, so the
/// only reliable check is whether something still accepts connections on `.socket.sock`.
fn discover_signature() -> Option<String> {
    let mut best: Option<(std::time::SystemTime, String)> = None;
    for base in instance_bases() {
        let Ok(entries) = std::fs::read_dir(&base) else {
            continue;
        };
        for entry in entries.flatten() {
            let control = entry.path().join(".socket.sock");
            // A dead instance refuses the connection; skip it rather than point `hyprctl`
            // at a compositor that is no longer running.
            if UnixStream::connect(&control).is_err() {
                continue;
            }
            let Ok(modified) = std::fs::metadata(&control).and_then(|metadata| metadata.modified())
            else {
                continue;
            };
            let name = entry.file_name().to_string_lossy().into_owned();
            if best.as_ref().is_none_or(|(latest, _)| modified > *latest) {
                best = Some((modified, name));
            }
        }
    }
    best.map(|(_, name)| name)
}

fn connect_event_socket() -> Option<UnixStream> {
    let path = event_socket_path()?;
    let stream = UnixStream::connect(path).ok()?;
    stream.set_read_timeout(Some(SAFETY_POLL)).ok()?;
    Some(stream)
}

fn listen_events(stream: UnixStream) {
    let mut reader = BufReader::new(stream);
    let mut line = String::new();
    let mut last: Option<Vec<WorkspaceInfo>> = None;

    // Publish the current state immediately. Without this the bar would show an empty
    // workspace strip until the first compositor event or the 2 s safety poll.
    refresh(&mut last);

    loop {
        match reader.read_line(&mut line) {
            Ok(0) => return,
            Ok(_) => {
                if is_workspace_event(&line) {
                    refresh(&mut last);
                }
                line.clear();
            }
            Err(error)
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                ) =>
            {
                line.clear();
                refresh(&mut last);
            }
            Err(_) => return,
        }
    }
}

fn is_workspace_event(line: &str) -> bool {
    const EVENTS: &[&str] = &[
        "workspace>>",
        "workspacev2>>",
        "focusedmon>>",
        "createworkspace>>",
        "createworkspacev2>>",
        "destroyworkspace>>",
        "destroyworkspacev2>>",
        "moveworkspace>>",
        "moveworkspacev2>>",
        "renameworkspace>>",
        "movewindow>>",
        "movewindowv2>>",
        "urgent>>",
    ];
    EVENTS.iter().any(|prefix| line.starts_with(prefix))
}

fn refresh(last: &mut Option<Vec<WorkspaceInfo>>) {
    let current = query();
    if current != *last {
        if let Some(workspaces) = current.clone() {
            publish_workspaces(workspaces);
        }
        *last = current;
    }
}

fn poll_forever(interval: Duration) {
    let mut last: Option<Vec<WorkspaceInfo>> = None;
    loop {
        refresh(&mut last);
        std::thread::sleep(interval);
    }
}

fn hyprctl_json(args: &[&str]) -> Option<Value> {
    let mut command = std::process::Command::new("hyprctl");
    if let Some(signature) = instance_signature() {
        command.env("HYPRLAND_INSTANCE_SIGNATURE", signature);
    }
    let output = command.args(args).output().ok()?;
    if !output.status.success() {
        return None;
    }
    serde_json::from_slice::<Value>(&output.stdout).ok()
}

fn query() -> Option<Vec<WorkspaceInfo>> {
    let workspaces = hyprctl_json(&["-j", "workspaces"])?;
    let active = hyprctl_json(&["-j", "activeworkspace"])?;
    let active_id = active.get("id").and_then(Value::as_i64).unwrap_or(-1);

    let mut result = Vec::new();
    for workspace in workspaces.as_array()? {
        let id = workspace.get("id").and_then(Value::as_i64).unwrap_or(0) as i32;
        let name = workspace
            .get("name")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        result.push(WorkspaceInfo { id, name, active: id as i64 == active_id });
    }
    result.sort_by_key(|workspace| workspace.id);
    Some(result)
}
