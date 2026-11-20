//! Battery state from `/sys/class/power_supply` (no DBus, no UPower dependency).
//!
//! Polls every 2 s; a value is published only when it changes, so the UI gets no wakeups
//! while the charge stays constant.

use std::fs;
use std::sync::OnceLock;
use std::time::Duration;

use crate::{publish_battery, BatteryState};

static STARTED: OnceLock<()> = OnceLock::new();

const POLL: Duration = Duration::from_secs(2);

pub(crate) fn ensure_started() {
    STARTED.get_or_init(|| {
        std::thread::Builder::new()
            .name("kn-battery-listener".into())
            .spawn(poll_loop)
            .expect("failed to spawn battery listener thread");
    });
}

fn read_trim(path: &std::path::Path) -> Option<String> {
    fs::read_to_string(path).ok().map(|value| value.trim().to_string())
}

fn query() -> BatteryState {
    let mut state = BatteryState {
        present: false,
        percent: 0,
        charging: false,
        status: "Unknown".to_string(),
    };

    let Ok(entries) = fs::read_dir("/sys/class/power_supply") else {
        return state;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if read_trim(&path.join("type")).as_deref() != Some("Battery") {
            continue;
        }
        state.present = true;
        if let Some(capacity) = read_trim(&path.join("capacity")).and_then(|v| v.parse::<i32>().ok()) {
            state.percent = capacity.clamp(0, 100);
        }
        if let Some(status) = read_trim(&path.join("status")) {
            state.charging = status == "Charging" || status == "Full";
            state.status = status;
        }
    }
    state
}

fn poll_loop() {
    let mut last: Option<BatteryState> = None;
    loop {
        let current = query();
        if last.as_ref() != Some(&current) {
            if current.present {
                eprintln!(
                    "[rust/battery] {}% {} (charging={})",
                    current.percent, current.status, current.charging
                );
            }
            last = Some(current.clone());
            publish_battery(current);
        }
        std::thread::sleep(POLL);
    }
}
