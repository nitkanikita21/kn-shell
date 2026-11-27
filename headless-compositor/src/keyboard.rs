//! A virtual keyboard: the keymap clients get, which surface has the focus, and the control socket
//! through which a test run presses keys.
//!
//! Focus follows what a desktop would do without a pointer: the surface of the newest popup that
//! grabbed input, else the newest mapped window.

use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::os::fd::{AsFd, FromRawFd};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::{Path, PathBuf};

use wayland_server::protocol::wl_keyboard::{KeyState, KeymapFormat, WlKeyboard};
use wayland_server::protocol::wl_surface::WlSurface;
use wayland_server::Resource;

/// The US layout, compiled with `xkbcli compile-keymap --layout us`.
const KEYMAP: &str = include_str!("keymap.xkb");

const SHIFT: u32 = 1 << 0;
const CONTROL: u32 = 1 << 2;
const ALT: u32 = 1 << 3;
const SUPER: u32 = 1 << 6;

/// One key stroke: an evdev key code and the modifiers held while it goes down and up.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct Stroke {
    pub code: u32,
    pub modifiers: u32,
}

#[derive(Default)]
pub(super) struct Keyboard {
    devices: Vec<WlKeyboard>,
    windows: Vec<WlSurface>,
    grabs: Vec<WlSurface>,
    focused: Option<WlSurface>,
    serial: u32,
}

impl Keyboard {
    fn next_serial(&mut self) -> u32 {
        // A counter of its own, far from the one of the shell protocol.
        self.serial = self.serial.wrapping_add(1);
        self.serial | 0x8000_0000
    }

    /// A client asked for its keyboard.
    pub fn add_device(&mut self, device: WlKeyboard) {
        match keymap_file() {
            Ok((file, size)) => device.keymap(KeymapFormat::XkbV1, file.as_fd(), size),
            Err(error) => eprintln!("[compositor] cannot send the keymap: {error}"),
        }
        if device.version() >= 4 {
            // No key repeat: a stroke is exactly one press.
            device.repeat_info(0, 0);
        }
        if let Some(surface) = self.focused.clone().filter(|surface| surface.id().same_client_as(&device.id())) {
            let serial = self.next_serial();
            device.enter(serial, &surface, Vec::new());
            device.modifiers(serial, 0, 0, 0, 0);
        }
        self.devices.push(device);
    }

    /// A window showed its first picture.
    pub fn window_mapped(&mut self, surface: &WlSurface) {
        if !self.windows.contains(surface) {
            self.windows.push(surface.clone());
            self.refocus();
        }
    }

    /// A popup asked for the input.
    pub fn grab(&mut self, surface: &WlSurface) {
        self.grabs.retain(|other| other != surface);
        self.grabs.push(surface.clone());
        self.refocus();
    }

    /// A popup or a surface is gone.
    pub fn release(&mut self, surface: &WlSurface) {
        self.grabs.retain(|other| other != surface);
        self.refocus();
    }

    /// A surface was destroyed.
    pub fn forget(&mut self, surface: &WlSurface) {
        self.windows.retain(|other| other != surface);
        self.release(surface);
    }

    fn refocus(&mut self) {
        let target = self.grabs.iter().rev().chain(self.windows.iter().rev()).find(|s| s.is_alive()).cloned();
        if target == self.focused {
            return;
        }
        let serial = self.next_serial();
        self.devices.retain(Resource::is_alive);
        if let Some(old) = self.focused.take().filter(Resource::is_alive) {
            for device in self.devices.iter().filter(|d| d.id().same_client_as(&old.id())) {
                device.leave(serial, &old);
            }
        }
        if let Some(new) = &target {
            for device in self.devices.iter().filter(|d| d.id().same_client_as(&new.id())) {
                device.enter(serial, new, Vec::new());
                device.modifiers(serial, 0, 0, 0, 0);
            }
        }
        self.focused = target;
    }

