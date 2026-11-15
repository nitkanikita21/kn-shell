# Writing UI on top of the shell

The library ships **no bar, no modules and no theme** — only mechanics. You compose the
bar yourself from generic layout, utilities, the Modifier DSL and reactive services.

## Building blocks

| Piece | From |
| --- | --- |
| layout | `Row`, `Column`, `Box`, `CenterBox` — `dev.knshell.compose` |
| utilities | `Space`, `HSpace`, `VSpace`, `Spacer`, `Icon`, `Text` — `dev.knshell.ui.primitives` |
| styling | Modifier DSL (`padding`, `margin`, `background`, `textColor`, `fontSize`, …) + `Catppuccin` palette |
| data | `Services` — `StateFlow`s (`Services.workspaces.state`, `Services.media.state`, …) |
| runtime | `Shell.run { barWindow { … } }` |

## A clock module

```kotlin
@Composable
fun Clock() {
    var parts by remember { mutableStateOf(currentClockParts()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            parts = currentClockParts()
        }
    }

    Row(
        spacing = 6,
        modifier = Modifier
            .padding(horizontal = 14, vertical = 5)
            .background(Catppuccin.Surface0, radius = 16, shadow = true, transitionMs = 200)
            .margin(horizontal = 5)
            .textColor(Catppuccin.Flamingo),
    ) {
        Icon("\uF017")
        Text(parts.first)
        Text(parts.second)
        Text(parts.third)
    }
}
```

`currentClockParts()` is a small utility in `dev.knshell.ui` that returns
`(date, ":", minutes)` so the colon can be animated separately.

## A volume module

```kotlin
@Composable
fun Volume() {
    var volume by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { Services.volume.state.collect { volume = it } }

    Row(
        spacing = 6,
        modifier = Modifier
            .padding(horizontal = 14, vertical = 5)
            .background(Catppuccin.Surface0, radius = 16, shadow = true)
            .margin(horizontal = 5)
            .textColor(Catppuccin.Green),
    ) {
        Icon("\uF028")
        Slider(
            value = volume.toFloat(),
            min = 0f,
            max = 100f,
            step = 5f,
            modifier = Modifier
                .width(64)
                .height(16)
                .slider(
                    troughColor = Catppuccin.Surface1,
                    troughHeight = 6,
                    highlightColor = Catppuccin.Green,
                    knobColor = Catppuccin.Text,
                    knobSize = 12,
                ),
        ) { Services.volume.setVolume(it.toInt()) }
        Text(volume.toString() + "%")
    }
}
```

### Synchronised animations

Independent `animateFloatAsState` calls each own a frame loop: starting them from the same
recomposition is usually in phase, but nothing guarantees it. When several values must move
together (an indicator sliding between slots, a pill growing while its colour changes),
derive them from **one shared progress**:

```kotlin
Animate(active = workspace.active, durationMs = 180) { t ->
    Box(
        Modifier
            .size(lerp(28, 40, t), 28)
            .radius(14)
            .background(lerpColor(Catppuccin.Surface3, Catppuccin.Mauve, t)),
    ) {
        Text(workspace.name)
    }
}
```

`Animate` drives exactly one `withFrameNanos` loop and one easing curve; `lerp` / `lerpColor`
read the same `t`, so every value is in phase by construction — no drift, no per-value
spring/overshoot. This is the QML `ParallelAnimation` idea as data.

Hoist the progress to a common ancestor when siblings must share it (e.g. a highlight pill
sliding between fixed slots: `x = lerp(oldIndex, newIndex, t)`).

Tip: colours can also be left to GTK's `transitionMs`; use the shared `t` for geometry and
let the CSS transition handle colour, which avoids one CSS update per frame.

### Opt-in size behavior (`Behavior on width`)

Attach `widthBehavior(...)` (or `heightBehavior` / `sizeBehavior`) to the widget you want,
exactly like QML `Behavior on width`:

```kotlin
// explicit target
Box(
    Modifier
        .widthBehavior(durationMs = 180, easing = Easing.OutCubic)
        .size(if (active) 34 else 24, 24),
)

// content-driven width — no size() needed, works like QML too
Row(
    Modifier
        .widthBehavior()
        .padding(horizontal = 14),
) {
    CavaEqualizer(width = 80, height = 20)
    Label(title)
}
```

Two modes:

* **explicit** — a `size()`/`width()`/`height()` target changes and is tweened;
* **auto** — no explicit size in the chain: the animator measures the natural size
  (`gtk_widget_get_preferred_size` with the size request temporarily lifted, so the
  measurement is not clamped) and tweens to it. It probes every 6 frames when idle and
  every frame while tweening.

The tween runs on the widget node (plain GTK calls, no Compose recomposition). The first
application snaps to the initial value; removing the widget cancels its tween. Colours are
better handled by `transition(ms)` / `transitionMs` (native GTK transitions).

**GTK limitation of the auto mode.** `gtk_widget_set_size_request` is a *minimum*, so the
Modifier can animate shrinking (request above the natural width) but **not growth**: when
content grows, its natural width wins and the widget jumps. For smooth animation in both
directions use the container, which disables natural-size propagation:

