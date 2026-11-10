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
