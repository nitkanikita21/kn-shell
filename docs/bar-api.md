# Writing bar modules with the kit

The bar API lives in `dev.knshell.ui.kit`. A bar is a declarative tree:
[`Bar`](../shell-ui/src/nativeMain/kotlin/dev/knshell/ui/kit/Components.kt) owns the
three-region layout and the service subscriptions; everything inside it is a normal
Compose composable built from the kit primitives. The runtime, windows, services and
actions are documented in [`shell-api.md`](shell-api.md).

## A minimal custom bar

```kotlin
package dev.knshell.ui

import androidx.compose.runtime.Composable
import dev.knshell.shell.Shell
import dev.knshell.ui.kit.*

@Composable
fun MyBar() {
    val theme = BarTheme.Mocha

    Bar(
        theme = theme,
        start = {
            Pill(theme = theme) {
                Icon("\uF001", size = 14)
                ValueText("my module", theme, color = theme.mauve, size = 12)
            }
        },
        center = {
            WorkspaceStrip(theme)
        },
        end = {
            MetricPill("\uF2DB", 42, theme.blue, theme)
            ClockPill(theme)
            PowerPill(theme) { Shell.Hyprland.spawn("wlogout") }
        },
    )
}
```

Mount it exactly like the built-in bar:

```kotlin
fun main() {
    Shell.run {
        barWindow { MyBar() }
    }
}
```

## What the kit gives you

| Piece | Purpose |
| --- | --- |
| `BarTheme` / `BarTheme.Mocha` | colours, radius, pill padding and font metrics |
| `Modifier.pill()` | padded, rounded, shadowed, transitioned background |
| `Modifier.chip(active, …)` | active/inactive workspace chip style |
| `Modifier.pillText()` | themed text colour + size + bold |
| `Bar(start, center, end)` | `CenterBox` layout and `ShellServices` subscriptions |
| `Pill` / `Spacer` / `Icon` / `ValueText` | basic layout and text |
| `AnimatedPercent` | spring-animated integer value |
| `EqualizerBars` | independent spring equalizer, active/idle colours |
| `MusicPill` / `UpdatesPill` / `WorkspaceStrip` | ready-made modules |
| `VolumePill` / `MetricPill` / `ClockPill` / `PowerPill` | ready-made modules |

`MusicPill`, `VolumePill`, `WorkspaceStrip` and `ClockPill` read the shared `BarState`
that `Bar` fills from `Shell.services` (workspaces, media, volume, system stats) so a
custom module only needs to declare its content. A fully custom module can instead
collect `Shell.services` flows itself, as the dashboard example does.

The built-in [`TopBar`](../shell-ui/src/nativeMain/kotlin/dev/knshell/ui/TopBar.kt) is
the reference composition: it is nothing but `Bar` plus a list of kit components.