```kotlin
AnimatedWidth(
    durationMs = 200,
    modifier = Modifier.radius(8).background(Grayscale.Surface).padding(horizontal = 14),
) {
    if (playing) CavaEqualizer(width = 80, height = 20) else Icon("\uF04C")
    Label(title)
}
```

`AnimatedBox(vertical = false|true, spacing = …, axis = AnimatedAxis.Width|Height)` is the
general form: it is a full Row/Column container (orientation, spacing, any number of
children) whose main axis animates. `AnimatedWidth { … }` and `AnimatedHeight { … }` are
the short aliases.

It is backed by a native **`GtkLayoutManager` implemented in C**
(`rust-core/src/kn_animated_size_layout.c`): `measure()` reports the animated width as both
minimum and natural, so the parent allocates exactly that value — growth animates just like
shrinking, which a `size_request` cannot express. The child keeps its natural size and the
container clips it (`overflow: hidden`), so content is revealed instead of being reflowed on
every frame. The container is the styled surface (background/radius/padding go on it).

The layout manager is C on purpose: subclassing `GtkLayoutManager` through `gtk4-rs` inside
the Kotlin/Native process corrupted the GObject type state (widgets became invalid right
after attaching it), while `G_DEFINE_TYPE` has neither the double-initialisation problem nor
the Rust subclass wrappers.

### Gradients and effects

Static gradients are native GTK CSS (no frame loop):

```kotlin
Box(Modifier.size(120, 32).radius(16).gradient(Grayscale.Background, Grayscale.Accent, angleDeg = 90f))
Box(Modifier.size(32, 32).radius(16).radialGradient(Grayscale.Accent, Grayscale.Background))
Box(Modifier.size(120, 32).radius(16).glow(Grayscale.Accent, blur = 16, spread = 2))
```

Animated effects (shimmer, moving gradient, pulse) are done with `Canvas` + cairo, which
gives float geometry and real per-frame control:

```kotlin
@Composable
fun ShimmerPill() {
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { phase = (phase + 0.012f) % 1f }
    }

    Canvas(160, 32) { cr, w, h ->
        val pattern = cairo_pattern_create_linear(0.0, 0.0, w.toDouble(), 0.0)
        val mid = phase.coerceIn(0.05f, 0.95f)
        cairo_pattern_add_color_stop_rgba(pattern, 0.0, 0.08, 0.08, 0.08, 1.0)
        cairo_pattern_add_color_stop_rgba(pattern, mid.toDouble(), 0.81, 0.81, 0.81, 0.55)
        cairo_pattern_add_color_stop_rgba(pattern, 1.0, 0.08, 0.08, 0.08, 1.0)
        cairo_set_source(cr, pattern)
        cairo_paint(cr)
        cairo_pattern_destroy(pattern)
    }
}
```

Tips:

* a gradient respects `radius(...)` clipping on the same widget (GTK clips the background);
* animate `glow` colour with `transition(ms)` instead of a frame loop — GTK interpolates
  `box-shadow` natively;
* per-frame CSS gradient changes are expensive; for animation use `Canvas`, not
  `gradient(...)`;
* true blur/glow shaders (`GskGLShader` / `gtk_snapshot_push_gl_shader`) are possible but
  require the GL renderer and a custom GSK node — not wired up yet.

### Popovers (hover / click / manual)

`Popover` anchors a real `GtkPopover` to its trigger. Three ways to open it (hover and click
can be combined):

```kotlin
// tooltip-like: opens on hover
Popover(content = { InfoPanel() }) { Icon("\uF028", modifier = Modifier.pointerCursor()) }

// notification centre: opens on click, closes on outside click
Popover(openOnHover = false, openOnClick = true, content = { NotificationPanel() }) { Bell() }

// manual control
val menu = remember { PopoverController() }
Popover(openOnHover = false, openOnClick = false, controller = menu,
        content = { MenuPanel() }) { Icon("\uF0C9") }
// menu.open() / menu.close() / menu.toggle() / menu.isOpen
```

The content is a **separate Compose composition** mounted when the popover opens (and
disposed when it closes, so the next open negotiates its size from scratch). The surface is
fully reset (transparent `popover`/`contents`/`arrow`, no border, shadow, padding or arrow),
so the content root owns background/padding/radius. `KN_POPOVER_DEBUG=1` pops each popover
once at startup for layout checks (only one popover is visible at a time in normal use —
with the debug flag several open together, which GTK flags as a benign critical).

### Per-island surfaces (recommended for expanding islands)

Instead of one full-width bar surface, give an island its **own** layer-shell surface: the
surface is then exactly the island, so growing it cannot block clicks around it and needs no
input-region tricks.

