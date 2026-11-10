# Writing a bar module

KN Shell ships the mechanics of a bar, **not** ready-made modules: there is no music pill,
no workspace strip and no clock in the library. Everything visible is composed from small
primitives, a theme and the Modifier DSL. This page shows the pieces.

## Primitives — `dev.knshell.ui.primitives`

| API | What it is |
| --- | --- |
| `Bar(theme, start, center, end)` | three-region layout (`GtkCenterBox`); subscribes to the shell services, maintains `BarState`, applies the theme font |
| `Pill(theme, color, spacing, onClick, content)` | rounded, padded, shadowed container; `onClick` makes it clickable without changing its look |
| `Space(width, height)` / `HSpace(w)` / `VSpace(h)` / `Spacer(modifier)` | empty boxes used for spacing |
| `Icon(glyph, size = 13, color = null)` | Nerd Font glyph |
| `Text(text, theme, color, size)` | themed label |

`BarState` (same package) exposes the reactive snapshots that `Bar` keeps up to date:
`workspaces`, `media`, `volume`, `stats`, `clock` — all Compose state, safe to read in a composable.

## Style — `dev.knshell.ui.style`

`BarTheme` holds colours and metrics (`BarTheme.Mocha`, `BarTheme.Default`, `copy(...)` to
re-skin), and `Modifier.pill(...)`, `Modifier.chip(...)`, `Modifier.pillText(...)` are the
style helpers on top of the generic Modifier DSL (`dev.knshell.compose`):
`padding`, `margin`, `background(color, radius, shadow, borderWidth, borderColor, transitionMs)`,
`hoverBackground`, `textColor`, `fontSize`, `fontFamily`, `bold`, `size/width/height`,
`fillMaxWidth`, `centerVertically`, `opacity`, `rawStyle(selector, css)`.

## Example: a clock module

```kotlin
@Composable
fun ClockPill(theme: BarTheme = BarTheme.Mocha) {
    val (date, colon, minutes) = BarState.clock.value
    Pill(theme) {
        Icon("\uF017")
        Space(6)
        Text(date, theme)
        Text(colon, theme, color = theme.flamingo)
        Text(minutes, theme, color = theme.flamingo)
    }
}
```

## Example: a volume module with a slider

```kotlin
@Composable
fun VolumePill(theme: BarTheme = BarTheme.Mocha) {
    val volume = BarState.volume.value
    Pill(theme) {
        Icon("\uF028")
        Slider(
            value = volume.toFloat(),
            min = 0f,
            max = 100f,
            step = 5f,
            modifier = Modifier
                .width(64)
                .height(16)
                .rawStyle(" trough", "min-height: 6px; border-radius: 3px; background-color: #45475A;")
                .rawStyle(" highlight", "border-radius: 3px; background-color: #A6E3A1;")
                .rawStyle(" slider", "min-width: 14px; min-height: 14px; border-radius: 50%; background-color: #CDD6F4;"),
        ) { Shell.services.setVolume(it.toInt()) }
        Text(volume.toString() + "%", theme, size = 12)
    }
}
```

## Example: an animated indicator

```kotlin
@Composable
fun PulseDot(theme: BarTheme = BarTheme.Mocha) {
    var up by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(900)
            up = !up
        }
    }
    val radius by animateFloatAsState(if (up) 8f else 4f, stiffness = 300f, dampingRatio = 0.5f)
    Canvas(24, 24) { cr, width, height ->
        drawCircle(cr, width, height, radius, 0.35, 0.75, 1.0)   // dev.knshell.ui.drawing helper
    }
}
```

## Composing the bar

```kotlin
@Composable
fun MyBar() = Bar(
    theme = BarTheme.Mocha,
    start = { ClockPill() },
    center = { Space(16) },
    end = { VolumePill(); PulseDot() },
)
```

## Hooking the bar into the shell

```kotlin
fun main() = Shell.run {
    barWindow { MyBar() }
}
```

The shell ships with an empty skeleton: add modules to the `Bar` slots and the layer surface
sizes itself to the content (a completely empty bar falls back to GTK's default surface
height). `BarTheme.barHeight` / `BarWindowSpec.height` define the target bar height.

Windows, services and actions are documented in [shell-api.md](shell-api.md).
