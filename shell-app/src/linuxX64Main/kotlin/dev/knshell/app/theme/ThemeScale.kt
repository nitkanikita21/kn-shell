package dev.knshell.app.theme

import dev.knshell.compose.Color

/**
 * Turns a wallpaper palette into the shadcn token set.
 *
 * palette-core answers two numbers — the dominant hue and how chromatic the wallpaper is —
 * and this object is the whole "how does the UI look" layer on top of them. The structure is
 * deliberately the shadcn one: a neutral ramp tinted by the wallpaper hue, one brand step
 * (`primary`) built from the wallpaper's own chroma, and two fixed semantic colours
 * (`destructive`) that must not drift with the wallpaper.
 *
 * The neutrals keep a sliver of the hue (`NEUTRAL_CHROMA`) instead of being pure grey: a
 * shell tinted by its own wallpaper reads as one piece of design rather than a grey box
 * floating over a photo.
 */
object ThemeScale {

    /** Chroma of the neutral ramp — visible as a tint, still reads as a neutral. */
    private const val NEUTRAL_CHROMA = 0.008f

    /** Chroma of the ring/placeholder steps — slightly more tinted than the ramp. */
    private const val TINT_CHROMA = 0.016f

    /** How far the wallpaper's chroma is pushed for `primary`: subtle walls stay subtle. */
    private const val PRIMARY_GAIN = 2.5f

    /** Ceiling for `primary`'s chroma, so a neon wallpaper cannot produce a glowing button. */
    private const val PRIMARY_MAX_CHROMA = 0.18f

    fun dark(hue: Float, chroma: Float): ThemeTokens {
        val brand = minOf(chroma * PRIMARY_GAIN, PRIMARY_MAX_CHROMA)
        val background = Color.oklch(0.145f, NEUTRAL_CHROMA, hue)
        val card = Color.oklch(0.205f, NEUTRAL_CHROMA, hue)
        val foreground = Color.oklch(0.985f, 0.004f, hue)
        return ThemeTokens(
            background = background,
            foreground = foreground,
            card = card,
            cardForeground = foreground,
            popover = card,
            popoverForeground = foreground,
            primary = Color.oklch(0.705f, brand, hue),
            primaryForeground = background,
            secondary = Color.oklch(0.269f, NEUTRAL_CHROMA, hue),
            secondaryForeground = foreground,
            muted = Color.oklch(0.269f, NEUTRAL_CHROMA, hue),
            mutedForeground = Color.oklch(0.708f, TINT_CHROMA, hue),
            // shadcn's `accent` is the hover wash, one step above `secondary` — not the brand.
            accent = Color.oklch(0.371f, 0.012f, hue),
            accentForeground = foreground,
            destructive = Color.oklch(0.704f, 0.191f, 25f),
            destructiveForeground = foreground,
            // Translucent hairlines: the wallpaper shows through the shell's edges.
            border = Color.oklch(1f, 0f, hue, alpha = 0.10f),
            input = Color.oklch(1f, 0f, hue, alpha = 0.15f),
            ring = Color.oklch(0.556f, TINT_CHROMA, hue),
            isDark = true,
        )
    }

    fun light(hue: Float, chroma: Float): ThemeTokens {
        val brand = minOf(chroma * PRIMARY_GAIN, PRIMARY_MAX_CHROMA)
        val background = Color.oklch(0.985f, 0.004f, hue)
        val card = Color.oklch(1f, 0.002f, hue)
        val foreground = Color.oklch(0.205f, 0.006f, hue)
        return ThemeTokens(
            background = background,
            foreground = foreground,
            card = card,
            cardForeground = foreground,
            popover = card,
            popoverForeground = foreground,
            // Darker than the dark theme's brand: on white, lightness 0.55 is what stays legible.
            primary = Color.oklch(0.55f, brand, hue),
            primaryForeground = background,
            secondary = Color.oklch(0.955f, 0.006f, hue),
            secondaryForeground = foreground,
            muted = Color.oklch(0.955f, 0.006f, hue),
            mutedForeground = Color.oklch(0.50f, TINT_CHROMA, hue),
            accent = Color.oklch(0.93f, 0.010f, hue),
            accentForeground = foreground,
            destructive = Color.oklch(0.577f, 0.245f, 27f),
            destructiveForeground = Color.oklch(0.985f, 0.004f, hue),
            border = Color.oklch(0.885f, 0.006f, hue),
            input = Color.oklch(0.885f, 0.006f, hue),
            ring = Color.oklch(0.55f, TINT_CHROMA, hue),
            isDark = false,
        )
    }

    fun fallback(isDark: Boolean = true): ThemeTokens =
        if (isDark) dark(hue = 285f, chroma = 0f)
        else light(hue = 285f, chroma = 0f)
}
