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
rust-core: windows (sctk) · workspaces (Hyprland IPC) · media (MPRIS via playerctl)
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

`Services` (`dev.knshell.services.Services`) is the framework-style entry point: each
service is a small object with a `state: StateFlow` fed from Rust plus its own actions.
Services start **lazily on first access**, so touching `Services.media` subscribes it, and
their UniFFI listener objects stay alive for the process lifetime.

| Service | Flow | Type | Source |
| --- | --- | --- | --- |
| `Services.windows` | `state` | `List<WindowInfo>` | `ext-foreign-toplevel-list-v1` events |
| `Services.workspaces` | `state` | `List<WorkspaceInfo>` | Hyprland event socket |
| `Services.media` | `state` | `MediaState` | `playerctl --follow` (event-driven) |
| `Services.volume` | `state` | `Int` (0–150) | `pactl` poll |
| `Services.system` | `state` | `SystemStats` | `/proc` poll |
| `Services.spectrum` | `state` | `List<Int>` (16 × 0..100) | CAVA raw stream |
| `Services.battery` | `state` | `BatteryState` | `/sys/class/power_supply` poll (2 s) |
| `Services.hyprland` | `activeWindow`, `currentSubmap` | `ActiveWindow?`, `String` | Hyprland event socket + safety refresh |
| `Services.network` | `state` | `NetworkState` (rx/tx rates, totals, interface, `connection_type`, `ssid`, `signal_strength`, `ip_address`) | `/proc/net/dev` on the default-route interface (1 s) + `ip`/`nmcli` details (5 s) |
| `Services.notifications` | `state` (id, app, summary, body, urgency, icon, actions), `doNotDisturb` | `List<NotificationInfo>`, `Boolean` | server: own `org.freedesktop.Notifications` (expiry timers, DND, actions, closed events); observer fallback: `dbus-monitor` |

Workspaces are event-driven: the service listens on
`$XDG_RUNTIME_DIR/hypr/$HYPRLAND_INSTANCE_SIGNATURE/.socket2.sock` and re-reads
`hyprctl -j workspaces` on `workspace>>`, `movewindow>>`, `renameworkspace>>`, … events.
Without the socket it polls every 400 ms; while listening a 2 s safety poll catches missed
events. The instance signature used for `hyprctl` is discovered automatically, so the
polling fallback also works when the shell was started outside the compositor environment.

```kotlin
@Composable
fun MyModule() {
    val volume by Services.volume.state.collectAsState()
    val media by Services.media.state.collectAsState()

    Label("$volume%  ${media.artist} - ${media.title}",
        modifier = Modifier.onClick { Services.media.togglePlayPause() })
}
```

Actions live on the service itself: `Services.volume.setVolume(45)`,
`Services.media.togglePlayPause()`, `Services.workspaces.focus("2")`.

**StateFlow → Compose path:** Rust invokes a UniFFI callback on its own thread → the
the service's listener writes `flow.value` → `collect` in `LaunchedEffect` runs on
`GLibDispatcher` and writes Compose state → the next 16 ms tick calls
`Snapshot.sendApplyNotifications()`, the Recomposer recomposes and the applier calls `gtk_*`
on the GTK thread. The same tick advances animations (`withFrameNanos`): time-based `durationMs` + `Easing`, spring, or a shared `Animate { t -> … }` progress; callbacks
never touch GTK, so UI code needs no locking.

**Notifications have two modes.** If `org.freedesktop.Notifications` is free (the user's
daemon — swaync/mako/dunst — is stopped), the shell **is** the notification server: it serves
the freedesktop spec (`Notify`, `CloseNotification`, `GetCapabilities`,
`GetServerInformation`, plus `KnInvokeAction`) and emits `NotificationClosed` /
`ActionInvoked`. Control API: `Services.notifications.dismiss(id)`,
`invokeAction(id, action)`, `isServer`, `clear()`. If another daemon owns the name (common:
swaync holds it and refuses replacement), the service degrades to a passive observer that
only parses `Notify` calls for history. Expiry timeouts are not implemented yet.

