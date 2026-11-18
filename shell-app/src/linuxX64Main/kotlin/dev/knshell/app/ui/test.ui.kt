package dev.knshell.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.app.ui.helpers.collectTheme
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Color
import dev.knshell.compose.Column
import dev.knshell.compose.Icon
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Overlay
import dev.knshell.compose.Row
import dev.knshell.compose.Stack
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.bold
import dev.knshell.compose.centerBoth
import dev.knshell.compose.fillWidth
import dev.knshell.compose.onHover
import dev.knshell.compose.fillAll
import dev.knshell.compose.fontFamily
import dev.knshell.compose.padding
import dev.knshell.compose.radius
import dev.knshell.compose.aspectRatio
import dev.knshell.compose.size
import dev.knshell.compose.textColor
import dev.knshell.icons.lucide.Lucide
import dev.knshell.ui.primitives.Text

/**
 * A single, flat demo surface that exercises every [DesignTokens] entry against a
 * real wallpaper-derived theme. No sub-components — one big composable so the
 * token-to-widget mapping is easy to eyeball while the palette reloads.
 */
@Composable
fun TestUi() {
    val theme by collectTheme()
    val c = theme.colors

    Column(
        spacing = 16,
        modifier = Modifier
            .fillWidth()
            .background(c.bg)
            .padding(24)
    ) {
        // Header/
        Row(spacing = 12, modifier = Modifier.fillWidth()) {
            // `Stack`, not `Box`: a block-flow `Box` packs its children along the main
            // axis, so `align(v = Center)` has no claimed cell to centre into — the icon
            // would sit at the top edge. `Stack` hands every child the whole 44x44 cell.
            // `requiredSize`, not `size`: a `size()` request is only a GTK floor, so this
            // row would stretch the chip to its own height and it would stop being 1:1.
            Stack(
                Modifier
                    .aspectRatio(1f)
                    .radius(12)
                    .background(c.accent),
            ) { Icon(Lucide.Home, size = 22, color = c.bg, modifier = Modifier.align(v = Align.Center, h = Align.Center)) }

            Column(spacing = 4) {
                Text("KN Shell", size = 22, color = c.fg, modifier = Modifier.bold())
                Text("theme demo · ${if (c.isDark) "dark" else "light"}", size = 13, color = c.mutedFg)
            }
        }

        // Accent button + pill
        Row(spacing = 12) {
            Box(
                Modifier
                    .padding(horizontal = 16, vertical = 10)
                    .radius(999, 999)
                    .background(c.accent),
            ) { Text("Accent", size = 14, color = c.bg, modifier = Modifier.bold()) }

            Box(
                Modifier
                    .padding(horizontal = 16, vertical = 10)
                    .radius(999, 999)
                    .background(c.surface2)
                    .textColor(c.fg),
            ) { Text("Surface", size = 14) }
        }

        // Card with surface
        Box(
            Modifier
                .fillWidth()
                .padding(16)
                .radius(12)
                .background(c.surface)
                .padding(16),
        ) {
            Column(spacing = 8) {
                Text("Card title", size = 16, color = c.fg, modifier = Modifier.bold())
                Text(
                    "Muted helper text that sits on the surface token to check " +
                        "foreground legibility against the derived palette.",
                    size = 13,
                    color = c.mutedFg,
                )
                var powerHover by remember { mutableStateOf(false) }
                Row(
                    spacing = 8,
                    modifier = Modifier.onHover(
                        onEnter = { powerHover = true },
                        onExit = { powerHover = false },
                    ),
                ) {
                    // Vector icon with an animated tint: the tween runs on the frame
                    // clock, so hovering does not recompose this subtree per frame.
                    Icon(Lucide.Power, size = 16, color = if (powerHover) c.fg else c.accent, transitionMs = 180)
                    Text("Icon in accent", size = 13, color = c.fg)
                }
            }
        }

        // Token swatches — every entry in ThemeTokens
        val swatches = listOf(
            "bg" to c.bg,
            "surface" to c.surface,
            "surface2" to c.surface2,
            "border" to c.border,
            "mutedFg" to c.mutedFg,
            "fg" to c.fg,
            "accent" to c.accent,
        )
        Row(spacing = 10, modifier = Modifier.fillWidth()) {
            for ((name, color) in swatches) {
                Box(
                    Modifier
                        .size(64)
                        .radius(10)
                        .background(color, borderWidth = if (color == c.border) 0 else 1, borderColor = c.border),
                ) {
                    Overlay(Modifier.fillAll()) {
                        Label(
                            name,
                            modifier = Modifier
                                .fontFamily("JetBrainsMono Nerd Font")
                                .textColor(textOn(color, c)),
                        )
                    }
                }
            }
        }

        // Border line demo
        Box(
            Modifier
                .fillWidth()
                .size(0, 2)
                .background(c.border),
        )
    }
}

/** Pick a readable label color: light text on dark swatches, dark on light. */
private fun textOn(swatch: Color, theme: dev.knshell.app.theme.ThemeTokens): Color {
    // Luminance-ish heuristic using the green channel as proxy.
    return if (swatch.g + swatch.r + swatch.b > 384) theme.bg else theme.fg
}
