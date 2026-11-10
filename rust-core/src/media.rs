//! Media state via MPRIS (`mpris` crate, blocking libdbus backend).
//!
//! The MVP polls the active player every 700 ms and only broadcasts when the state
//! actually changes. This keeps the implementation free of an async runtime while
//! still reacting promptly to metadata/playback changes.

use std::sync::OnceLock;
use std::time::Duration;

use mpris::{PlaybackStatus, Player, PlayerFinder};

use crate::{publish_media, MediaState};

static STARTED: OnceLock<()> = OnceLock::new();

pub fn ensure_started() {
    STARTED.get_or_init(|| {
        std::thread::Builder::new()
            .name("kn-media-listener".into())
            .spawn(poll_loop)
            .expect("failed to spawn media listener thread");
    });
}

fn poll_loop() {
    let mut last: Option<MediaState> = None;
    loop {
        let current = query();
        if current != last {
            if let Some(state) = current.clone() {
                publish_media(state);
            }
            last = current;
        }
        std::thread::sleep(Duration::from_millis(700));
    }
}

fn active_player() -> Option<Player> {
    let finder = PlayerFinder::new().ok()?;
    finder
        .find_active()
        .ok()
        .or_else(|| finder.find_all().ok()?.into_iter().next())
}

fn query() -> Option<MediaState> {
    let player = active_player()?;
    let available = true;
    let player_name = player.identity().to_string();

    match player.get_metadata() {
        Ok(metadata) => {
            let title = metadata.title().unwrap_or_default().to_string();
            let artist = metadata
                .artists()
                .map(|artists| artists.join(", "))
                .unwrap_or_default();
            let playing = matches!(player.get_playback_status(), Ok(PlaybackStatus::Playing));
            Some(MediaState { title, artist, playing, player: player_name, available })
        }
        Err(_) => Some(MediaState {
            title: String::new(),
            artist: String::new(),
            playing: false,
            player: player_name,
            available,
        }),
    }
}

pub fn toggle_play_pause() {
    if let Some(player) = active_player() {
        if player.play_pause().is_ok() {
            return;
        }
    }
    // Fallback for players whose MPRIS implementation does not allow play/pause.
    let _ = std::process::Command::new("playerctl").arg("play-pause").status();
}
