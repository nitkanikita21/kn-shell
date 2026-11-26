//! A headless Wayland compositor with just enough protocol for a GTK 4 client to map its windows
//! and keep its frame clock running.
//!
//! Nothing is ever drawn: buffers are released as soon as they are committed, because the client
//! renders its own screenshots. What the client does need from a compositor is the handshake
//! (globals, `configure`) and a steady stream of `wl_callback.done` frame events. Without them GTK
//! freezes after the first frame and nothing recomposes or lays out. `linux-dmabuf` (see [`dmabuf`])
//! is what lets it render on the GPU.
//!
//! No input devices are advertised; the client is driven through its inspector instead.

use std::io;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use wayland_protocols::wp::linux_dmabuf::zv1::server::zwp_linux_dmabuf_v1::ZwpLinuxDmabufV1;
use wayland_protocols::xdg::shell::server::xdg_popup::{self, XdgPopup};
use wayland_protocols::xdg::shell::server::xdg_positioner::{self, XdgPositioner};
use wayland_protocols::xdg::shell::server::xdg_surface::{self, XdgSurface};
use wayland_protocols::xdg::shell::server::xdg_toplevel::{self, XdgToplevel};
use wayland_protocols::xdg::shell::server::xdg_wm_base::{self, XdgWmBase};
use wayland_server::protocol::wl_buffer::WlBuffer;
use wayland_server::protocol::wl_callback::WlCallback;
use wayland_server::protocol::wl_compositor::{self, WlCompositor};
use wayland_server::protocol::wl_data_device::WlDataDevice;
use wayland_server::protocol::wl_data_device_manager::{self, WlDataDeviceManager};
use wayland_server::protocol::wl_data_source::WlDataSource;
use wayland_server::protocol::wl_keyboard::WlKeyboard;
use wayland_server::protocol::wl_output::{self, WlOutput};
use wayland_server::protocol::wl_pointer::WlPointer;
use wayland_server::protocol::wl_region::WlRegion;
use wayland_server::protocol::wl_seat::{self, WlSeat};
use wayland_server::protocol::wl_shm::{self, WlShm};
use wayland_server::protocol::wl_shm_pool::{self, WlShmPool};
use wayland_server::protocol::wl_subcompositor::{self, WlSubcompositor};
use wayland_server::protocol::wl_subsurface::WlSubsurface;
use wayland_server::protocol::wl_surface::{self, WlSurface};
use wayland_server::protocol::wl_touch::WlTouch;
use wayland_server::{Client, DataInit, Dispatch, Display, DisplayHandle, GlobalDispatch, ListeningSocket, New, Resource};

mod dmabuf;

/// Size of the single virtual monitor.
const OUTPUT_WIDTH: i32 = 1920;
const OUTPUT_HEIGHT: i32 = 1080;

/// How often pending frame callbacks are answered; the client renders at most this fast.
const FRAME_INTERVAL: Duration = Duration::from_millis(16);
/// How often client requests are read while idle.
const POLL_INTERVAL: Duration = Duration::from_millis(4);

/// A running compositor. Dropping it disconnects the clients and removes the socket.
pub struct Compositor {
    socket_name: String,
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl Compositor {
    /// Listens on `$XDG_RUNTIME_DIR/<socket_name>` and serves clients on a background thread.
    /// The socket accepts connections as soon as this returns.
    pub fn start(socket_name: &str) -> io::Result<Self> {
        let display = Display::<State>::new().map_err(io::Error::other)?;
        let socket = ListeningSocket::bind(socket_name).map_err(io::Error::other)?;
        let state = State { dmabuf: dmabuf::Dmabuf::detect(), ..State::default() };
        create_globals(&display.handle(), &state);

        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = stop.clone();
        let thread = thread::Builder::new()
            .name("headless-compositor".to_owned())
            .spawn(move || serve(display, socket, state, &stop_flag))?;

        Ok(Self { socket_name: socket_name.to_owned(), stop, thread: Some(thread) })
    }

    /// The value clients need in `WAYLAND_DISPLAY`.
    pub fn socket_name(&self) -> &str {
        &self.socket_name
    }
}

impl Drop for Compositor {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            // A panic on the compositor thread has already been reported by the panic hook.
            let _ = thread.join();
        }
    }
}