`Services.startAll()` subscribes everything; `Shell.run` calls it, and lazy access already
starts the services a module touches. `Shell.services` refers to `Services`.

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
widgets, data from `Services`, styling from the Modifier DSL, and a few utilities
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
        modifier = Modifier.align(h = Align.Fill).height(40)
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
    val volume by Services.volume.state.collectAsState()

    Row(
        spacing = 6,
        modifier = Modifier.padding(horizontal = 14, vertical = 5)
            .background(Catppuccin.Surface0, radius = 16, shadow = true)
            .margin(horizontal = 5).textColor(Catppuccin.Green),
    ) {
        Icon("\uF028")
        Text(volume.toString() + "%")
        Slider(value = volume.toFloat(), min = 0f, max = 100f, step = 5f) {
            Services.volume.setVolume(it.toInt())
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

Everything the reset removes is styleable with **typed modifiers** (no rawStyle needed):

```kotlin
Button(
    "ok",
    modifier = Modifier
        .padding(horizontal = 10, vertical = 4)
        .minWidth(48)
        .background(Catppuccin.Surface0, radius = 8, transitionMs = 150)
        .hoverBackground(Catppuccin.Surface1)
        .pressedBackground(Catppuccin.Surface3)
        .hoverTextColor(Color.White)
        .focusRing(Catppuccin.Mauve),
) { }

Slider(
    value = 50f,
    modifier = Modifier.width(96).height(16).slider(
        troughColor = Catppuccin.Surface1,
        troughHeight = 6,
        highlightColor = Catppuccin.Green,
        knobColor = Catppuccin.Text,
        knobSize = 12,
    ),
    onValueChange = { },
)

Label("selectable", Modifier.selection(Catppuccin.Base, Catppuccin.Mauve))
```

The reset is installed once after `gtk_init()`, by both `Shell.run` and the legacy
`runComposeWindow`. Implementation: `compose/Reset.kt`.

### Modifier reference (`dev.knshell.compose`)

| Modifier | Effect |
| --- | --- |
| `padding(horizontal, vertical)` / `padding(all)` | CSS padding |
| `margin(horizontal, vertical)` | GTK start/end and top/bottom margins |
| `background(color, radius, shadow, borderWidth, borderColor, transitionMs)` | CSS background, radius, optional shadow/border, CSS transition |
| `radius(px)` | all corners: `border-radius: Npx` (circle: `size / 2`, pill: `height / 2`) |
| `radius(horizontal, vertical)` | elliptical corners: `border-radius: Hpx Vpx` |
| `radius(topLeft, topRight, bottomRight, bottomLeft)` | per-corner radii (clockwise) |
| `radius(Corner(h, v), …)` | per-corner elliptical radii (`Corner(10, 4)`) |
| `hoverBackground(color)` | `:hover` background rule |
| `pressedBackground(color)` / `checkedBackground(color)` / `disabledBackground(color)` | `:active` / `:checked` / `:disabled` background rules |
| `hoverTextColor(color)` / `checkedTextColor(color)` | text colour for `:hover` / `:checked` |
| `focusRing(color, width = 2, offset = 0)` | keyboard focus outline for `:focus-visible` |
| `minWidth(px)` / `minHeight(px)` / `minSize(w, h)` | CSS minimums after the plain reset removed the theme's |
| `maxWidth(px)` / `maxHeight(px)` / `maxSize(w, h)` | cap an **explicit** `width()/height()/size()` request (GTK requests are minimums; natural content cannot be clamped here) |
| `textColor(color, transitionMs = 0)` / `fontSize(px)` / `fontFamily(name)` / `bold()` | CSS text declarations; `transitionMs` animates the text colour |
| `transition(ms)` | native GTK transition for colour-like props (color/background/border/shadow/opacity) |
| `gradient(colors…, angleDeg)` / `radialGradient(colors…)` | CSS gradient background |
| `glow(color, blur, spread)` | coloured box-shadow glow |
| `italic()` / `underline()` / `strikethrough()` | `font-style: italic` / `text-decoration-line` |
| `fontWeight(weight)` | numeric weight: `100` thin, `300` light, `400` normal, `500` medium, `600` semiBold, `700` bold, `800` extraBold, `900` black |
| `thin()` / `light()` / `medium()` / `semiBold()` / `extraBold()` / `black()` | named aliases for the weights above |
| `letterSpacing(px)` | CSS `letter-spacing` (fractional ok) |
| `selection(color, background)` | text-selection colours inside a label |
| `slider(troughColor, troughHeight, highlightColor, knobColor, knobSize)` | typed styling of `GtkScale` trough/highlight/knob |
| `size(w, h)` / `width(w)` / `height(h)` | `gtk_widget_set_size_request` |
| `animatedWidth` / `animatedHeight` / `animatedSize` (`target`, `minMainSize`, `crossSize`, `spacing`) | Compose-owned size animation as a `Modifier` on the container (see `docs/framework-api.md` → Animated size) |
| `align(h = …, v = …)` / `align(Align.…)` | unified alignment on one or both axes |
| `hExpand()` / `vExpand()` / `expand()` | grow + fill on one axis / both |
| `fillWidth()` / `fillHeight()` / `fillAll()` | shorthand for expand + fill |
| `centerH()` / `centerV()` / `centerBoth()` | shorthand for align(Center) |
| `opacity(value)` | `gtk_widget_set_opacity` |
| `clipOverflow()` | `gtk_widget_set_overflow(GTK_OVERFLOW_HIDDEN)` — clip children to the allocation |
| `onClick { … }` | click gesture on any widget (Label, Icon, Box, …) |
| `onHover(onEnter, onExit)` | hover enter/exit callbacks |
| `cursor(name)` / `pointerCursor()` | mouse cursor while hovering (`pointer`, `text`, `default`, …) |
| `rawStyle(selector, declarations)` | escape hatch: arbitrary CSS scoped to the widget |

### Radius examples

```kotlin
Box(Modifier.size(40, 40).background(color).radius(20))              // circle
Box(Modifier.size(120, 28).background(color).radius(14))             // pill
Box(Modifier.background(color).radius(horizontal = 16, vertical = 6)) // elliptical
Box(Modifier.background(color).radius(topLeft = 12, topRight = 0, bottomRight = 12, bottomLeft = 0))
Box(Modifier.background(color).radius(Corner(10, 4), Corner(10), Corner(2), Corner(2)))
```

Later modifiers win on order as usual: `background(color, radius = 8)` overrides an earlier
`radius(12)` when placed after it.

### Text that does not affect layout

GTK measures children, so a `Label` forces its line height onto the parent — a 16px chip
with a 13px label grows to ~17px. Put such content in [Overlay]: `GtkOverlay` measures only
its main child, overlay children are painted on top (centered) and never affect the
measured size. Combine with `clipOverflow()` to clip the overflow.

```kotlin
Overlay(
    Modifier
        .size(16, 16)
        .radius(8)
        .background(Catppuccin.Surface3)
        .clipOverflow(),
) {
    Text("2", size = 10)   // 10px label: drawn inside 16px, does not stretch the chip
}
```

Give the Overlay its size through the Modifier. It deliberately does **not** set
`hexpand`/`vexpand`: `GtkBox` aggregates its children's expand flags, so an expanding
Overlay would make the surrounding box claim extra space and stretch (e.g. a workspace chip
stretched across the whole row). If you *do* want the overlay to fill a parent that already
allocates space, pass `Modifier.align(Align.Fill)` explicitly.

Verified semantics: a `GtkOverlay` whose main child is empty measures 0×0 even with a 17px
overlay label. Use it for badges, counters, icons over fixed-size shapes; keep regular
`Label` where the text *should* size its container.

### Keeping a container width constant

If a child animates its width, the parent (`GtkBox`) width changes every frame. Reserve a
fixed slot per child and animate inside it:

```kotlin
Row(spacing = 0, modifier = Modifier.padding(horizontal = 8, vertical = 4)) {
    list.forEach { workspace ->
        key(workspace.name) {
            Box(Modifier.width(48).height(28)) {   // slot: constant 48px
                WorkspaceChip(workspace)           // chip: 16 → 32 px, centered
            }
        }
    }
}
```

The row width is now `slots * 48 + padding * 2` and never changes when the active item
moves; only the chip inside its slot animates (`Modifier.align(h = Align.Center)` keeps it
centered). This also removes the parent-layout jitter caused by several simultaneous width
animations.

### Text formatting

Whole-label styling uses modifiers (`fontSize`, `fontFamily`, `bold`, `italic`,
`underline`, `strikethrough`, `fontWeight`, `letterSpacing`, `textColor`). Beyond that,
`Label` exposes the GTK text options:

```kotlin
Label(
    text = longTitle,
    modifier = Modifier.fontSize(13).textColor(Grayscale.Foreground),
    wrap = true,          // allow multiple lines
    ellipsize = true,     // truncate with "…" when the width is constrained
    align = 0f,           // 0 = left, 0.5 = center, 1 = right
    maxLines = 2,         // -1 = unlimited (needs wrap)
    maxWidthChars = 26,   // font-aware width clamp + automatic "…"; -1 = off
)
```

For mixed inline styles in one label use Pango markup (`markup = true`, or the `Markup`
helper):

```kotlin
Markup("<b>CPU</b> <span foreground='#e06c75'>54°C</span>  <i>fan</i> 720rpm")
```

Note on truncation: `ellipsize = true` alone does nothing until the label's allocation is
smaller than its natural width. Use `maxWidthChars` — it lowers the label minimum, so Pango
draws "…" at the right place for the current font. Pixel `maxWidth(...)` only caps explicit
`size()` requests (see the layout table above).

Supported markup includes `<b>`, `<i>`, `<u>`, `<s>`, `<big>`, `<small>`, `<tt>`,
`<span foreground=… background=… size=… font_family=…>`. CSS modifiers still apply to the
whole label; GTK CSS has no `line-height` — use Pango `<span>` where needed.

Numbers: `dev.knshell.ui` has `Float.format(decimals)`, `.whole()`, `.percent(decimals)`
(Kotlin/Native has no `String.format`).

## Extending

**Rust service.** Follow the pattern in `rust-core/src/lib.rs`: a `#[derive(uniffi::Record)]`
state struct, a `#[uniffi::export(callback_interface)]` trait (`Send + Sync`), a listener
registry (`OnceLock<Mutex<Vec<Arc<dyn BatteryListener>>>>`), a `broadcast_*` that clones the
list and invokes it, one named thread started on first subscription, and an exported
`subscribe_battery(listener: Box<dyn BatteryListener>)`. Then add a
`ShellService` implementation in `framework/.../services/` (a `MutableStateFlow` in the
object, its UniFFI listener retained, `subscribe_*` in `start()`), register it in
`Services` with `by lazy { … .also { it.start() } }` and collect its `state` in a
composable. Gobley regenerates the `dev.knshell.core` bindings on build.

**Widget node.** Subclass `GtkNode` in `framework/.../compose/Widgets.kt`, implement
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
``Services.*` if it needs live data.

## Limitations and caveats

* **GTK CSS has no `@keyframes`**, so every animation runs in Compose (time-based easing or springs, plus per-frame
  opacity). `transitionMs` only animates properties GTK can transition (`background-color`,
  `color`).
* **One GLib loop per process.** `Shell.run` blocks and must own GTK; there is no second
  runtime, and only `linuxX64` is configured.
* **Layer-shell needs wlroots.** On GNOME/Mutter bars become normal windows, and requested
  `width`/`height` are hints because the compositor stretches anchored surfaces.
* **External tools:** `hyprctl` (workspaces/actions), `pactl` (audio), `playerctl`
  (play/pause), MPRIS/DBus (media).
* **Polling:** media 700 ms, audio 500 ms, system 1 s; only windows and workspaces are
  event-driven (workspace fallback poll 400 ms). Media streams MPRIS changes through
  `playerctl --follow`, so play/pause and metadata reach the UI immediately.
* **Keyboard mode `NONE`** never takes focus; use `ON_DEMAND`/`EXCLUSIVE` for typing.
