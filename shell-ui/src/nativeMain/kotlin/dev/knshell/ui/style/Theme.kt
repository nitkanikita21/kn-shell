package dev.knshell.ui.style

import dev.knshell.compose.Catppuccin
import dev.knshell.compose.Color

/**
 * Colour palette + metrics of a bar.
 *
 * The kit components take a theme instead of hard-coding colours, so an entire bar can
 * be re-skinned by passing a different instance. [Mocha] is the Catppuccin palette used
 * by the reference waybar theme.
 */
data class BarTheme(
    val surface: Color,
    val text: Color,
    val accent: Color,
    val blue: Color,
    val green: Color,
    val red: Color,
    val mauve: Color,
    val pink: Color,
    val flamingo: Color,
    val sapphire: Color,
    val sky: Color,
    val radius: Int = 16,
    val pillPaddingH: Int = 14,
    val pillPaddingV: Int = 5,
    val gap: Int = 5,
    val fontFamily: String = "JetBrainsMono Nerd Font",
    val fontSize: Int = 13,
) {
    companion object {
        /** Catppuccin Mocha — the palette the shell ships with. */
        val Mocha: BarTheme = BarTheme(
            surface = Catppuccin.Surface0,
            text = Catppuccin.Text,
            accent = Catppuccin.Accent,
            blue = Catppuccin.Blue,
            green = Catppuccin.Green,
            red = Catppuccin.Red,
            mauve = Catppuccin.Mauve,
            pink = Catppuccin.Pink,
            flamingo = Catppuccin.Flamingo,
            sapphire = Catppuccin.Sapphire,
            sky = Catppuccin.Sky,
        )

        /** Theme used when no explicit theme is supplied. */
        val Default: BarTheme = Mocha
    }
}