fn serve(mut display: Display<State>, socket: ListeningSocket, mut state: State, stop: &AtomicBool) {
    let started = Instant::now();
    let mut last_frame = Instant::now();

    while !stop.load(Ordering::Relaxed) {
        loop {
            match socket.accept() {
                Ok(Some(stream)) => {
                    if let Err(error) = display.handle().insert_client(stream, Arc::new(())) {
                        eprintln!("[compositor] cannot add a client: {error}");
                    }
                }
                Ok(None) => break,
                Err(error) => {
                    eprintln!("[compositor] accept failed: {error}");
                    break;
                }
            }
        }
        if let Err(error) = display.dispatch_clients(&mut state) {
            eprintln!("[compositor] dispatch failed: {error}");
        }
        if last_frame.elapsed() >= FRAME_INTERVAL {
            last_frame = Instant::now();
            let time = started.elapsed().as_millis() as u32;
            for callback in state.frames.drain(..) {
                callback.done(time);
            }
        }
        if let Err(error) = display.flush_clients() {
            eprintln!("[compositor] flush failed: {error}");
        }
        thread::sleep(POLL_INTERVAL);
    }
}

fn create_globals(handle: &DisplayHandle, state: &State) {
    if state.dmabuf.is_some() {
        handle.create_global::<State, ZwpLinuxDmabufV1, ()>(dmabuf::VERSION, ());
    }
    handle.create_global::<State, WlCompositor, ()>(5, ());
    handle.create_global::<State, WlSubcompositor, ()>(1, ());
    handle.create_global::<State, WlShm, ()>(1, ());
    handle.create_global::<State, WlOutput, ()>(4, ());
    handle.create_global::<State, WlSeat, ()>(5, ());
    handle.create_global::<State, WlDataDeviceManager, ()>(3, ());
    handle.create_global::<State, XdgWmBase, ()>(3, ());
}

#[derive(Default)]
struct State {
    /// GPU support offered to clients; `None` on a machine without a render node.
    dmabuf: Option<dmabuf::Dmabuf>,
    /// Frame callbacks of committed surfaces, answered on the next frame tick.
    frames: Vec<WlCallback>,
    serial: u32,
}

impl State {
    fn next_serial(&mut self) -> u32 {
        self.serial = self.serial.wrapping_add(1);
        self.serial
    }
}

#[derive(Default)]
struct Surface {
    pending: Mutex<Pending>,
}

/// State a surface accumulates between two commits, plus the role it was given.
#[derive(Default)]
struct Pending {
    buffer: Option<WlBuffer>,
    frames: Vec<WlCallback>,
    /// A role that still waits for its first `configure`.
    unconfigured: Option<Role>,
}

enum Role {
    Toplevel(XdgSurface, XdgToplevel),
    Popup(XdgSurface, XdgPopup, Placement),
}

/// Where a popup goes relative to its parent, as asked by its positioner.
#[derive(Default, Clone, Copy)]
struct Placement {
    x: i32,
    y: i32,
    width: i32,
    height: i32,
}

#[derive(Default)]
struct Positioner(Mutex<PositionerState>);

#[derive(Default, Clone, Copy)]
struct PositionerState {
    size: (i32, i32),
    anchor: (i32, i32, i32, i32),
    offset: (i32, i32),
}

impl Positioner {
    /// Below the anchor rectangle; enough for a client that only needs its popup to be mapped.
    fn placement(&self) -> Placement {
        let state = *self.0.lock().expect("positioner lock");
        Placement {
            x: state.anchor.0 + state.offset.0,
            y: state.anchor.1 + state.anchor.3 + state.offset.1,
            width: state.size.0,
            height: state.size.1,
        }
    }
}

/// The surface an `xdg_surface` was created for.
type XdgData = WlSurface;

