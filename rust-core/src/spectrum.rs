//! Audio spectrum from CAVA (`cava` raw output).
//!
//! A child process is spawned with a generated raw-ASCII config; each output line is one
//! frame of bar values (`0..100`) separated by `;`. Values are published to Kotlin as a
//! `Vec<i32>`, and the UI draws them on a cairo canvas. If cava exits (no audio device,
//! compositor restart) it is respawned with a short backoff.

use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::OnceLock;
use std::time::Duration;

use crate::publish_spectrum;

static STARTED: OnceLock<()> = OnceLock::new();

const BARS: usize = 16;
const FRAMERATE: u32 = 30;

pub fn ensure_started() {
    STARTED.get_or_init(|| {
        std::thread::Builder::new()
            .name("kn-spectrum-listener".into())
            .spawn(run)
            .expect("failed to spawn spectrum listener thread");
    });
}

fn config_path() -> PathBuf {
    std::env::temp_dir().join(format!("kn-shell-cava-{}.conf", std::process::id()))
}

fn write_config(path: &PathBuf) -> std::io::Result<()> {
    let config = format!(
        "[general]\n\
         bars = {BARS}\n\
         framerate = {FRAMERATE}\n\
         \n\
         [input]\n\
         method = pulse\n\
         \n\
         [output]\n\
         method = raw\n\
         raw_target = /dev/stdout\n\
         data_format = ascii\n\
         ascii_max_range = 100\n\
         bar_delimiter = 59\n\
         frame_delimiter = 10\n\
         "
    );
    std::fs::write(path, config)
}

fn run() {
    let path = config_path();
    if let Err(error) = write_config(&path) {
        eprintln!("[rust/spectrum] cannot write cava config: {error}");
    }

    loop {
        match spawn_cava(&path) {
            Some(mut child) => {
                eprintln!("[rust/spectrum] cava started");
                read_frames(&mut child);
                let _ = child.kill();
                let _ = child.wait();
                eprintln!("[rust/spectrum] cava exited; restarting in 2 s");
            }
            None => eprintln!("[rust/spectrum] cava not available; retrying in 2 s"),
        }
        std::thread::sleep(Duration::from_secs(2));
    }
}

fn spawn_cava(path: &PathBuf) -> Option<Child> {
    Command::new("cava")
        .arg("-p")
        .arg(path)
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()
}

fn read_frames(child: &mut Child) {
    let Some(stdout) = child.stdout.take() else {
        return;
    };
    let reader = BufReader::new(stdout);
    let mut logged_first = false;

    for line in reader.lines() {
        let Ok(line) = line else { return };
        let bars: Vec<i32> = line
            .split(';')
            .filter_map(|value| value.trim().parse::<i32>().ok())
            .collect();
        if bars.is_empty() {
            continue;
        }
        if !logged_first {
            logged_first = true;
            eprintln!("[rust/spectrum] first frame: {} bars", bars.len());
        }
        publish_spectrum(bars);
    }
}
