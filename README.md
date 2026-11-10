# KN Shell — Quickshell-like Wayland desktop shell (MVP)

A minimal Wayland desktop shell where:

* the **UI is rendered by native GTK4 widgets**,
* the UI tree, state and animation are driven by the **Compose Runtime** (`org.jetbrains.compose.runtime`),
* all system-level work (Wayland protocols, MPRIS, audio) lives in a **Rust backend**,
* Rust is exposed to Kotlin/Native through **UniFFI**, wired into Gradle by **Gobley**,
* everything is one **Gradle monorepo** — `./gradlew build` and `./gradlew run` do it all.

The shell runs on the author's Hyprland 0.56 / Wayland session and was verified phase by phase
(see [Verification log](#verification-log)).

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│ shell-app (Kotlin/Native executable)                             │
│   main() → startKnShell()                                        │
└───────────────▲──────────────────────────────────────────────────┘
                │
┌───────────────┴──────────────────────────────────────────────────┐
│ shell-ui (Kotlin/Native library)                                 │
│                                                                   │
│  Compose Runtime              GtkApplier            GTK4 + cinterop│
│  ┌────────────────┐      ┌──────────────────┐    ┌───────────────┐│
│  │ Composer /     │─────▶│ Applier<GtkNode> │───▶│ GtkWindow     ││
│  │ Recomposer     │      │ Box/Label/Button │    │ GtkBox        ││
│  │ mutableStateOf │      │ Slider/Canvas    │    │ GtkLabel      ││
│  │ spring anim.   │      │                  │    │ GtkButton     ││
│  └───────▲────────┘      └──────────────────┘    │ GtkDrawingArea││
│          │ GLibFrameClock (16 ms, broadcast)     │ GtkScale      ││
│          │ GLibDispatcher (g_idle_add)           └───────▲───────┘│
│  gtk4-layer-shell: TOP layer, anchors top/left/right, rgba CSS    │
└──────────────────────────────────────────────────────────────────┘
                │ UniFFI (Kotlin/Native ←→ Rust)
┌───────────────┴──────────────────────────────────────────────────┐
│ rust-core (Rust staticlib)                                        │
│  WindowService    ← ext-foreign-toplevel-list-v1 via sctk 0.21   │
│  WorkspaceService ← Hyprland IPC event socket (poll fallback)    │
│  MediaService     ← MPRIS via mpris 2.1 (blocking libdbus)       │
│  AudioService     ← PipeWire through pipewire-pulse via pactl    │
│  SystemService    ← /proc/stat + /proc/meminfo                   │
└──────────────────────────────────────────────────────────────────┘
```

### Modules

| Module | Contents |
|---|---|
| `rust-core/` | Rust crate, UniFFI exports, services, Gobley cargo/uniffi plugins |
| `shell-ui/` | GTK4 C interop, `GtkApplier`, Compose DSL, shell runtime (`Shell.run`), layer-shell, spring animation, reactive services facade, UI primitives (`ui/primitives`) and style helpers (`ui/style`) |
| `shell-app/` | `main()` — starts the shell |

---

## Prerequisites

* Linux x86_64 with a **wlroots-based compositor** — Hyprland, Sway or Niri.
  ⚠️ `gtk4-layer-shell` does **not** work on GNOME/Mutter; the panel falls back to a normal window there.
* GTK 4 development headers and `gtk4-layer-shell` 1.x:
  * Arch: `pacman -S gtk4 gtk4-layer-shell`
  * Fedora: `dnf install gtk4-devel gtk4-layer-shell`
* Rust toolchain **with rustup** (Gobley registers the Cargo target through rustup)
* JDK 17+ to run Gradle (JDK 21 recommended)
* `pkg-config`, `libdbus` (for the MPRIS crate)
* `pactl` (PulseAudio client, works with `pipewire-pulse`) for the audio service
* Network access on first build (Gradle deps, ~1 GB Kotlin/Native toolchain, crates)

This MVP was developed inside an isolated sandbox where user-level caches are read-only, so the
build redirects every cache into the repository:

```bash
source .tools/env.sh   # JAVA_HOME, GRADLE_USER_HOME, KONAN_DATA_DIR, CARGO_HOME, RUSTUP_HOME
```

In a normal environment skip that and use your global toolchains.

### IDE (IntelliJ IDEA) sync

IntelliJ starts Gradle directly (not through the `gradlew` shell script), so shell environment
variables are not applied. The build therefore configures Gobley explicitly:

* `dev.gobley.rust` + `rust { toolchainDirectory = <repo>/.rustup/toolchains/<...>/bin }` makes
  Gobley use the repository toolchain (`cargo`/`rustc`) instead of the system one — Arch's
  `rustc --version` output cannot be parsed by Gobley;
* Gobley's `rustup` invocations are pointed at `scripts/gobley-rustup.sh`, which sets
  `RUSTUP_HOME`/`CARGO_HOME` for the local toolchain and falls back to the system `rustup`.

No IDE Gradle-environment tweaks are needed: `Sync Project with Gradle Files` works out of the box
(with the Gradle JVM set to a supported JDK, e.g. 21).

In *this* checkout the bootstrap is automatic: `gradlew` sources `.tools/env.sh` when it exists,
so a plain `./gradlew run` works without any manual setup (this also pins JDK 21 and makes
`rustup` visible to Gobley). On a fresh clone without `.tools/`, install the prerequisites above
(`rustup` is mandatory for Gobley).

---

## Build & run

```bash
# fast development loop — Kotlin/Native DEBUG binary (no LTO)
./scripts/dev.sh                 # build + run (windowed by default)
./scripts/dev.sh --watch         # rebuild + restart on .kt/.rs changes
KN_SHELL_MODE=panel ./scripts/dev.sh --watch

# release build — whole-program LTO, slower to link but optimized
./scripts/run.sh                 # builds only :shell-app:linkReleaseExecutableLinuxX64
./gradlew run                    # same, but runs the binary under the Gradle daemon

# everything at once (slow: debug + release + tests)
./gradlew build
```

Measured on this machine (one changed Kotlin file): **debug 12 s**, **release 82–95 s** —
release pays for whole-program LTO. `scripts/dev.sh` builds
`linkDebugExecutableLinuxX64` and runs `debugExecutable/shell-app.kexe`; with `--watch` it
polls mtimes every second and restarts the process, so UI edits need no manual commands.
`./gradlew run` executes the binary as a child of the Gradle daemon; `scripts/run.sh` builds
once and `exec`s `releaseExecutable/shell-app.kexe`, so the shell is a normal standalone
process (handy for `hyprctl layers` checks and PID-based cleanup).

### Run modes (`KN_SHELL_MODE`)

| Value | Behaviour |
|---|---|
| *(unset)* / `panel` | **production**: wlr-layer-shell bar (TOP layer, anchored top/left/right), no regular window |
| `windowed` | same panel in a normal GTK window — safe for testing, does not touch the desktop |

```bash
# safe test run — a normal window, no layer surface:
KN_SHELL_MODE=windowed ./gradlew run
```

### Verifying from the shell

```bash
hyprctl layers          # kn-shell surface in the TOP layer
pactl set-sink-volume @DEFAULT_SINK@ 40%   # the volume service follows the sink
```

---

## Verification log

| Phase | Result |
|---|---|
| Phase | Result (historical — the demo modes were removed from the library) |
|---|---|
| 0 — monorepo + UniFFI | `./gradlew run` printed the Rust greeting |
| 1 — Compose→GTK applier | a GTK window rendered a Compose `Column`/`Label`/`Button`; recomposition ticks observed in logs |
| 2 — layer shell | `hyprctl layers` showed `namespace: kn-shell` in layer TOP with translucent CSS |
| 3 — Rust services | live callbacks: toplevel windows, MPRIS metadata, audio `100% → 45% → 70% → 33%` |
| 4 — animation | Compose spring animation verified with a frame counter (`composition #30 → #60`) |
| 5 — panel | layer-shell bar with clock/volume/media modules; `./gradlew clean build` green |

### Shell API

Windows are declared, not assembled by hand:

```kotlin
fun main() = Shell.run {
    barWindow { MyBar() }                                      // wlr-layer-shell bar
    window(WindowSpec("Panel", 420, 260)) { MyPanel() }
}
```

* `Shell.run { … }` mounts every declared window on one shared runtime (one GTK init, one GLib
  loop, one Recomposer) and blocks until the last window closes.
* `Shell.services` publishes the reactive Rust backend as `StateFlow`s: `windows`,
  `workspaces`, `media`, `volume`, `system`.
* `Shell.Hyprland` performs compositor actions: `focusWorkspace(name)`, `dispatch(lua)`,
  `spawn(command)`.
* `dev.knshell.ui.primitives` provides `Space`/`HSpace`/`VSpace`, `Spacer`, `Icon` and
  `Text`; layout comes from `Row`/`Column`/`Box`/`CenterBox`, styling from the Modifier
  DSL, data from `Shell.services`. The library ships no bar, no modules and no theme.

The complete reference — window/bar specs, layers, anchors, margins, exclusive zone,
keyboard mode, service latency, actions, the kit, the modifier table and extension points —
is in [`docs/shell-api.md`](docs/shell-api.md). A focused bar-module tutorial (custom bar,
kit overview) is in [`docs/bar-api.md`](docs/bar-api.md).

### The bar

The shell starts with an **empty surface**: a `CenterBox` with three empty slots and no
theme. Everything visible is yours to compose from generic layout, utilities, the Modifier
DSL and `Shell.services`:

```kotlin
@Composable
fun MyBar() {
    CenterBox(
        modifier = Modifier.fillMaxWidth().height(40)
            .fontFamily("JetBrainsMono Nerd Font").fontSize(13),
        start = { Row(spacing = 6) { Clock() } },
        center = { Space(16) },
        end = { Row(spacing = 6) { Volume() } },
    )
}
```

Styling is expressed **in Kotlin with a Compose-like `Modifier` chain** — there are no
per-module CSS classes:

```kotlin
Label(
    "12:30",
    modifier = Modifier
        .padding(horizontal = 14, vertical = 5)
        .background(Catppuccin.Surface0, radius = 16, shadow = true, transitionMs = 200)
        .margin(horizontal = 5)
        .textColor(Catppuccin.Flamingo)
        .fontSize(13),
)
```

`Modifier` maps to native GTK calls (margins, size, opacity, expand/align) plus a per-widget
style provider for background/border/padding/font declarations; `rawStyle(selector, css)` is
an escape hatch for widget internals (e.g. `GtkScale` trough/slider). Window-level CSS is
generated from `runComposeWindow` parameters.

Data comes from `Shell.services` (plain `StateFlow`s): collect a flow in a `LaunchedEffect`
and read the Compose state. An empty bar surface falls back to GTK's default height; add
modules and it sizes to the content.

When the real waybar is running, the layer surface is placed below it (exclusive zone); stop
waybar to see the bar at the very top edge.

### How the composition is driven

1. `GLibDispatcher` — a `CoroutineDispatcher` that posts work with `g_idle_add`, so recomposition runs
   on the GTK main thread.
2. `GLibFrameClock` — a `MonotonicFrameClock` that broadcasts one frame to **all** suspended
   `withFrameNanos` callers every 16 ms via `g_timeout_add`. Every tick also calls
   `Snapshot.sendApplyNotifications()`: Compose observes state writes through snapshot apply
   notifications, which usually the platform UI loop sends. Without it the Recomposer freezes
   after the initial composition (Rust service updates, clocks and animations stop reaching the UI).
3. `GtkApplier` — an `AbstractApplier<GtkNode>` implementing bottom-up insertion, which maps
   Compose nodes onto `GtkBox` children.
4. Composable state (clock text, volume, MPRIS title, animated radii) is read **during composition**,
   so changes schedule recomposition, and the applier calls `gtk_*` setters / `gtk_widget_queue_draw`.

---

## Deviations from the original specification (important)

1. **`gtk-kn` / `compose-gtk4` could not be used as a dependency.**
   `gtk-kn` is archived on GitHub (read-only mirror of a GitLab project), is **not published to any
   Maven repository** (only buildable from source into `mavenLocal`), and **does not contain a
   `compose-gtk4` module** (there is only an unmerged `feature/84-compose` branch).
   Instead of vendoring ~13 generated binding modules, `shell-ui` declares a **C interop against the
   system GTK4 + gtk4-layer-shell** (include paths from `pkg-config`, linker options in `gtk4.def`)
   and implements `GtkNodeApplier` directly — exactly the Phase 1 task ("implement or adapt the
   applier"). GTK 4.22 is installed, newer than what gtk-kn targets.
2. **Compose `animation` / `animation-core` are not published for `linuxX64`.**
   Only `org.jetbrains.compose.runtime:runtime` ships a linuxX64 klib, so `animateFloatAsState`,
   `AnimatedContent` and `spring()` from the official artifact are unavailable. `shell-ui` therefore
   ships a small spring implementation on top of `withFrameNanos`
   (`shell-ui/.../animation/Spring.kt`), preserving the same semantics.
3. **Audio uses `pactl` instead of `pipewire-rs`/libpulse bindings.**
   PipeWire's `pipewire-pulse` server speaks the PulseAudio protocol, so `pactl` observes and sets
   the default sink. This avoids ~300 lines of unsafe libpulse mainloop integration for the MVP;
   the transport is the same. Documented in `rust-core/src/audio.rs`.
4. **`shell-contract` is not a separate Gradle module.** Gobley generates the UniFFI bindings into
   `rust-core/shell-contract`-style output (`rust-core/build/generated/uniffi/...`, package
   `dev.knshell.core`); the Kotlin API is consumed via `project(":rust-core")`. A separate hop would
   require manually re-exporting generated code.
5. **Kotlin/Native links with `--allow-shlib-undefined`** (see `shell-app/build.gradle.kts`):
   the bundled sysroot is older than the host glibc that GTK 4.22 needs; symbols resolve against the
   system glibc at runtime.

All versions are pinned in `gradle/libs.versions.toml` and `rust-core/Cargo.toml`; `Cargo.lock` is
committed.

---

## UniFFI memory model

* Rust service state lives in process-global registries (`OnceLock<Mutex<Vec<Arc<dyn Listener>>>>`);
  callbacks are `Arc`-based and `Send + Sync`.
* Rust → Kotlin callbacks run on Rust-owned threads. Kotlin/Native's new memory model makes the
  cross-thread call safe; callbacks only write to `MutableStateFlow`/Compose state, never touch GTK.
* GTK calls happen exclusively on the GLib main thread (recomposer + applier are dispatched there).
* Kotlin keeps every callback object in `ShellServices.retained`, and every GTK node keeps its
  `StableRef` (`selfRef`) for the lifetime of the widget — otherwise the GC frees the native handle
  and GTK would call a dangling pointer.

## Known limitations

* Layer-shell is ignored on GNOME/Mutter (protocol not implemented there); the app degrades to a
  normal window.
* `gtk4-layer-shell` 1.x API is assumed (the panel uses `gtk_layer_*` from 1.0+).
* Only `linuxX64` is configured.
* The audio service polls `pactl` every 500 ms (no event subscription yet).
* MPRIS support is polling-based (700 ms) and uses the first available player.
* Toplevel windows and workspaces are event-driven (sctk `ext-foreign-toplevel-list-v1`,
  Hyprland event socket `.socket2.sock`). Workspaces fall back to a 400 ms poll when the event
  socket is unavailable and run a 2 s safety poll while listening; the service discovers the
  instance signature itself, so it also works when the shell starts outside the compositor
  environment.
* Hyprland ≥ 0.5x evaluates dispatchers as Lua (`hl.dsp.focus({ workspace = "…" })`);
  `focus_workspace` uses that spelling and falls back to the legacy `dispatch workspace` form on older releases.
* The privileged-sandbox environment made a nested Hyprland impossible (aquamarine's DRM backend
  cannot open a seat); testing therefore uses `KN_SHELL_MODE=windowed` or a temporary headless
  monitor.
* Default renderer is GTK's GL/Vulkan renderer; on machines without GL, use `GSK_RENDERER=cairo`.

## Repository layout

```
.
├── gradle/libs.versions.toml     # pinned versions (Kotlin 2.1, Gobley 0.3.7, Compose runtime 1.7.3)
├── rust-core/                    # Rust crate + services
│   ├── src/lib.rs                # UniFFI exports, listeners, records, actions
│   ├── src/windows.rs            # ext-foreign-toplevel-list-v1 (sctk)
│   ├── src/workspaces.rs         # Hyprland IPC (event socket + polling fallback)
│   ├── src/media.rs              # MPRIS
│   ├── src/audio.rs              # default sink volume (pactl)
│   └── src/system.rs             # CPU / memory from /proc
├── shell-ui/                     # Compose → GTK bridge, shell runtime, primitives
│   ├── src/nativeInterop/cinterop/gtk4.def
│   └── src/nativeMain/kotlin/dev/knshell/
│       ├── compose/              # applier, Modifier DSL, widgets, frame clock
│       ├── shell/                # Shell.run, ShellScope, ShellRuntime
│       ├── ui/primitives/        # Space/HSpace/VSpace, Spacer, Icon, Text
│       └── services/             # ShellServices facade over the Rust backend
├── shell-app/                    # main()
├── scripts/dev.sh, scripts/run.sh, scripts/gobley-rustup.sh
└── docs/shell-api.md, docs/bar-api.md
```