    /// Sends one stroke to the focused surface.
    pub fn press(&mut self, stroke: Stroke, time: u32) -> Result<(), String> {
        let Some(surface) = self.focused.clone().filter(Resource::is_alive) else {
            return Err("no surface has the keyboard focus".to_owned());
        };
        let devices: Vec<WlKeyboard> = self
            .devices
            .iter()
            .filter(|d| d.is_alive() && d.id().same_client_as(&surface.id()))
            .cloned()
            .collect();
        if devices.is_empty() {
            return Err("the focused client has no keyboard".to_owned());
        }
        for device in devices {
            device.modifiers(self.next_serial(), stroke.modifiers, 0, 0, 0);
            device.key(self.next_serial(), time, stroke.code, KeyState::Pressed);
            device.key(self.next_serial(), time, stroke.code, KeyState::Released);
            device.modifiers(self.next_serial(), 0, 0, 0, 0);
        }
        Ok(())
    }
}

fn keymap_file() -> io::Result<(File, u32)> {
    let fd = unsafe { libc::memfd_create(c"headless-keymap".as_ptr(), libc::MFD_CLOEXEC) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: memfd_create just returned this descriptor and nothing else owns it.
    let mut file = unsafe { File::from_raw_fd(fd) };
    file.write_all(KEYMAP.as_bytes())?;
    file.write_all(&[0])?;
    Ok((file, KEYMAP.len() as u32 + 1))
}

/// The strokes a command line asks for:
/// `key <Name> [shift] [ctrl] [alt] [super]` or `type <text>`.
pub(super) fn parse_command(line: &str) -> Result<Vec<Stroke>, String> {
    let (command, rest) = line.split_once(' ').ok_or_else(|| format!("expected `key …` or `type …`, got {line:?}"))?;
    match command {
        "key" => {
            let mut words = rest.split_whitespace();
            let name = words.next().ok_or("key needs a name")?;
            let code = key_code(name).ok_or_else(|| format!("unknown key {name:?}"))?;
            let mut modifiers = 0;
            for word in words {
                modifiers |= match word {
                    "shift" => SHIFT,
                    "ctrl" => CONTROL,
                    "alt" => ALT,
                    "super" => SUPER,
                    other => return Err(format!("unknown modifier {other:?}")),
                };
            }
            Ok(vec![Stroke { code, modifiers }])
        }
        "type" => rest
            .chars()
            .map(|c| {
                let name = match c {
                    ' ' => "Space".to_owned(),
                    '-' => "Minus".to_owned(),
                    '.' => "Period".to_owned(),
                    ',' => "Comma".to_owned(),
                    '/' => "Slash".to_owned(),
                    c => c.to_ascii_uppercase().to_string(),
                };
                let code = key_code(&name).ok_or_else(|| format!("cannot type {c:?}"))?;
                Ok(Stroke { code, modifiers: if c.is_ascii_uppercase() { SHIFT } else { 0 } })
            })
            .collect(),
        other => Err(format!("unknown command {other:?}")),
    }
}

/// evdev codes (`linux/input-event-codes.h`) of the keys tests press.
fn key_code(name: &str) -> Option<u32> {
    const LETTERS: [(&str, u32); 26] = [
        ("A", 30), ("B", 48), ("C", 46), ("D", 32), ("E", 18), ("F", 33), ("G", 34), ("H", 35), ("I", 23),
        ("J", 36), ("K", 37), ("L", 38), ("M", 50), ("N", 49), ("O", 24), ("P", 25), ("Q", 16), ("R", 19),
        ("S", 31), ("T", 20), ("U", 22), ("V", 47), ("W", 17), ("X", 45), ("Y", 21), ("Z", 44),
    ];
    const NAMED: [(&str, u32); 31] = [
        ("Escape", 1), ("1", 2), ("2", 3), ("3", 4), ("4", 5), ("5", 6), ("6", 7), ("7", 8), ("8", 9), ("9", 10),
        ("0", 11), ("Minus", 12), ("Backspace", 14), ("Tab", 15), ("Enter", 28), ("Comma", 51), ("Period", 52),
        ("Slash", 53), ("Space", 57), ("Home", 102), ("Up", 103), ("PageUp", 104), ("Left", 105),
        ("Right", 106), ("End", 107), ("Down", 108), ("PageDown", 109), ("Delete", 111), ("F11", 87),
        ("F12", 88), ("Menu", 127),
    ];
    if let Some(number) = name.strip_prefix('F').and_then(|n| n.parse::<u32>().ok()) {
        if (1..=10).contains(&number) {
            return Some(58 + number);
        }
    }
    LETTERS.iter().chain(NAMED.iter()).find(|(key, _)| *key == name).map(|(_, code)| *code)
}

/// The control socket: one command per line, answered with `ok` or `error: …` once the events are
/// on their way to the client.
pub(super) struct Control {
    listener: UnixListener,
    path: PathBuf,
    clients: Vec<(UnixStream, Vec<u8>)>,
}

impl Control {
    pub fn bind(path: &Path) -> io::Result<Self> {
        let _ = fs::remove_file(path);
        let listener = UnixListener::bind(path)?;
        listener.set_nonblocking(true)?;
        Ok(Self { listener, path: path.to_owned(), clients: Vec::new() })
    }

    /// The complete lines that arrived since the last call, each with the index of its client.
    pub fn receive(&mut self) -> Vec<(usize, String)> {
        while let Ok((stream, _)) = self.listener.accept() {
            if stream.set_nonblocking(true).is_ok() {
                self.clients.push((stream, Vec::new()));
            }
        }
        let mut lines = Vec::new();
        let mut closed = Vec::new();
        for (index, (stream, buffer)) in self.clients.iter_mut().enumerate() {
            let mut chunk = [0; 512];
            loop {
                match stream.read(&mut chunk) {
                    Ok(0) => {
                        closed.push(index);
                        break;
                    }
                    Ok(read) => buffer.extend_from_slice(&chunk[..read]),
                    Err(_) => break,
                }
            }
            while let Some(end) = buffer.iter().position(|byte| *byte == b'\n') {
                let line: Vec<u8> = buffer.drain(..=end).collect();
                lines.push((index, String::from_utf8_lossy(&line[..end]).into_owned()));
            }
        }
        // Clients with a line still to be answered stay until the next call.
        for index in closed.into_iter().rev() {
            if !lines.iter().any(|(client, _)| *client >= index) {
                self.clients.remove(index);
            }
        }
        lines
    }

    pub fn answer(&mut self, client: usize, result: Result<(), String>) {
        let text = match result {
            Ok(()) => "ok\n".to_owned(),
            Err(error) => format!("error: {error}\n"),
        };
        if let Some((stream, _)) = self.clients.get_mut(client) {
            let _ = stream.write_all(text.as_bytes());
        }
    }
}

impl Drop for Control {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_key_command_gives_one_stroke_with_its_modifiers() {
        assert_eq!(parse_command("key Tab shift"), Ok(vec![Stroke { code: 15, modifiers: SHIFT }]));
        assert_eq!(parse_command("key F8"), Ok(vec![Stroke { code: 66, modifiers: 0 }]));
        assert!(parse_command("key Nope").is_err());
        assert!(parse_command("key Tab hyper").is_err());
        assert!(parse_command("dance").is_err());
    }

    #[test]
    fn typed_text_becomes_strokes_with_shift_for_capitals() {
        let strokes = parse_command("type Ab 1").unwrap();

        assert_eq!(strokes.iter().map(|s| s.code).collect::<Vec<_>>(), [30, 48, 57, 2]);
        assert_eq!(strokes[0].modifiers, SHIFT);
        assert_eq!(strokes[1].modifiers, 0);
    }
}
