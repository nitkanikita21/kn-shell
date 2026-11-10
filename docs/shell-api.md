# KN Shell API

KN Shell is a Quickshell-like Wayland shell: windows and bar modules are declared in
Kotlin, the widget tree is rendered by GTK4, and system integration lives in a Rust
backend exposed through UniFFI. Bar tutorial: [`bar-api.md`](bar-api.md).

## Architecture

```
Shell.run { barWindow { … }; window(…) { … } }        "your shell code"
      │
      ▼
Compose Runtime ── Recomposer · mutableStateOf · Snapshot
      │  GLibDispatcher (g_idle_add) — all GTK calls happen on the GTK thread
      │  GLibFrameClock (16 ms) + Snapshot.sendApplyNotifications() every tick
      ▼
GtkApplier : AbstractApplier<GtkNode>
      GtkBox · GtkCenterBox · GtkLabel · GtkButton · GtkScale · GtkDrawingArea
      ▼
GTK4 + gtk4-layer-shell ── wlr-layer-shell surface (wlroots compositors)
      ▲
      │ UniFFI callback interfaces (called from Rust threads)
rust-core: windows (sctk) · workspaces (Hyprland IPC) · media (MPRIS)
           audio (pactl) · system (/proc)
```

One process owns exactly **one** GTK init, GLib loop, dispatcher, frame clock, Recomposer
and service registry. Windows only add a `Composition`; all recompose on the same frame.

## `Shell.run`

The lambda is a `ShellScope`: it *declares* windows, it is not a composition. Afterwards the
call blocks in the GLib main loop until the last window closes (which quits the loop); with
no declared window it logs and returns. The runtime is created lazily on first use, which
initialises GTK and starts the frame clock. `barWindow` / `window` return a `WindowHandle`
whose `close()` closes that window through GTK's close-request path (useful for toggled
panels).

```kotlin
fun main() = Shell.run {
    barWindow { MyBar() }                     // your layer-shell bar
    window(WindowSpec("Panel", 420, 260)) { MyPanel() }
}
```

## Windows and the bar surface

`WindowSpec(title, width, height, background = null, decorations = true, cssClass = null)`
is a normal toplevel; `background` installs a per-window CSS rule and plain windows get
12 px horizontal / 6 px vertical margins around their root box.

`BarWindowSpec` describes a layer-shell surface; every field has a default, so
`barWindow { MyBar() }` mounts your bar as the production layer surface:

| Field | Default | Meaning |
| --- | --- | --- |
| `title` | `"KN Shell"` | GTK window title |
| `height` / `width` | `40` / `1280` | requested surface size (px) |
| `layer` | `GTK_LAYER_SHELL_LAYER_TOP` | `TOP`, `BOTTOM`, `OVERLAY` or `BACKGROUND` |
| `anchors` | `Top, Left, Right` | edges the surface is pinned to (`BarAnchor.*`) |
| `margins` | `top = 8, left = 10, right = 10, bottom = 0` | per-edge offsets (px) |
| `exclusiveZone` | `ExclusiveZone.Auto` | reserved space: `Auto`, `None`, `Fixed(px)` |
| `keyboardMode` | `GTK_LAYER_SHELL_KEYBOARD_MODE_NONE` | when the surface may take focus |
| `namespace` | `"kn-shell"` | layer namespace in `hyprctl layers` |

```kotlin
barWindow(
    BarWindowSpec(
        height = 36,
        anchors = setOf(BarAnchor.Bottom, BarAnchor.Left, BarAnchor.Right),
        margins = Margins(top = 0, left = 8, right = 8, bottom = 6),
        exclusiveZone = ExclusiveZone.None,           // floating bar
        keyboardMode = GTK_LAYER_SHELL_KEYBOARD_MODE_ON_DEMAND,
    ),
) { BottomBar() }
```

`Auto` reserves exactly the surface height, `None` reserves nothing and `Fixed(px)` reserves an
explicit amount. Layer-shell needs a wlroots compositor (Hyprland / Sway / Niri); on
GNOME/Mutter `gtk_layer_is_supported()` is false and the window degrades to a normal toplevel.
Bars are transparent by default (`window.kn-window.kn-bar`); the kit pills paint the
background.

## Reactive services

`Shell.services` is `dev.knshell.services.ShellServices`; every service is a
`MutableStateFlow` filled from Rust:

