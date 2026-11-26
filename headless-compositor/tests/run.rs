//! `headless-compositor -- <command>`: what the command sees and what is left behind. `sh` stands in
//! for a Wayland client, so no GTK is needed.

use std::env;
use std::fs;
use std::path::PathBuf;
use std::process::{Command, Output};

fn run(runtime_dir: Option<&PathBuf>, script: &str) -> Output {
    let mut command = Command::new(env!("CARGO_BIN_EXE_headless-compositor"));
    command.args(["--", "sh", "-c", script]).env("DISPLAY", ":99");
    match runtime_dir {
        Some(dir) => command.env("XDG_RUNTIME_DIR", dir),
        None => command.env_remove("XDG_RUNTIME_DIR"),
    };
    command.output().expect("cannot run headless-compositor")
}

#[test]
fn the_command_gets_a_private_display_and_its_status_comes_back() {
    let dir = env::temp_dir().join(format!("headless-compositor-test-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();

    let output = run(
        Some(&dir),
        r#"test -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" && echo "$GDK_BACKEND ${DISPLAY:-no-x11}"; exit 3"#,
    );

    assert_eq!(output.status.code(), Some(3), "{output:?}");
    assert_eq!(String::from_utf8_lossy(&output.stdout), "wayland no-x11\n");
    let left: Vec<_> = fs::read_dir(&dir).unwrap().flatten().map(|entry| entry.file_name()).collect();
    assert!(left.is_empty(), "the socket files are removed: {left:?}");
    fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn without_a_runtime_directory_one_is_made_and_removed() {
    let output = run(None, r#"test -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" && echo "$XDG_RUNTIME_DIR""#);

    assert!(output.status.success(), "{output:?}");
    let made = PathBuf::from(String::from_utf8_lossy(&output.stdout).trim());
    assert!(made.starts_with(env::temp_dir()), "{made:?}");
    assert!(!made.exists(), "{made:?} is removed afterwards");
}
