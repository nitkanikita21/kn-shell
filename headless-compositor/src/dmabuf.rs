//! `zwp_linux_dmabuf_v1`: what lets the client render on the GPU.
//!
//! With `wl_shm` alone GTK cannot present a GPU frame (its Vulkan renderer fails to realize and GL
//! falls back to llvmpipe). Advertising dma-buf support, together with the device to render on, makes
//! the client behave as it does on a real desktop. The buffers are never imported or read here, so
//! this needs no graphics library: a buffer is accepted, and released as soon as it is committed.

use std::fs::{self, File};
use std::io::{self, Write};
use std::os::fd::{AsFd, FromRawFd};
use std::os::unix::fs::MetadataExt;

use wayland_protocols::wp::linux_dmabuf::zv1::server::zwp_linux_buffer_params_v1::{self, ZwpLinuxBufferParamsV1};
use wayland_protocols::wp::linux_dmabuf::zv1::server::zwp_linux_dmabuf_feedback_v1::{
    TrancheFlags, ZwpLinuxDmabufFeedbackV1,
};
use wayland_protocols::wp::linux_dmabuf::zv1::server::zwp_linux_dmabuf_v1::{self, ZwpLinuxDmabufV1};
use wayland_server::protocol::wl_buffer::WlBuffer;
use wayland_server::{Client, DataInit, Dispatch, DisplayHandle, GlobalDispatch, New, Resource};

use super::State;

/// Highest protocol version served; version 4 added the feedback object that names the device.
pub(super) const VERSION: u32 = 4;

/// `DRM_FORMAT_*` codes of the 8-bit and 10-bit RGB layouts GTK renders into.
const FORMATS: [u32; 8] = [
    fourcc(b"AR24"),
    fourcc(b"XR24"),
    fourcc(b"AB24"),
    fourcc(b"XB24"),
    fourcc(b"AR30"),
    fourcc(b"XR30"),
    fourcc(b"AB30"),
    fourcc(b"XB30"),
];

/// "The driver chooses the layout".
const MODIFIER_INVALID: u64 = 0x00ff_ffff_ffff_ffff;
const MODIFIER_LINEAR: u64 = 0;

const fn fourcc(code: &[u8; 4]) -> u32 {
    u32::from_le_bytes(*code)
}

/// What is advertised to clients: the render device and the format/modifier table.
pub(super) struct Dmabuf {
    /// `dev_t` of the render node, in the byte layout the protocol carries it in.
    device: Vec<u8>,
    pairs: Vec<(u32, u64)>,
    /// The table of [`Dmabuf::pairs`] in shared memory, as feedback version 4 passes it.
    table: File,
    table_size: u32,
}

impl Dmabuf {
    /// Describes the first DRM render node, or `None` on a machine without one: the client then
    /// renders in software through `wl_shm`.
    pub(super) fn detect() -> Option<Self> {
        let rdev = (128..192).find_map(|minor| fs::metadata(format!("/dev/dri/renderD{minor}")).ok().map(|m| m.rdev()))?;
        let pairs = format_pairs();
        let bytes = encode_table(&pairs);
        match memory_file(&bytes) {
            Ok(table) => Some(Self {
                device: (rdev as libc::dev_t).to_ne_bytes().to_vec(),
                pairs,
                table,
                table_size: bytes.len() as u32,
            }),
            Err(error) => {
                eprintln!("[compositor] no GPU rendering, cannot create the format table: {error}");
                None
            }
        }
    }

    fn send_feedback(&self, feedback: &ZwpLinuxDmabufFeedbackV1) {
        feedback.format_table(self.table.as_fd(), self.table_size);
        feedback.main_device(self.device.clone());
        // One preference tranche: every pair, rendered on the main device.
        feedback.tranche_target_device(self.device.clone());
        feedback.tranche_flags(TrancheFlags::empty());
        let indices = (0..self.pairs.len() as u16).flat_map(u16::to_ne_bytes).collect();
        feedback.tranche_formats(indices);
        feedback.tranche_done();
        feedback.done();
    }
}

fn format_pairs() -> Vec<(u32, u64)> {
    FORMATS.iter().flat_map(|&format| [(format, MODIFIER_INVALID), (format, MODIFIER_LINEAR)]).collect()
}

