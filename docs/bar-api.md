# Writing UI on top of the shell

The library ships **no bar, no modules and no theme** — only mechanics. You compose the
bar yourself from generic layout, utilities, the Modifier DSL and reactive services.

## Building blocks

| Piece | From |
| --- | --- |
| layout | `Row`, `Column`, `Box`, `CenterBox` — `dev.knshell.compose` |
| utilities | `Space`, `HSpace`, `VSpace`, `Spacer`, `Icon`, `Text` — `dev.knshell.ui.primitives` |
| styling | Modifier DSL (`padding`, `margin`, `background`, `textColor`, `fontSize`, …) + `Catppuccin` palette |
| data | `Shell.services` — `StateFlow`s (`workspaces`, `media`, `volume`, `system`, `windows`) |
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
    LaunchedEffect(Unit) { Shell.services.volume.collect { volume = it } }

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
        ) { Shell.services.setVolume(it.toInt()) }
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
Box(
    Modifier
        .widthBehavior(durationMs = 180, easing = Easing.OutCubic)
        .size(if (active) 34 else 24, 24),
)
```

From then on every change of `size()`, `width()` or `height()` in that chain is tweened on
the widget node (plain GTK calls, no Compose recomposition). The first application snaps to
the initial value; removing the widget cancels its tween. Colours are better handled by
`transition(ms)` / `transitionMs` (native GTK transitions).

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

## The bar itself

```kotlin
@Composable
fun MyBar() {
    CenterBox(
        modifier = Modifier
            .fillMaxWidth()
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
