package dev.knshell.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.app.theme.ThemeMode
import dev.knshell.app.theme.ThemeService
import dev.knshell.app.theme.ThemeTokens
import dev.knshell.app.ui.helpers.collectTheme
import dev.knshell.app.ui.helpers.collectThemeMode
import dev.knshell.app.ui.helpers.injectDi
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Color
import dev.knshell.compose.Column
import dev.knshell.compose.Icon
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Overlay
import dev.knshell.compose.Row
import dev.knshell.compose.Slider
import dev.knshell.compose.Stack
import dev.knshell.compose.align
import dev.knshell.compose.aspectRatio
import dev.knshell.compose.background
import dev.knshell.compose.bold
import dev.knshell.compose.clipOverflow
import dev.knshell.compose.fillAll
import dev.knshell.compose.fillHeight
import dev.knshell.compose.fillWidth
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.height
import dev.knshell.compose.hoverBackground
import dev.knshell.compose.medium
import dev.knshell.compose.onClick
import dev.knshell.compose.onHover
import dev.knshell.compose.padding
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.radius
import dev.knshell.compose.requiredSize
import dev.knshell.compose.textColor
import dev.knshell.compose.weight
import dev.knshell.compose.width
import dev.knshell.icons.IconRef
import dev.knshell.icons.lucide.Lucide
import dev.knshell.ui.primitives.Text

/**
 * A single, flat demo surface that exercises the toolkit against a real wallpaper-derived
 * theme: a header, a tab bar that switches the whole body, a list with hover rows, settings
 * rows with a hand-rolled switch, sliders, buttons and the token swatches.
 *
 * Deliberately one file with a handful of small private composables — it is a *specimen* for
 * humans to eyeball while the palette reloads, not an app architecture. Nothing here is
 * reused from the real shell: every line is the demo for one modifier or token.
 */
