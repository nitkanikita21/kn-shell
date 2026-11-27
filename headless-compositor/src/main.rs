//! The headless compositor as a program, for any Wayland client that needs a display but no screen.
//!
//! - `headless-compositor [<socket-name>]` serves until killed; start clients with that
//!   `WAYLAND_DISPLAY`.
//! - `headless-compositor [<socket-name>] -- <command> [<args>…]` runs the command as a client of a
//!   private display and exits with the command's status. `HEADLESS_COMPOSITOR_KEYS` names the
//!   control socket through which it can press keys.
//!
//! Without `XDG_RUNTIME_DIR` (a CI container, a bare shell) a private one is made and removed again.

use std::env;
use std::ffi::OsString;
use std::fs::{self, DirBuilder};
use std::io;
use std::os::unix::fs::DirBuilderExt;
use std::path::PathBuf;
use std::process::{Command, ExitCode};

use headless_compositor::Compositor;

const USAGE: &str = "usage: headless-compositor [<socket-name>] [-- <command> [<args>...]]";

fn main() -> ExitCode {
    let mut args: Vec<OsString> = env::args_os().skip(1).collect();
    let command = match args.iter().position(|arg| arg == "--") {
        Some(at) => {
            let command = args.split_off(at + 1);
            args.pop();
            if command.is_empty() {
                eprintln!("{USAGE}");
                return ExitCode::from(2);
            }
            Some(command)
        }
        None => None,
    };
    let name = match args.as_slice() {
        // A command gets a name of its own, so that several runs can share one runtime directory.
        [] if command.is_some() => format!("headless-compositor-{}", std::process::id()),
        [] => "headless-compositor".to_owned(),
        [name] if !name.to_string_lossy().starts_with('-') => name.to_string_lossy().into_owned(),
        _ => {
            eprintln!("{USAGE}");
            return ExitCode::from(2);
        }
    };

    let made = match ensure_runtime_dir() {
        Ok(made) => made,
        Err(error) => {
            eprintln!("headless-compositor: cannot make a runtime directory: {error}");
            return ExitCode::FAILURE;
        }
    };
    let code = serve(&name, command);
    if let Some(dir) = made {
        let _ = fs::remove_dir_all(dir);
    }
    code
}

fn serve(name: &str, command: Option<Vec<OsString>>) -> ExitCode {
    let compositor = match Compositor::start(name) {
        Ok(compositor) => compositor,
        Err(error) => {
            eprintln!("headless-compositor: cannot start: {error}");
            return ExitCode::FAILURE;
        }
    };
    let Some(command) = command else {
        println!("listening on WAYLAND_DISPLAY={}", compositor.socket_name());
        loop {
            std::thread::park();
        }
    };
    let status = Command::new(&command[0])
        .args(&command[1..])
        .env("WAYLAND_DISPLAY", compositor.socket_name())
        .env("GDK_BACKEND", "wayland")
        .env("HEADLESS_COMPOSITOR_KEYS", compositor.keys_socket())
        // The client must not reach the desktop the caller may be sitting in.
        .env_remove("DISPLAY")
        .env_remove("HYPRLAND_INSTANCE_SIGNATURE")
        .status();
    match status {
        // A command ended by a signal has no code; it failed all the same.
        Ok(status) => ExitCode::from(status.code().map_or(1, |code| code as u8)),
        Err(error) => {
            eprintln!("headless-compositor: cannot run {}: {error}", command[0].to_string_lossy());
            ExitCode::from(127)
        }
    }
}

/// Makes a private `XDG_RUNTIME_DIR` when there is none. Returns the directory to remove afterwards.
fn ensure_runtime_dir() -> io::Result<Option<PathBuf>> {
    if env::var_os("XDG_RUNTIME_DIR").is_some() {
        return Ok(None);
    }
    let dir = env::temp_dir().join(format!("headless-compositor-runtime-{}", std::process::id()));
    DirBuilder::new().mode(0o700).create(&dir)?;
    // Set before the compositor thread exists; the client inherits it.
    env::set_var("XDG_RUNTIME_DIR", &dir);
    Ok(Some(dir))
}
