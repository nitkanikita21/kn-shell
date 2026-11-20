//! Toplevel window list via `ext-foreign-toplevel-list-v1`.
//!
//! Uses `smithay-client-toolkit`'s high-level `ForeignToplevelList` helper, which
//! handles the protocol dispatch; we only react to new/updated/closed toplevels and
//! re-broadcast the whole list.

use std::sync::OnceLock;

use smithay_client_toolkit::{
    delegate_registry,
    foreign_toplevel_list::{ForeignToplevelList, ForeignToplevelListHandler},
    registry::{ProvidesRegistryState, RegistryState},
    registry_handlers,
};
use wayland_client::{globals::registry_queue_init, Connection, QueueHandle};
use wayland_protocols::ext::foreign_toplevel_list::v1::client::ext_foreign_toplevel_handle_v1::ExtForeignToplevelHandleV1;

use crate::{publish_windows, WindowInfo};

static STARTED: OnceLock<()> = OnceLock::new();

struct WindowWatcher {
    registry_state: RegistryState,
    toplevels: ForeignToplevelList,
    dirty: bool,
}

impl WindowWatcher {
    fn snapshot(&self) -> Vec<WindowInfo> {
        self.toplevels
            .toplevels()
            .iter()
            .filter_map(|handle| self.toplevels.info(handle))
            .map(|info| WindowInfo { title: info.title, app_id: info.app_id })
            .collect()
    }
}

impl ProvidesRegistryState for WindowWatcher {
    fn registry(&mut self) -> &mut RegistryState {
        &mut self.registry_state
    }

    registry_handlers! {}
}

impl ForeignToplevelListHandler for WindowWatcher {
    fn foreign_toplevel_list_state(&mut self) -> &mut ForeignToplevelList {
        &mut self.toplevels
    }

    fn new_toplevel(
        &mut self,
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
        _handle: ExtForeignToplevelHandleV1,
    ) {
        self.dirty = true;
    }

    fn update_toplevel(
        &mut self,
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
        _handle: ExtForeignToplevelHandleV1,
    ) {
        self.dirty = true;
    }

    fn toplevel_closed(
        &mut self,
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
        _handle: ExtForeignToplevelHandleV1,
    ) {
        // sctk removes the toplevel from the list right after this callback returns,
        // so the actual re-broadcast happens in the event loop below.
        self.dirty = true;
    }
}

delegate_registry!(WindowWatcher);
smithay_client_toolkit::delegate_dispatch2!(WindowWatcher);

pub(crate) fn ensure_started() {
    STARTED.get_or_init(|| {
        std::thread::Builder::new()
            .name("kn-window-listener".into())
            .spawn(|| {
                if let Err(error) = run() {
                    eprintln!("[rust/windows] watcher stopped: {error}");
                }
            })
            .expect("failed to spawn window listener thread");
    });
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let connection = Connection::connect_to_env()?;
    let (globals, mut event_queue) = registry_queue_init(&connection)?;
    let qh = event_queue.handle();

    let registry_state = RegistryState::new(&globals);
    let toplevels = ForeignToplevelList::new(&globals, &qh);

    let mut state = WindowWatcher { registry_state, toplevels, dirty: true };

    loop {
        event_queue.blocking_dispatch(&mut state)?;
        if state.dirty {
            state.dirty = false;
            publish_windows(state.snapshot());
        }
    }
}