```kotlin
Shell.run {
    // bar surface: side islands only, reserves the top edge
    val bar = barWindow(
        BarWindowSpec(height = 40, anchors = setOf(BarAnchor.Top, BarAnchor.Left, BarAnchor.Right),
            exclusiveZone = ExclusiveZone.Fixed(40), namespace = "my-bar"),
    ) { KnBar() }

    // centre island: natural size (width = -1, height = -1), centred by its left margin
    val center = barWindow(
        BarWindowSpec(width = -1, height = -1, anchors = setOf(BarAnchor.Top, BarAnchor.Left),
            margins = Margins(top = 0, left = (displayWidth() - 245) / 2, right = 0, bottom = 0),
            exclusiveZone = ExclusiveZone.None, namespace = "kn-center"),
    ) { CenterIsland() }
}
```

Inside the island, `AnimatedBox(vertical = true, mainSize = if (open) 400 else 40,
crossSize = 245)` animates its own height and clips the notification centre; the window is
resized to match (`WindowHandle.resize(height = …)`). No `wl_surface` input region is needed:
everything outside the island's surface belongs to other clients.

### Notification card

Cards are just composition — `Services.notifications.state` gives id, app, summary, body,
urgency, icon path and actions:

```kotlin
@Composable
fun NotificationCard(n: NotificationInfo) {
    Row(spacing = 10, modifier = Modifier.width(340).padding(8).background(Grayscale.Background, radius = 8)) {
        if (n.icon.startsWith("/")) {
            Image(n.icon, Modifier.radius(6), width = 28, height = 28)
        } else {
            Icon("\uDB84\uDD6B", size = 18, color = Grayscale.Accent)
        }
        Column(spacing = 3, modifier = Modifier.width(258)) {
            Label("${n.app}  ${n.summary}", modifier = Modifier.textColor(Grayscale.Foreground).fontWeight(600))
            if (n.body.isNotBlank()) Label(n.body, modifier = Modifier.textColor(Grayscale.Accent))
            if (n.actions.isNotEmpty()) {
                Row(spacing = 10) {
                    n.actions.forEach { a ->
                        Label(a.label, modifier = Modifier.textColor(ACTION_COLOR)
                            .onClick { Services.notifications.invokeAction(n.id, a.key) }
                            .pointerCursor())
                    }
                }
            }
        }
        Label("\uF00D", modifier = Modifier.textColor(Grayscale.Accent)
            .onClick { Services.notifications.dismiss(n.id) }.pointerCursor())
    }
}
```

`Image` is a framework primitive that scales the file at load time
(`gdk_pixbuf_new_from_file_at_scale` → `GdkTexture` → `GtkPicture`), so a huge logo does not
stretch the row.

A ready-made sender lives in `scripts/test-notification.sh`:

```bash
scripts/test-notification.sh "Title" "Body"                 # rich: icon + Open/Later + critical
scripts/test-notification.sh --plain "Title" "Body"         # plain notify-send
```

### Audio visualiser (CAVA)

`CavaEqualizer` draws a real audio spectrum: the Rust `spectrum` service spawns a `cava`
child with a generated raw-ASCII config and publishes 16 bar values (`0..100`) at 30 fps
through `Services.spectrum.state`.

```kotlin
Row(spacing = 8) {
    PlayerChip()
    HSpace(8)
    CavaEqualizer(width = 80, height = 20, color = Grayscale.Background)
}
```

Requirements: `cava` must be installed (`pacman -S cava`); the service feeds it PulseAudio
(pipewire-pulse works) and respawns it if it exits. With no audio playing the bars stay
flat. A plain widget for custom drawing:

```kotlin
val bars by Services.spectrum.state.collectAsState()
Canvas(96, 20) { cr, w, h -> /* draw bars from 0..100 values */ }
```

## The bar itself

```kotlin
@Composable
fun MyBar() {
    CenterBox(
        modifier = Modifier
            .align(h = Align.Fill)
            .height(40)
            .fontFamily("JetBrainsMono Nerd Font")
            .fontSize(13),
        start = { Row(spacing = 0) { Clock() } },
        center = { Space(16) },
        end = { Row(spacing = 0) { Volume() } },
    )
}

fun main() = Shell.run {
    barWindow { MyBar() }
}
```

## Animations

Any Compose state works; animate with `dev.knshell.animation.animateFloatAsState` — either the
QML-style time-based variant (`durationMs` + `Easing`) or the spring variant (`stiffness` +
`dampingRatio`) — and draw with `Canvas` (cairo):

```kotlin
var up by remember { mutableStateOf(false) }
LaunchedEffect(Unit) { while (true) { delay(900); up = !up } }
val radius by animateFloatAsState(
    targetValue = if (up) 8f else 4f,
    durationMs = 180,
    easing = Easing.OutCubic,   // monotonic: no overshoot, no parent-layout jitter
)
Canvas(24, 24) { cr, width, height -> /* cairo drawing */ }
```

A completely empty `CenterBox` (no modules) falls back to GTK's default surface height;
the layer surface sizes to the content as soon as modules are added.

All DSL widgets are **plain by default**: Adwaita backgrounds, borders, paddings, minimum
sizes, focus rings and checked states are stripped by a global reset (see "Plain widgets"
in [shell-api.md](shell-api.md)); only your Modifier styles paint anything.

Windows, services and actions are documented in [shell-api.md](shell-api.md).