| Flow | Type | Source | Latency |
| --- | --- | --- | --- |
| `windows` | `List<WindowInfo>` | `ext-foreign-toplevel-list-v1` events | one event round-trip |
| `workspaces` | `List<WorkspaceInfo>` | Hyprland event socket | ~1 frame |
| `media` | `MediaState` | MPRIS poll | ≤ 700 ms |
| `volume` | `Int` (0–150) | `pactl get-sink-volume` poll | ≤ 500 ms |
| `system` | `SystemStats` | `/proc/stat`, `/proc/meminfo` poll | ≤ 1 s |

Workspaces are event-driven: the service listens on
`$XDG_RUNTIME_DIR/hypr/$HYPRLAND_INSTANCE_SIGNATURE/.socket2.sock` and re-reads
`hyprctl -j workspaces` on `workspace>>`, `movewindow>>`, `renameworkspace>>`, … events.
Without the socket it polls every 400 ms; while listening a 2 s safety poll catches missed
events. The instance signature used for `hyprctl` is discovered automatically, so the
polling fallback also works when the shell was started outside the compositor environment.

```kotlin
@Composable
fun MyModule() {
    var volume by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { Shell.services.volume.collect { volume = it } }
    Label("$volume%")
}
```

**StateFlow → Compose path:** Rust invokes a UniFFI callback on its own thread → the
`ShellServices` listener writes `flow.value` → `collect` in `LaunchedEffect` runs on
`GLibDispatcher` and writes Compose state → the next 16 ms tick calls
`Snapshot.sendApplyNotifications()`, the Recomposer recomposes and the applier calls `gtk_*`
on the GTK thread. The same tick advances spring animations (`withFrameNanos`); callbacks
never touch GTK, so UI code needs no locking.

`ShellServices.start()` subscribes all listeners — `startKnShell()` does it, embedders must
call it before `Shell.run`. Listener objects are retained for the process lifetime to match
the Rust registries. Facade actions: `setVolume(Int)`, `togglePlayPause()`,
`focusWorkspace(name)`.

## Actions

```kotlin
Shell.Hyprland.focusWorkspace("2")                            // workspace chip click
Shell.Hyprland.dispatch("hl.dsp.focus({ workspace = \"2\" })") // raw Lua dispatcher
Shell.Hyprland.spawn("foot")                                  // sh -c, detached
```

Hyprland ≥ 0.56 evaluates dispatchers as Lua, so `dispatch` takes a full expression (legacy
`workspace N` payloads fail there). `focusWorkspace` tries the Lua form first and falls back
to legacy `dispatch workspace <name>`. `spawn` discards stdio, reaps the child in a thread
and never blocks the shell.

## Utilities

The library ships **no bar, no modules and no theme**. Layout comes from the generic
widgets, data from `Shell.services`, styling from the Modifier DSL, and a few utilities
remove boilerplate:

`dev.knshell.ui.primitives`:

| Utility | What it is |
| --- | --- |
| `Space(width, height)`, `HSpace(w)`, `VSpace(h)`, `Spacer(modifier)` | empty boxes for spacing |
| `Icon(glyph, size = 13, color = null)` | Nerd Font glyph |
| `Text(text, size = 13, color = null)` | plain label |

Layout: `Row`, `Column`, `Box`, `CenterBox` from `dev.knshell.compose`.
Colours: the `Catppuccin` palette object (`dev.knshell.compose`); build any other palette
with the Modifier DSL.

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

Services are plain `StateFlow`s — collect them into Compose state yourself:

```kotlin
@Composable
fun Volume() {
    var volume by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { Shell.services.volume.collect { volume = it } }

    Row(
        spacing = 6,
        modifier = Modifier.padding(horizontal = 14, vertical = 5)
            .background(Catppuccin.Surface0, radius = 16, shadow = true)
            .margin(horizontal = 5).textColor(Catppuccin.Green),
    ) {
        Icon("\uF028")
        Text(volume.toString() + "%")
        Slider(value = volume.toFloat(), min = 0f, max = 100f, step = 5f) {
            Shell.services.setVolume(it.toInt())
        }
    }
}
```

### Plain widgets (reset)

Every widget created by the DSL — `Row`, `Column`, `Box`, `CenterBox`, `Label`, `Button`,
`Canvas`, `Slider` — carries the `kn-plain` class. A single global stylesheet at provider
priority **650** strips Adwaita's defaults: backgrounds, borders, box-shadow, outline and
focus ring, padding, margins, min-width/min-height, text shadows, transitions, colour and
font overrides, the `:hover/:active/:checked/:focus/:focus-visible/:disabled/:backdrop`
button states and the `GtkScale` `trough`/`highlight`/`slider` sub-nodes.

