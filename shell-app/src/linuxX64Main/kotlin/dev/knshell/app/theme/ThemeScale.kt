package dev.knshell.app.theme

import dev.knshell.app.theme.ThemeTokens
import dev.knshell.compose.Color

object ThemeScale {

    fun dark(hue: Float, chroma: Float): ThemeTokens = ThemeTokens(
        bg = Color.oklch(0.145f, 0.006f, hue),
        surface = Color.oklch(0.205f, 0.006f, hue),
        surface2 = Color.oklch(0.269f, 0.006f, hue),
        border = Color.oklch(0.269f, 0.006f, hue),
        mutedFg = Color.oklch(0.552f, 0.016f, hue),
        fg = Color.oklch(0.985f, 0.002f, hue),
        accent = Color.oklch(0.705f, minOf(chroma * 2.5f, 0.18f), hue),
        isDark = true,
    )

    fun light(hue: Float, chroma: Float): ThemeTokens = ThemeTokens(
        bg = Color.oklch(0.975f, 0.006f, hue),
        surface = Color.oklch(0.945f, 0.006f, hue),
        surface2 = Color.oklch(0.905f, 0.006f, hue),
        border = Color.oklch(0.885f, 0.006f, hue),
        mutedFg = Color.oklch(0.500f, 0.016f, hue),
        fg = Color.oklch(0.205f, 0.002f, hue),
        accent = Color.oklch(0.550f, minOf(chroma * 2.5f, 0.18f), hue),
        isDark = false,
    )

    fun fallback(isDark: Boolean = true): ThemeTokens =
        if (isDark) dark(hue = 285f, chroma = 0f)
        else light(hue = 285f, chroma = 0f)
}
