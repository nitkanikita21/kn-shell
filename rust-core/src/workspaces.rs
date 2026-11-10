//! Workspace list from the Hyprland IPC (`hyprctl -j`).
//!
//! A Wayland-agnostic workspace protocol does not exist, so this service talks to the
//! compositor through `hyprctl`. It is the same source waybar's hyprland module uses.
//! Kept behind its own service so it can be replaced with a compositor-agnostic backend.

use std::sync::OnceLock;
use std::time::Duration;

use serde_json::Value;

use crate::{publish_workspaces, WorkspaceInfo};

static STARTED: OnceLock<()> = OnceLock::new();

pub fn ensure_started() {
    STARTED.get_or_init(|| {
        std::thread::Builder::new()
            .name("kn-workspace-listener".into())
            .spawn(poll_loop)
            .expect("failed to spawn workspace listener thread");
    });
}

fn poll_loop() {
    let mut last: Option<Vec<WorkspaceInfo>> = None;
    loop {
        let current = query();
        if current != last {
            if let Some(workspaces) = current.clone() {
                publish_workspaces(workspaces);
            }
            last = current;
        }
        std::thread::sleep(Duration::from_millis(400));
    }
}

fn hyprctl_json(args: &[&str]) -> Option<Value> {
    let output = std::process::Command::new("hyprctl")
        .args(args)
        .output()
        .ok()?;
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