@Composable
fun TestUi() {
    val theme by collectTheme()
    val mode by collectThemeMode()
    val service by injectDi<ThemeService>()
    val c = theme.colors
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Overview" to Lucide.LayoutGrid, "List" to Lucide.List, "Settings" to Lucide.SlidersHorizontal)

    Column(
        spacing = 16,
        modifier = Modifier.width(440).background(c.background).padding(20),
    ) {
        Header(c, mode)

        // Tab bar. `onClick` is a gesture on the plain Box, so the tab is not a GTK Button and
        // keeps the exact pill shape: no button chrome to fight with `kn-plain`.
        Row(spacing = 6, modifier = Modifier.fillWidth()) {
            for ((index, entry) in tabs.withIndex()) {
                val (name, icon) = entry
                TabPill(c, name, icon, active = tab == index) { tab = index }
            }
        }

        when (tab) {
            0 -> OverviewTab(c)
            1 -> ListTab(c)
            else -> SettingsTab(c, mode, service::setMode)
        }

        // `entries()` is the theme's own list, so a token added to the theme cannot be
        // silently missing from the specimen. Nineteen swatches do not fit one row: two rows,
        // each sharing its width by `weight`.
        val swatches = c.entries()
        for (row in swatches.chunked((swatches.size + 1) / 2)) {
            Row(spacing = 6, modifier = Modifier.fillWidth()) {
                for ((name, color) in row) {
                    // `weight`, not a fixed width: the swatches share the column evenly.
                    Box(
                        Modifier.weight(1.0).aspectRatio(1f).radius(8)
                            .background(color, borderWidth = 1, borderColor = c.border),
                    ) {
                        Stack {
                            Label(
                                name,
                                modifier = Modifier.padding(3)
                                    .fontFamily("JetBrainsMono Nerd Font")
                                    .fontSize(8)
                                    .textColor(textOn(color, c)),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ header

@Composable
private fun Header(c: ThemeTokens, mode: ThemeMode) {
    Row(spacing = 12, modifier = Modifier.fillWidth()) {
        // `Stack`, not `Box`: a block-flow `Box` packs its children along the main axis, so
        // `align(v = Center)` would have no claimed cell to centre into.
        // `aspectRatio(1f)`, not `size(44)`: a size *request* is only a GTK floor, so the row
        // would stretch the chip to its own height and it would stop being 1:1.
        Stack(
            Modifier.aspectRatio(1f).radius(12).background(c.primary),
        ) {
            Icon(
                Lucide.Home,
                size = 22,
                color = c.background,
                modifier = Modifier.align(v = Align.Center, h = Align.Center),
            )
        }
        Column(spacing = 4, modifier = Modifier.weight(1.0).align(v = Align.Center)) {
            Text("KN Shell", size = 22, color = c.foreground, modifier = Modifier.bold())
            Text(
                "theme demo · ${if (c.isDark) "dark" else "light"} (${mode.name.lowercase()}) · ${c.primary.hex()}",
                size = 12,
                color = c.mutedForeground,
            )
        }
        Icon(Lucide.Bell, size = 18, color = c.mutedForeground, modifier = Modifier.align(v = Align.Center))
    }
}

// ------------------------------------------------------------------ tabs

@Composable
private fun TabPill(
    c: ThemeTokens,
    name: String,
    icon: IconRef,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        spacing = 6,
        modifier = Modifier
            .onClick(onClick)
            .pointerCursor()
            .padding(horizontal = 12, vertical = 8)
            .radius(999, 999)
            .background(if (active) c.primary else c.secondary),
    ) {
        Icon(icon, size = 14, color = if (active) c.background else c.mutedForeground)
        Text(name, size = 13, color = if (active) c.background else c.foreground, modifier = Modifier.medium())
    }
}

// ------------------------------------------------------------------ tab 0: overview

@Composable
private fun OverviewTab(c: ThemeTokens) {
    Column(spacing = 16, modifier = Modifier.fillWidth()) {
        Card(
            c,
            "Card title",
            "Muted helper text that sits on the surface token to check foreground " +
                "legibility against the derived palette.",
        ) {
            var powerHover by remember { mutableStateOf(false) }
            Row(
                spacing = 8,
                modifier = Modifier.onHover(
                    onEnter = { powerHover = true },
                    onExit = { powerHover = false },
                ),
            ) {
                // Vector icon with an animated tint: the tween runs on the frame clock, so
                // hovering does not recompose this subtree per frame.
                Icon(Lucide.Power, size = 16, color = if (powerHover) c.foreground else c.primary, transitionMs = 180)
                Text("Hover the icon — the tint animates", size = 13, color = c.foreground)
            }
        }

        Row(spacing = 10, modifier = Modifier.fillWidth()) {
            Pill(c, "Accent", filled = true)
            Pill(c, "Surface", filled = false)
        }

        Column(spacing = 8, modifier = Modifier.fillWidth()) {
            Row(spacing = 8, modifier = Modifier.fillWidth()) {
                Text("Session progress", size = 13, color = c.foreground, modifier = Modifier.weight(1.0))
                Text("68%", size = 13, color = c.mutedForeground)
            }
            // Two boxes in a Row instead of a Stack: the fill is a share of the width, which is
            // exactly what `weight` means here.
            Row(spacing = 0, modifier = Modifier.fillWidth().height(6).radius(999, 999).clipOverflow()) {
                Box(Modifier.width(190).fillHeight().background(c.primary))
                Box(Modifier.weight(1.0).fillHeight().background(c.secondary))
            }
        }

        var volume by remember { mutableStateOf(62f) }
        Column(spacing = 6, modifier = Modifier.fillWidth()) {
            Row(spacing = 8, modifier = Modifier.fillWidth()) {
                Icon(Lucide.Volume2, size = 16, color = c.mutedForeground)
                Text("Volume", size = 13, color = c.foreground, modifier = Modifier.weight(1.0))
                Text("${volume.toInt()}%", size = 13, color = c.mutedForeground)
            }
            Slider(value = volume, min = 0f, max = 100f, onValueChange = { volume = it })
        }
    }
}

// ------------------------------------------------------------------ tab 1: list

private data class ListEntry(
    val icon: IconRef,
    val title: String,
    val subtitle: String,
    val value: String,
)

@Composable
private fun ListTab(c: ThemeTokens) {
    val entries = listOf(
        ListEntry(Lucide.Wifi, "Wi-Fi", "kn-shell-5G", "Connected"),
        ListEntry(Lucide.Bluetooth, "Bluetooth", "No devices paired", "Off"),
        ListEntry(Lucide.Volume2, "Output", "PipeWire · built-in speakers", "62%"),
        ListEntry(Lucide.Battery, "Battery", "Charging over USB-C", "87%"),
        ListEntry(Lucide.Sun, "Brightness", "eDP-1 adaptive", "Auto"),
        ListEntry(Lucide.Monitor, "Display", "1920×1080 @ 1.25", "120 Hz"),
    )
    Column(spacing = 2, modifier = Modifier.fillWidth()) {
        for (entry in entries) ListRow(c, entry)
    }
}

@Composable
private fun ListRow(c: ThemeTokens, entry: ListEntry) {
    var hover by remember { mutableStateOf(false) }
    Row(
        spacing = 12,
        modifier = Modifier.fillWidth()
            .onHover(onEnter = { hover = true }, onExit = { hover = false })
            .padding(horizontal = 10, vertical = 9)
            .radius(9)
            .hoverBackground(c.accent),
    ) {
        // A tinted tile keeps the rows aligned even though the icons have different aspect.
        Stack(
            Modifier.requiredTile().radius(8).background(if (hover) c.primary else c.secondary),
        ) {
            Icon(
                entry.icon,
                size = 16,
                color = if (hover) c.background else c.foreground,
                modifier = Modifier.align(v = Align.Center, h = Align.Center),
            )
        }
        Column(spacing = 2, modifier = Modifier.weight(1.0)) {
            Text(entry.title, size = 14, color = c.foreground, modifier = Modifier.medium())
            Text(entry.subtitle, size = 12, color = c.mutedForeground, ellipsize = true)
        }
        Text(entry.value, size = 13, color = c.mutedForeground, modifier = Modifier.align(v = Align.Center))
        Icon(
            Lucide.ChevronRight,
            size = 16,
            color = if (hover) c.foreground else c.mutedForeground,
            modifier = Modifier.align(v = Align.Center),
        )
    }
}

// ------------------------------------------------------------------ tab 2: settings

@Composable
private fun SettingsTab(c: ThemeTokens, mode: ThemeMode, onMode: (ThemeMode) -> Unit) {
    var wifi by remember { mutableStateOf(true) }
    var bluetooth by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf(true) }
    var scale by remember { mutableStateOf(1.25f) }

    Column(spacing = 14, modifier = Modifier.fillWidth()) {
        // The palette's dark/light guess is only a guess — a dark UI can sit on a bright
        // wallpaper. This is the override; `Auto` hands the decision back to the palette.
        Column(spacing = 8, modifier = Modifier.fillWidth()) {
            Text("Appearance", size = 13, color = c.mutedForeground)
            Row(spacing = 6, modifier = Modifier.fillWidth()) {
                for (candidate in ThemeMode.entries) {
                    ModePill(c, candidate, active = mode == candidate) { onMode(candidate) }
                }
            }
        }
        Box(Modifier.fillWidth().height(1).background(c.border))

        SwitchRow(c, "Wi-Fi", "Join kn-shell-5G automatically", wifi) { wifi = it }
        SwitchRow(c, "Bluetooth", "Discoverable for 2 minutes", bluetooth) { bluetooth = it }
        SwitchRow(c, "Do not disturb", "Silence notifications for 1 hour", focus) { focus = it }

        Box(Modifier.fillWidth().height(1).background(c.border))

        Column(spacing = 6, modifier = Modifier.fillWidth()) {
            Row(spacing = 8, modifier = Modifier.fillWidth()) {
                Icon(Lucide.SlidersHorizontal, size = 16, color = c.mutedForeground)
                Text("Interface scale", size = 13, color = c.foreground, modifier = Modifier.weight(1.0))
                Text(twoDecimals(scale) + "×", size = 13, color = c.mutedForeground)
            }
            Slider(value = scale, min = 0.75f, max = 2f, step = 0.05f, onValueChange = { scale = it })
        }

        Row(spacing = 10, modifier = Modifier.fillWidth()) {
            Pill(c, "Apply", filled = true)
            Pill(c, "Reset", filled = false)
        }
    }
}

@Composable
private fun SwitchRow(c: ThemeTokens, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        spacing = 12,
        modifier = Modifier.fillWidth()
            .onClick { onChange(!checked) }
            .pointerCursor()
            .padding(vertical = 2),
    ) {
        Column(spacing = 2, modifier = Modifier.weight(1.0)) {
            Text(title, size = 14, color = c.foreground, modifier = Modifier.medium())
            Text(subtitle, size = 12, color = c.mutedForeground, ellipsize = true)
        }
        // Hand-rolled switch: a rounded track with a knob on the left or the right. `Stack`
        // gives the knob the whole cell, `align` puts it at one end of it.
        Stack(
            Modifier.width(44).height(24).radius(999, 999)
                .background(if (checked) c.primary else c.secondary)
                .padding(3),
        ) {
            Box(
                Modifier.width(18).height(18).radius(999, 999).background(c.background)
                    .align(h = if (checked) Align.End else Align.Start, v = Align.Center),
            )
        }
    }
}

// ------------------------------------------------------------------ shared bits

@Composable
private fun Card(c: ThemeTokens, title: String, body: String, content: @Composable () -> Unit) {
    Box(Modifier.fillWidth().radius(12).background(c.card).padding(16)) {
        Column(spacing = 8) {
            Text(title, size = 16, color = c.foreground, modifier = Modifier.bold())
            Text(body, size = 13, color = c.mutedForeground)
            content()
        }
    }
}

@Composable
private fun Pill(c: ThemeTokens, text: String, filled: Boolean) {
    Row(
        spacing = 6,
        modifier = Modifier.padding(horizontal = 16, vertical = 10).radius(999, 999)
            .background(if (filled) c.primary else c.secondary),
    ) {
        Text(
            text,
            size = 14,
            color = if (filled) c.background else c.foreground,
            modifier = if (filled) Modifier.bold() else Modifier.medium(),
        )
    }
}

/** Segmented-control cell for [ThemeMode] — same pill shape as the tab bar, evenly shared. */
@Composable
private fun ModePill(c: ThemeTokens, mode: ThemeMode, active: Boolean, onClick: () -> Unit) {
    val label = when (mode) {
        ThemeMode.Auto -> "Auto"
        ThemeMode.Dark -> "Dark"
        ThemeMode.Light -> "Light"
    }
    Row(
        spacing = 6,
        modifier = Modifier.weight(1.0)
            .onClick(onClick)
            .pointerCursor()
            .padding(vertical = 9)
            .radius(9)
            .background(if (active) c.primary else c.secondary),
    ) {
        Overlay(Modifier.fillWidth()) {
            Label(
                label,
                modifier = Modifier.fontFamily("sans").align(h = Align.Center)
                    .textColor(if (active) c.background else c.foreground),
            )
        }
    }
}

/** A 30×30 tile: `requiredSize` because a `Row` would otherwise stretch it to the row height. */
private fun Modifier.requiredTile(): Modifier = requiredSize(30)

/** Pick a readable label color: light text on dark swatches, dark on light. */
private fun textOn(swatch: Color, theme: ThemeTokens): Color =
    // Cheap luminance proxy: the sum of the channels against 3 × mid-grey.
    if (swatch.r + swatch.g + swatch.b > 384) theme.background else theme.foreground

/** Kotlin/Native has no `String.format`, and a slider would otherwise print 1.2500001. */
private fun twoDecimals(value: Float): String {
    val hundredths = (value * 100f).toInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

/** `#rrggbb` for the "theme demo · dark · #8ab4f8" line — no dependency on a colour library. */
private fun Color.hex(): String {
    fun part(value: Int): String = value.toString(16).padStart(2, '0')
    return "#${part(r)}${part(g)}${part(b)}"
}