It is the GTK analogue of an HTML CSS reset: a widget paints only what your Modifier says.
Per-widget modifier CSS is registered at priority **700** (above the reset), so
`Modifier.background(...)`, `textColor(...)`, `fontSize(...)` always win.

Consequences:

* buttons have no default padding, min-size, hover background or focus ring — set them yourself;
* the `GtkScale` slider/trough are invisible until styled; use `rawStyle(" trough", ...)`,
  `rawStyle(" highlight", ...)`, `rawStyle(" slider", ...)`;
* the focus ring is gone — re-add one with `rawStyle(":focus-visible", "outline: ...")` if the
  widget must stay keyboard-visible.

The reset is installed once after `gtk_init()`, by both `Shell.run` and the legacy
`runComposeWindow`. Implementation: `compose/Reset.kt`.

### Modifier reference (`dev.knshell.compose`)

| Modifier | Effect |
| --- | --- |
| `padding(horizontal, vertical)` / `padding(all)` | CSS padding |
| `margin(horizontal, vertical)` | GTK start/end and top/bottom margins |
| `background(color, radius, shadow, borderWidth, borderColor, transitionMs)` | CSS background, radius, optional shadow/border, CSS transition |
| `hoverBackground(color)` | `:hover` background rule |
| `textColor(color)` / `fontSize(px)` / `fontFamily(name)` / `bold()` | CSS text declarations |
| `size(w, h)` / `width(w)` / `height(h)` | `gtk_widget_set_size_request` |
| `fillMaxWidth()` | `hexpand` + horizontal `GTK_ALIGN_FILL` |
| `centerVertically()` | vertical `GTK_ALIGN_CENTER` |
| `opacity(value)` | `gtk_widget_set_opacity` |
| `rawStyle(selector, declarations)` | extra CSS scoped to the widget (e.g. `" trough"` for `GtkScale`) |

## Extending

**Rust service.** Follow the pattern in `rust-core/src/lib.rs`: a `#[derive(uniffi::Record)]`
state struct, a `#[uniffi::export(callback_interface)]` trait (`Send + Sync`), a listener
registry (`OnceLock<Mutex<Vec<Arc<dyn BatteryListener>>>>`), a `broadcast_*` that clones the
list and invokes it, one named thread started on first subscription, and an exported
`subscribe_battery(listener: Box<dyn BatteryListener>)`. Then add a `MutableStateFlow` to
`ShellServices`, retain the Kotlin listener object, subscribe in `start()` and collect the
flow in a composable. Gobley regenerates the `dev.knshell.core` bindings on build.

**Widget node.** Subclass `GtkNode` in `shell-ui/.../compose/Widgets.kt`, implement
`attachChild`/`detachChild` if it has children, and emit it with `ComposeNode`. The applier
inserts children bottom-up (children before parents), which is what GTK needs.

```kotlin
class GtkSpinnerNode : GtkNode(gtk_spinner_new()!!)

@Composable
fun Spinner(modifier: Modifier = Modifier) = ComposeNode<GtkSpinnerNode, GtkApplier>(
    factory = { GtkSpinnerNode() },
    update = { set(modifier) { m -> applyModifier(m) } },
)
```

**Bar module.** Add a composable in your own file (there are no built-in visual modules)
built from `Row` / `Column` / `Space` / `Icon` / `Text` and the Modifier DSL; collect
`Shell.services.*` if it needs live data.

## Limitations and caveats

* **GTK CSS has no `@keyframes`**, so every animation runs in Compose (springs and per-frame
  opacity). `transitionMs` only animates properties GTK can transition (`background-color`,
  `color`).
* **One GLib loop per process.** `Shell.run` blocks and must own GTK; there is no second
  runtime, and only `linuxX64` is configured.
* **Layer-shell needs wlroots.** On GNOME/Mutter bars become normal windows, and requested
  `width`/`height` are hints because the compositor stretches anchored surfaces.
* **External tools:** `hyprctl` (workspaces/actions), `pactl` (audio), `playerctl`
  (play/pause fallback), MPRIS/DBus (media).
* **Polling:** media 700 ms, audio 500 ms, system 1 s; only windows and workspaces are
  event-driven (workspace fallback poll 400 ms). MPRIS uses the first available player.
* **Keyboard mode `NONE`** never takes focus; use `ON_DEMAND`/`EXCLUSIVE` for typing.
