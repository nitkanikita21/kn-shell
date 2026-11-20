//! Notification list state held by the server, plus the helpers that convert
//! freedesktop arguments into the records published to Kotlin.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::{publish_notification_closed, NotificationAction};

/// Server bookkeeping: the id counter plus the notifications still on screen.
#[derive(Default)]
pub(crate) struct ServerState {
    /// Last id handed out; starts at 0 and is bumped with `max(1)` after wrapping.
    pub(crate) next_id: u32,
    /// Accepted notifications by id.
    pub(crate) items: HashMap<u32, Item>,
}

/// One accepted notification as remembered by the server.
pub(crate) struct Item {
    /// Application name from `Notify`.
    pub(crate) app: String,
    /// Summary line.
    pub(crate) summary: String,
    /// Body text.
    pub(crate) body: String,
    /// Urgency hint (`1` normal, `2` critical).
    pub(crate) urgency: u8,
    /// Flat `key, label, …` action list as sent by the client.
    pub(crate) actions: Vec<String>,
    /// Acceptance time in Unix epoch milliseconds.
    pub(crate) timestamp_ms: u64,
}

/// Pairs the flat freedesktop action list (`key`, `label`, `key`, `label`, …) into
/// records; a trailing key without a label reuses the key as the label.
pub(crate) fn pairs(flat: Vec<String>) -> Vec<NotificationAction> {
    flat.chunks(2)
        .filter_map(|chunk| {
            let key = chunk.first()?.clone();
            let label = chunk.get(1).cloned().unwrap_or_else(|| key.clone());
            Some(NotificationAction { key, label })
        })
        .collect()
}

/// Current wall-clock time in Unix epoch milliseconds (`0` if the clock is before 1970).
pub(crate) fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Forgets a notification and notifies listeners that it closed.
///
/// Returns `false` when the id was unknown, in which case nothing is published.
pub(crate) fn close_notification(state: &mut Arc<Mutex<ServerState>>, id: u32, reason: u32) -> bool {
    let mut guard = state.lock().unwrap();
    let Some(item) = guard.items.remove(&id) else {
        return false;
    };
    drop(guard);
    eprintln!(
        "[rust/notifications] closed id={id} reason={reason} app=\"{}\"",
        item.app
    );
    publish_notification_closed(id as u64, reason);
    true
}