/// The wire layout of the table: 16 bytes per entry, `u32` format, 4 bytes of padding, `u64` modifier.
fn encode_table(pairs: &[(u32, u64)]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(pairs.len() * 16);
    for &(format, modifier) in pairs {
        bytes.extend_from_slice(&format.to_ne_bytes());
        bytes.extend_from_slice(&[0; 4]);
        bytes.extend_from_slice(&modifier.to_ne_bytes());
    }
    bytes
}

fn memory_file(bytes: &[u8]) -> io::Result<File> {
    // SAFETY: the name is a NUL-terminated literal and the returned descriptor is checked.
    let fd = unsafe { libc::memfd_create(c"headless-dmabuf-formats".as_ptr(), libc::MFD_CLOEXEC) };
    if fd < 0 {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: `fd` is a freshly created descriptor that nothing else owns.
    let mut file = unsafe { File::from_raw_fd(fd) };
    file.write_all(bytes)?;
    Ok(file)
}

impl GlobalDispatch<ZwpLinuxDmabufV1, ()> for State {
    fn bind(
        state: &mut Self,
        _: &DisplayHandle,
        _: &Client,
        resource: New<ZwpLinuxDmabufV1>,
        _: &(),
        data_init: &mut DataInit<'_, Self>,
    ) {
        let global = data_init.init(resource, ());
        // Before version 4 the formats are announced on bind; version 4 forbids these events.
        if global.version() < 4 {
            for &(format, modifier) in state.dmabuf.iter().flat_map(|dmabuf| &dmabuf.pairs) {
                if global.version() == 3 {
                    global.modifier(format, (modifier >> 32) as u32, modifier as u32);
                } else if modifier == MODIFIER_INVALID {
                    global.format(format);
                }
            }
        }
    }
}

impl Dispatch<ZwpLinuxDmabufV1, ()> for State {
    fn request(
        state: &mut Self,
        _: &Client,
        _: &ZwpLinuxDmabufV1,
        request: zwp_linux_dmabuf_v1::Request,
        _: &(),
        _: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        let feedback = match request {
            zwp_linux_dmabuf_v1::Request::CreateParams { params_id } => {
                data_init.init(params_id, ());
                return;
            }
            zwp_linux_dmabuf_v1::Request::GetDefaultFeedback { id } => data_init.init(id, ()),
            zwp_linux_dmabuf_v1::Request::GetSurfaceFeedback { id, .. } => data_init.init(id, ()),
            _ => return,
        };
        if let Some(dmabuf) = &state.dmabuf {
            dmabuf.send_feedback(&feedback);
        }
    }
}

impl Dispatch<ZwpLinuxBufferParamsV1, ()> for State {
    fn request(
        _: &mut Self,
        client: &Client,
        params: &ZwpLinuxBufferParamsV1,
        request: zwp_linux_buffer_params_v1::Request,
        _: &(),
        handle: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            // The plane descriptors of `Add` are dropped, and so closed: nothing imports them.
            zwp_linux_buffer_params_v1::Request::CreateImmed { buffer_id, .. } => {
                data_init.init(buffer_id, ());
            }
            zwp_linux_buffer_params_v1::Request::Create { .. } => {
                match client.create_resource::<WlBuffer, (), Self>(handle, 1, ()) {
                    Ok(buffer) => params.created(&buffer),
                    Err(error) => {
                        eprintln!("[compositor] cannot create a dma-buf buffer: {error}");
                        params.failed();
                    }
                }
            }
            _ => {}
        }
    }
}

impl Dispatch<ZwpLinuxDmabufFeedbackV1, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &ZwpLinuxDmabufFeedbackV1,
        _: <ZwpLinuxDmabufFeedbackV1 as Resource>::Request,
        _: &(),
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_table_has_sixteen_bytes_per_pair() {
        let table = encode_table(&[(fourcc(b"AR24"), MODIFIER_LINEAR), (fourcc(b"XR24"), MODIFIER_INVALID)]);

        assert_eq!(table.len(), 32);
        assert_eq!(&table[0..4], &0x3432_5241u32.to_ne_bytes());
        assert_eq!(&table[8..16], &MODIFIER_LINEAR.to_ne_bytes());
        assert_eq!(&table[24..32], &MODIFIER_INVALID.to_ne_bytes());
    }

    #[test]
    fn every_format_is_offered_with_both_modifiers() {
        let pairs = format_pairs();

        assert_eq!(pairs.len(), FORMATS.len() * 2);
        assert!(FORMATS.iter().all(|&format| {
            pairs.contains(&(format, MODIFIER_INVALID)) && pairs.contains(&(format, MODIFIER_LINEAR))
        }));
    }
}
