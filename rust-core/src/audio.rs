//! Audio volume of the default sink.
//!
//! The MVP talks to PipeWire through its PulseAudio-compatible server
//! (`pipewire-pulse`) using the `pactl` client. This is the same transport the
//! official libpulse bindings would use, without pulling ~300 lines of unsafe
//! mainloop integration into the shell core. The CLI dependency is documented in
//! the README as a known limitation.

use std::sync::OnceLock;
use std::time::Duration;

use crate::publish_volume;

static STARTED: OnceLock<()> = OnceLock::new();

pub(crate) fn ensure_started() {
    STARTED.get_or_init(|| {
        std::thread::Builder::new()
            .name("kn-audio-listener".into())
            .spawn(poll_loop)
            .expect("failed to spawn audio listener thread");
    });
}

fn poll_loop() {
    let mut last = None;
    loop {
        let current = query_volume();
        if current.is_some() && current != last {
            if let Some(volume) = current {
                publish_volume(volume);
            }
        }
        last = current;
        std::thread::sleep(Duration::from_millis(500));
    }
}

/// Reads the default sink volume (0-150).
fn query_volume() -> Option<i32> {
    let output = std::process::Command::new("pactl")
        .args(["get-sink-volume", "@DEFAULT_SINK@"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&output.stdout);
    // Example: "Volume: front-left: 65525 / 100% / -0.00 dB,  front-right: ..."
    let percent_index = text.find('%')?;
    let digits: String = text[..percent_index]
        .chars()
        .rev()
        .take_while(|c| c.is_ascii_digit())
        .collect::<String>()
        .chars()
        .rev()
        .collect();
    digits.parse().ok()
}

pub(crate) fn set_volume(volume: i32) {
    let _ = std::process::Command::new("pactl")
        .args(["set-sink-volume", "@DEFAULT_SINK@", &format!("{volume}%")])
        .status();
}
