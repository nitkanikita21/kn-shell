//! Media state from MPRIS through `playerctl --follow`.
//!
//! The previous implementation polled the `mpris` crate (blocking libdbus backend) and
//! could latch onto a stale player state: the first query after startup was the only one
//! ever published. `playerctl` handles MPRIS quirks (Firefox/Zen, playerctld proxies) and
//! `--follow` streams the current state immediately and then on every change, so there is
//! no polling loop to get stuck.

use std::io::{BufRead, BufReader};
use std::process::{Child, Command, Stdio};
use std::sync::OnceLock;
use std::time::Duration;

use crate::{publish_media, MediaState};

static STARTED: OnceLock<()> = OnceLock::new();

/// Unit separator between fields; `playerctl` formats one line per update.
const SEP: char = '\u{1f}';
const FORMAT: &str = "{{status}}\u{1f}{{artist}}\u{1f}{{title}}\u{1f}{{playerName}}";

pub(crate) fn ensure_started() {
    STARTED.get_or_init(|| {
        std::thread::Builder::new()
            .name("kn-media-listener".into())
            .spawn(run)
            .expect("failed to spawn media listener thread");
    });
}

fn run() {
    let mut last: Option<MediaState> = None;

    loop {
        match spawn_follow() {
            Some(mut child) => {
                eprintln!("[rust/media] playerctl --follow started");
                read_lines(&mut child, &mut last);
                let _ = child.kill();
                let _ = child.wait();
                eprintln!("[rust/media] playerctl exited; restarting in 1 s");
            }
            None => eprintln!("[rust/media] playerctl not available; retrying in 1 s"),
        }

        // No player (or playerctl missing): report once, then keep retrying.
        let offline = MediaState {
            title: String::new(),
            artist: String::new(),
            playing: false,
            player: String::new(),
            available: false,
        };
        if last.as_ref() != Some(&offline) {
            last = Some(offline.clone());
            publish_media(offline);
        }
        std::thread::sleep(Duration::from_millis(1000));
    }
}

fn spawn_follow() -> Option<Child> {
    Command::new("playerctl")
        .args(["--follow", "metadata", "--format", FORMAT])
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()
}

fn read_lines(child: &mut Child, last: &mut Option<MediaState>) {
    let Some(stdout) = child.stdout.take() else {
        return;
    };
    for line in BufReader::new(stdout).lines() {
        let Ok(line) = line else { return };
        let mut parts = line.split(SEP);
        let (Some(status), Some(artist), Some(title), Some(player)) =
            (parts.next(), parts.next(), parts.next(), parts.next())
        else {
            continue;
        };
        let state = MediaState {
            title: title.to_string(),
            artist: artist.to_string(),
            playing: status == "Playing",
            player: player.to_string(),
            available: true,
        };
        if last.as_ref() != Some(&state) {
            *last = Some(state.clone());
            publish_media(state);
        }
    }
}

/// Toggles playback through playerctl (also the fallback used by the shell action).
pub(crate) fn toggle_play_pause() {
    let _ = Command::new("playerctl")
        .arg("play-pause")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}
