# KN Shell — Quickshell-like Wayland desktop shell (MVP)

A minimal Wayland desktop shell where:

* the **UI is rendered by native GTK4 widgets**,
* the UI tree, state and animation are driven by the **Compose Runtime** (`org.jetbrains.compose.runtime`),
* all system-level work (Wayland protocols, MPRIS, audio) lives in a **Rust backend**,
* Rust is exposed to Kotlin/Native through **UniFFI**, wired into Gradle by **Gobley**,
* everything is one **Gradle monorepo** — `./gradlew build` and `./gradlew run` do it all.

The shell runs on the author's Hyprland 0.56 / Wayland session and was verified phase by phase
(see [Verification log](#verification-log)).

![Layer-shell panel](docs/screenshots/phase5-panel-layer-shell.png)

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
│  Compose Runtime              GtkNodeApplier        GTK4 + cinterop│
│  ┌────────────────┐      ┌──────────────────┐    ┌───────────────┐│
│  │ Composer /     │─────▶│ Applier<GtkNode> │───▶│ GtkWindow     ││
│  │ Recomposer     │      │ Box/Label/Button │    │ GtkBox        ││
│  │ mutableStateOf │      │ Slider/Canvas    │    │ GtkLabel      ││
│  │ spring anim.   │      │                  │    │ GtkButton     ││
│  └───────▲────────┘      └──────────────────┘    │ GtkDrawingArea││
│          │ GLibFrameClock (16 ms, broadcast)     │ GtkScale      ││
│          │ GLibDispatcher (g_idle_add)           └───────▲───────┘│
│  GtkLayerShell: TOP layer, anchors bottom/left/right, rgba CSS    │
└──────────────────────────────────────────────────────────────────┘
                │ UniFFI (Kotlin/Native ←→ Rust)
┌───────────────┴──────────────────────────────────────────────────┐
│ rust-core (Rust staticlib)                                        │
│   WindowService  ← ext-foreign-toplevel-list-v1 via sctk 0.21     │
│   MediaService   ← MPRIS via mpris 2.1 (blocking libdbus)         │
│   AudioService   ← PipeWire through pipewire-pulse via pactl      │
└──────────────────────────────────────────────────────────────────┘
```

### Modules

| Module | Contents |
|---|---|
| `rust-core/` | Rust crate, UniFFI exports, services, Gobley cargo/uniffi plugins |
| `shell-ui/` | GTK4 C interop, `GtkApplier`, Compose DSL, layer-shell, spring animation, services facade, panel UI |
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

---

## Build & run

```bash
./gradlew build     # Rust (release) → UniFFI bindings → Kotlin/Native → link
./gradlew run       # build and launch the layer-shell panel
./scripts/demo.sh   # build + run with logging
```

### Run modes (`KN_SHELL_MODE`)

| Value | Behaviour |
|---|---|
| *(unset)* / `panel` | **production**: wlr-layer-shell bar (TOP layer, anchored bottom), no regular window |
| `windowed` | same panel in a normal GTK window — safe for testing, does not touch the desktop |
| `demo` | Phase 4 test case: two circles animating with a spring |
| `phase1` | Phase 1 applier smoke test (static Column/Label/Button) |

```bash
# safe test run — a normal window, no layer surface:
KN_SHELL_MODE=windowed ./gradlew run
```

### Verifying from the shell

```bash
hyprctl layers          # kn-shell surface in the TOP layer
pactl set-sink-volume @DEFAULT_SINK@ 40%   # the panel slider follows the sink
```

---

## Verification log

| Phase | Result |
|---|---|
| 0 — monorepo + UniFFI | `./gradlew run` printed `[shell-app] Rust says: Hello from Rust via UniFFI` |
| 1 — Compose→GTK applier | GTK window `KN Shell — Phase 1` opened; a Column/Label/Button rendered from Compose; recomposition ticks logged (`[demo] recomposition tick — clicks=1…7`) |
| 2 — layer shell | `hyprctl layers` → `namespace: kn-shell, xywh: 0 1392 2560 48` in **layer TOP**, translucent CSS |
| 3 — Rust services | live callbacks: 7 toplevel windows with real titles; audio `100% → 45% → 70% → 33%`; MPRIS `player=Mozilla zen playing=true title="…"` |
| 4 — animation | spring animation verified via `composition #30 big=91.5 → #60 big=26.8`, frame clock delivering continuous frames |
| 5 — panel | layer-shell bar with clock, volume slider, media title, play/pause, window count and a pulsing circle; `./gradlew clean build` green |

Screenshots: [`docs/screenshots/`](docs/screenshots) — layer-shell bar, windowed panel,
circle animation, and the headless bar.

### How the composition is driven

1. `GLibDispatcher` — a `CoroutineDispatcher` that posts work with `g_idle_add`, so recomposition runs
   on the GTK main thread.
2. `GLibFrameClock` — a `MonotonicFrameClock` that broadcasts one frame to **all** suspended
   `withFrameNanos` callers every 16 ms via `g_timeout_add`.
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
* The privileged-sandbox environment made a nested Hyprland impossible (aquamarine's DRM backend
  cannot open a seat); testing therefore uses `KN_SHELL_MODE=windowed` or a temporary headless
  monitor.
* Default renderer is GTK's GL/Vulkan renderer; on machines without GL, use `GSK_RENDERER=cairo`.

## Repository layout

```
.
├── gradle/libs.versions.toml     # pinned versions (Kotlin 2.1, Gobley 0.3.7, Compose runtime 1.7.3)
├── rust-core/                    # Rust crate + services
│   ├── src/lib.rs                # UniFFI exports, listeners, records
│   ├── src/windows.rs            # ext-foreign-toplevel-list-v1 (sctk)
│   ├── src/media.rs              # MPRIS
│   └── src/audio.rs              # default sink volume
├── shell-ui/                     # Compose → GTK bridge + panel
│   ├── src/nativeInterop/cinterop/gtk4.def
│   └── src/nativeMain/kotlin/dev/knshell/…
├── shell-app/                    # main()
├── scripts/demo.sh
└── docs/screenshots/
```
