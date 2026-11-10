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
    barWindow { TopBar() }
    window(WindowSpec("Dashboard", 420, 260)) { DashboardExample() }
}
```

## Windows and the bar surface

`WindowSpec(title, width, height, background = null, decorations = true, cssClass = null)`
is a normal toplevel; `background` installs a per-window CSS rule and plain windows get
12 px horizontal / 6 px vertical margins around their root box.

`BarWindowSpec` describes a layer-shell surface; every field has a default, so
`barWindow { TopBar() }` already produces the production bar:

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

## The bar kit (`dev.knshell.ui.kit`)

`BarTheme` carries every colour and metric. `BarTheme.Mocha` (also `BarTheme.Default`) is
the Catppuccin reference palette: `surface`, `text`, `accent`, `blue`, `green`, `red`,
`mauve`, `pink`, `flamingo`, `sapphire`, `sky`, plus `radius = 16`, `pillPaddingH = 14`,
`pillPaddingV = 5`, `gap = 5`, `fontFamily = "JetBrainsMono Nerd Font"`, `fontSize = 13`.
Re-skin a whole bar with a copy:

```kotlin
Bar(BarTheme.Mocha.copy(radius = 10, gap = 3), start = {…}, center = {…}, end = {…})
```

Modifiers: `Modifier.pill(theme, color = theme.surface, radius)` = themed padding + rounded
background with shadow, 200 ms colour transition and horizontal margin;
`Modifier.chip(active, theme, width, height)` = workspace chip (mauve, bold 10 px when
active; small round dot otherwise; hover accent); `Modifier.pillText(theme, color, size,
bold)` = matching text style. Example: `Label("\uF0AC", Modifier.pill(theme).pillText(theme,
color = theme.blue))`.

| Component | Renders |
| --- | --- |
| `Bar(theme, start, center, end)` | `CenterBox` + service subscriptions + theme font |
| `Pill(color, theme, spacing, onClick, content)` | themed pill; `onClick` makes it a button without changing layout |
| `Spacer(modifier)` / `Icon(glyph, size = 13)` / `ValueText(text, theme, color, size, bold)` | layout and text primitives |
| `AnimatedPercent(target, theme, color, format, size, stiffness, dampingRatio)` | spring-animated integer (`"42%"` by default) |
| `EqualizerBars(playing, bars = 5, width = 24, height = 14, activeColor, idleColor)` | cairo equalizer, new spring targets every 140 ms (white / `#F5C2E6`) |
| `MusicPill(theme)` | title + equalizer; blue while playing, click toggles play/pause |
| `UpdatesPill(theme, count = 0)` | static updates counter |
| `WorkspaceStrip(theme)` | animated chips; click focuses the workspace |
| `VolumePill(theme)` | 0–100 slider (step 5) + animated percentage |
| `MetricPill(icon, value, color, theme)` | icon + animated percentage (CPU, memory, …) |
| `ClockPill(theme)` | day/weekday, blinking colon, bold minutes |
| `PowerPill(theme, onClick = {})` | power glyph; no-op by default |

`Bar` subscribes to workspaces, media, volume and system, ticks a 1 s clock and writes the
shared `BarState`, which `MusicPill`, `WorkspaceStrip`, `VolumePill` and `ClockPill` read.
`BarState` is `internal` to `shell-ui`; code outside that module collects `Shell.services`
itself (see `DashboardExample`).

```kotlin
@Composable
fun BatteryPill(percent: Int, theme: BarTheme = BarTheme.Mocha) = Pill(theme = theme) {
    Row(spacing = 4, modifier = Modifier.textColor(theme.green)) {
        Icon("\uF240", size = 14)
        AnimatedPercent(percent, theme, theme.green, size = 12)
    }
}

@Composable
fun MyBar() = Bar(
    theme = BarTheme.Mocha,
    start = { MusicPill() },
    center = { WorkspaceStrip() },
    end = {
        BatteryPill(82); VolumePill()
        ClockPill(); PowerPill { Shell.Hyprland.spawn("wlogout") }
    },
)
```

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

**Bar component.** Add a composable in `ui/kit/Components.kt` built from `Pill` / `Icon` /
`ValueText` / `Modifier`; read `BarState` if it needs live bar data, and take colours from
`BarTheme` so custom themes keep working.

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