impl Dispatch<WlSurface, Surface> for State {
    fn request(
        state: &mut Self,
        _: &Client,
        _: &WlSurface,
        request: wl_surface::Request,
        surface: &Surface,
        _: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        let mut pending = surface.pending.lock().expect("surface lock");
        match request {
            wl_surface::Request::Attach { buffer, .. } => pending.buffer = buffer,
            wl_surface::Request::Frame { callback } => pending.frames.push(data_init.init(callback, ())),
            wl_surface::Request::Commit => {
                if let Some(buffer) = pending.buffer.take() {
                    // The pixels are never read, so the client can reuse the buffer right away.
                    buffer.release();
                }
                state.frames.append(&mut pending.frames);
                match pending.unconfigured.take() {
                    Some(Role::Toplevel(xdg_surface, toplevel)) => {
                        // 0x0 lets the client pick its own default size.
                        let activated = (xdg_toplevel::State::Activated as u32).to_ne_bytes().to_vec();
                        toplevel.configure(0, 0, activated);
                        xdg_surface.configure(state.next_serial());
                    }
                    Some(Role::Popup(xdg_surface, popup, place)) => {
                        popup.configure(place.x, place.y, place.width, place.height);
                        xdg_surface.configure(state.next_serial());
                    }
                    None => {}
                }
            }
            _ => {}
        }
    }
}

impl Dispatch<XdgSurface, XdgData> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        xdg: &XdgSurface,
        request: xdg_surface::Request,
        surface: &XdgData,
        _: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        let role = match request {
            xdg_surface::Request::GetToplevel { id } => Role::Toplevel(xdg.clone(), data_init.init(id, ())),
            xdg_surface::Request::GetPopup { id, positioner, .. } => {
                let place = positioner.data::<Positioner>().map(Positioner::placement).unwrap_or_default();
                Role::Popup(xdg.clone(), data_init.init(id, xdg.clone()), place)
            }
            _ => return,
        };
        if let Some(data) = surface.data::<Surface>() {
            data.pending.lock().expect("surface lock").unconfigured = Some(role);
        }
    }
}

/// A popup keeps its `xdg_surface` to answer repositioning requests.
impl Dispatch<XdgPopup, XdgSurface> for State {
    fn request(
        state: &mut Self,
        _: &Client,
        popup: &XdgPopup,
        request: xdg_popup::Request,
        xdg: &XdgSurface,
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
        if let xdg_popup::Request::Reposition { positioner, token } = request {
            let place = positioner.data::<Positioner>().map(Positioner::placement).unwrap_or_default();
            popup.repositioned(token);
            popup.configure(place.x, place.y, place.width, place.height);
            xdg.configure(state.next_serial());
        }
    }
}

impl Dispatch<XdgPositioner, Positioner> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &XdgPositioner,
        request: xdg_positioner::Request,
        positioner: &Positioner,
        _: &DisplayHandle,
        _: &mut DataInit<'_, Self>,
    ) {
        let mut state = positioner.0.lock().expect("positioner lock");
        match request {
            xdg_positioner::Request::SetSize { width, height } => state.size = (width, height),
            xdg_positioner::Request::SetAnchorRect { x, y, width, height } => state.anchor = (x, y, width, height),
            xdg_positioner::Request::SetOffset { x, y } => state.offset = (x, y),
            _ => {}
        }
    }
}

impl Dispatch<XdgWmBase, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &XdgWmBase,
        request: xdg_wm_base::Request,
        _: &(),
        _: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            xdg_wm_base::Request::CreatePositioner { id } => {
                data_init.init(id, Positioner::default());
            }
            xdg_wm_base::Request::GetXdgSurface { id, surface } => {
                data_init.init(id, surface);
            }
            _ => {}
        }
    }
}

impl Dispatch<WlCompositor, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &WlCompositor,
        request: wl_compositor::Request,
        _: &(),
        _: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_compositor::Request::CreateSurface { id } => {
                data_init.init(id, Surface::default());
            }
            wl_compositor::Request::CreateRegion { id } => {
                data_init.init(id, ());
            }
            _ => {}
        }
    }
}

impl Dispatch<WlSubcompositor, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &WlSubcompositor,
        request: wl_subcompositor::Request,
        _: &(),
        _: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        if let wl_subcompositor::Request::GetSubsurface { id, .. } = request {
            data_init.init(id, ());
        }
    }
}

impl Dispatch<WlShm, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &WlShm,
        request: wl_shm::Request,
        _: &(),
        _: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        // The pool's file descriptor is dropped here: the memory is never mapped.
        if let wl_shm::Request::CreatePool { id, .. } = request {
            data_init.init(id, ());
        }
    }
}

