package dev.knshell.ui.style

import dev.knshell.compose.Catppuccin
import dev.knshell.compose.Color
import dev.knshell.compose.Modifier
import dev.knshell.compose.background
import dev.knshell.compose.bold
import dev.knshell.compose.fontSize
import dev.knshell.compose.hoverBackground
import dev.knshell.compose.margin
import dev.knshell.compose.padding
import dev.knshell.compose.size
import dev.knshell.compose.textColor

/**
 * The signature bar "pill": padded, rounded, softly shadowed and animated on hover.
 *
 * [color] is the background colour; text colours are applied with [pillText].
 */
fun Modifier.pill(
    theme: BarTheme = BarTheme.Mocha,
    color: Color = theme.surface,
    radius: Int = theme.radius,
): Modifier = this
    .padding(horizontal = theme.pillPaddingH, vertical = theme.pillPaddingV)
    .background(color, radius = radius, shadow = true, transitionMs = 200)
    .margin(horizontal = theme.gap)

/**
 * Workspace chip: compact inactive dot vs. highlighted, bold active chip.
 *
 * The caller supplies the (usually spring-animated) [width] and [height].
 */
fun Modifier.chip(
    active: Boolean,
    theme: BarTheme = BarTheme.Mocha,
    width: Int,
    height: Int,
): Modifier = this
    .size(width = width, height = height)
    .background(
        if (active) theme.mauve else Catppuccin.Surface3,
        radius = if (active) 7 else 100,
        transitionMs = 250,
    )
    .margin(horizontal = 4)
    .textColor(if (active) Catppuccin.Crust else theme.mauve)
    .hoverBackground(theme.accent)
    .let { if (active) it.bold().fontSize(10) else it }

/** Text styling for bar content: colour, size and optional bold. */
fun Modifier.pillText(
    theme: BarTheme,
    color: Color = theme.text,
    size: Int = theme.fontSize,
    bold: Boolean = false,
): Modifier = this
    .textColor(color)
    .fontSize(size)
    .let { if (bold) it.bold() else it }
