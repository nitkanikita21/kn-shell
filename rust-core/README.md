# rust-core

Rust backend of the KN Shell. Everything here is system integration — the compositor,
the session bus, `/sys`, PipeWire, the network stack — plus the C layout manager the
Kotlin/Compose DSL packs `Row`/`Column`/`Box` with. There is no UI in this crate.

Exposed to Kotlin/Native through **UniFFI proc-macro mode**; the generated Kotlin lives
in package `dev.knshell.core` (see `framework/src/nativeMain/kotlin/dev/knshell/services/`
for the thin reactive wrappers the UI actually talks to).

## Contract

* Records (`types`), callback interfaces (`listeners`) and export functions (`api`) are the
  public surface. Everything else is `pub(crate)`.
* UniFFI exports must stay `pub` and reachable from the crate root — `lib.rs` re-exports
  them (`pub use api::*; pub use types::*; pub use listeners::{…}`), so moving an item into
  another module never changes the Kotlin side.
* One background thread per service, started lazily on first subscription; listeners are
  held in a process-global `OnceLock<Mutex<Vec<Arc<dyn …>>>>`. Callbacks run on the service
  thread, never on the GTK main loop.

## Files

| File | Contents |
| --- | --- |
| `src/lib.rs` | crate docs, `mod` tree, `uniffi::setup_scaffolding!()`, re-exports — the facade |
| `src/types.rs` | `uniffi::Record` value types shared with Kotlin |
| `src/listeners.rs` | `callback_interface` traits, their registries and `publish_*` helpers |
| `src/api.rs` | `subscribe_*` / action functions (`toggle_play_pause`, `hyprctl_dispatch`, `spawn_command`, `focus_workspace`, `set_volume`, …) |
| `src/windows.rs` | toplevel list via `ext-foreign-toplevel-list-v1` (smithay + wayland-client) |
| `src/workspaces.rs` | Hyprland workspaces and submap over its IPC socket |
| `src/hyprland.rs` | raw Hyprland dispatcher (`hyprctl`/IPC Lua) |
| `src/media.rs` | MPRIS player state and commands (D-Bus) |
| `src/audio.rs` | default-sink volume through `pactl` |
| `src/network.rs` | throughput from `/proc/net/dev` |
| `src/system.rs` | CPU / memory percentages |
| `src/battery.rs` | charge + status from `/sys/class/power_supply` |
| `src/spectrum.rs` | `cava` audio bars |
| `src/notifications/` | `mod.rs` (module docs, exports), `dbus.rs` (server on the session bus), `store.rs` (accepted notifications), `watch.rs` (observer for foreign notifications) |
| `src/custom.rs` | extension host (`CustomExtension` / `register_extension`), used by `palette-core` under its `custom-host` feature |
| `src/box_layout.rs`, `src/kn_box_layout.c` | the GTK `GtkLayoutManager` the DSL's boxes are packed with |

## Build & test

```sh
cd rust-core && cargo test        # compiles the crate, 1 ignored doctest
cd ../palette-core && cargo test  # consumes `custom` through its bridge
```

Gradle drives the real build (`:rust-core:*` via Gobley); the static library
(`target/x86_64-unknown-linux-gnu/debug/libkn_shell_core.a`) is linked into the
Kotlin/Native executable by `shell-app`.

## Known limitations

* `audio` shells out to `pactl` (documented in `src/audio.rs`) instead of linking libpulse.
* `hyprland` / `workspaces` assume Hyprland's IPC; other compositors get the toplevel list
  only.