impl Dispatch<WlShmPool, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &WlShmPool,
        request: wl_shm_pool::Request,
        _: &(),
        _: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        if let wl_shm_pool::Request::CreateBuffer { id, .. } = request {
            data_init.init(id, ());
        }
    }
}

impl Dispatch<WlSeat, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &WlSeat,
        request: wl_seat::Request,
        _: &(),
        _: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_seat::Request::GetPointer { id } => {
                data_init.init(id, ());
            }
            wl_seat::Request::GetKeyboard { id } => {
                data_init.init(id, ());
            }
            wl_seat::Request::GetTouch { id } => {
                data_init.init(id, ());
            }
            _ => {}
        }
    }
}

impl Dispatch<WlDataDeviceManager, ()> for State {
    fn request(
        _: &mut Self,
        _: &Client,
        _: &WlDataDeviceManager,
        request: wl_data_device_manager::Request,
        _: &(),
        _: &DisplayHandle,
        data_init: &mut DataInit<'_, Self>,
    ) {
        match request {
            wl_data_device_manager::Request::CreateDataSource { id } => {
                data_init.init(id, ());
            }
            wl_data_device_manager::Request::GetDataDevice { id, .. } => {
                data_init.init(id, ());
            }
            _ => {}
        }
    }
}

/// Interfaces whose requests need no reaction (destructors are handled by the library).
macro_rules! ignore_requests {
    ($($interface:ty),* $(,)?) => {$(
        impl Dispatch<$interface, ()> for State {
            fn request(
                _: &mut Self,
                _: &Client,
                _: &$interface,
                _: <$interface as Resource>::Request,
                _: &(),
                _: &DisplayHandle,
                _: &mut DataInit<'_, Self>,
            ) {
            }
        }
    )*};
}

ignore_requests!(
    WlBuffer, WlCallback, WlDataDevice, WlDataSource, WlKeyboard, WlOutput, WlPointer, WlRegion, WlSubsurface,
    WlTouch, XdgToplevel,
);

/// Globals that only need their object to be created on bind.
macro_rules! plain_global {
    ($($interface:ty),* $(,)?) => {$(
        impl GlobalDispatch<$interface, ()> for State {
            fn bind(
                _: &mut Self,
                _: &DisplayHandle,
                _: &Client,
                resource: New<$interface>,
                _: &(),
                data_init: &mut DataInit<'_, Self>,
            ) {
                data_init.init(resource, ());
            }
        }
    )*};
}

plain_global!(WlCompositor, WlSubcompositor, WlDataDeviceManager, XdgWmBase);

impl GlobalDispatch<WlShm, ()> for State {
    fn bind(_: &mut Self, _: &DisplayHandle, _: &Client, resource: New<WlShm>, _: &(), data_init: &mut DataInit<'_, Self>) {
        let shm = data_init.init(resource, ());
        shm.format(wl_shm::Format::Argb8888);
        shm.format(wl_shm::Format::Xrgb8888);
    }
}

impl GlobalDispatch<WlSeat, ()> for State {
    fn bind(_: &mut Self, _: &DisplayHandle, _: &Client, resource: New<WlSeat>, _: &(), data_init: &mut DataInit<'_, Self>) {
        let seat = data_init.init(resource, ());
        seat.capabilities(wl_seat::Capability::empty());
        if seat.version() >= 2 {
            seat.name("headless".to_owned());
        }
    }
}

impl GlobalDispatch<WlOutput, ()> for State {
    fn bind(_: &mut Self, _: &DisplayHandle, _: &Client, resource: New<WlOutput>, _: &(), data_init: &mut DataInit<'_, Self>) {
        let output = data_init.init(resource, ());
        output.geometry(
            0,
            0,
            508,
            286,
            wl_output::Subpixel::Unknown,
            "atlas".to_owned(),
            "headless".to_owned(),
            wl_output::Transform::Normal,
        );
        output.mode(wl_output::Mode::Current, OUTPUT_WIDTH, OUTPUT_HEIGHT, 60_000);
        if output.version() >= 4 {
            output.name("HEADLESS-1".to_owned());
        }
        if output.version() >= 2 {
            output.scale(1);
            output.done();
        }
    }
}
