package dev.knshell.uikit.theme

/**
 * How strongly the wallpaper is allowed to colour the UI.
 *
 * The derivation in [ThemeScale] is B-medium by default: surfaces carry about a tenth of the
 * wallpaper's own family chroma, and `primary`/`ring` a bit more. Those numbers are a *default*,
 * not a law — this value object is the only place that decides how loud the result is, so
 * "make it more colourful" never means editing the derivation itself.
 *
 * Everything here multiplies or caps; hue decisions stay with [WallpaperPalette]. That is the
 * invariant worth keeping: **tuning changes chroma and surface lightness, never hue**.
 */
data class ThemeTuning(
    /**
     * Chroma multiplier for surfaces (`background`, `card`, `secondary`, `muted`, `accent`).
     * `0` turns the wallpaper tint off completely and leaves a plain neutral UI.
     */
    val tint: Float = 1f,
    /**
     * Chroma multiplier for the brand steps (`primary`, `ring`). Separate from [tint] so the
     * shell can stay calm while the one accented button still pops.
     */
    val accent: Float = 1f,
    /**
     * Lightness lift of the dark surfaces (subtracted from the light ones).
     *
     * This is the lever that matters in dark mode, and the reason it exists separately from
     * [tint]: at surface lightness ≈ 0.16 sRGB allows only ≈ 0.042 chroma at hue 126° (green) and
     * ≈ 0.051 at 253° (blue), so past `tint ≈ 1` the requested chroma already sits outside the
     * gamut and [ThemeScale] fits it back down — the slider would stop doing anything. Raising
     * lightness is what buys the extra chroma back.
     */
    val lift: Float = 0.022f,
    /** Ceiling for surface chroma, so a neon wallpaper cannot make the shell look plastic. */
    val neutralCap: Float = 0.055f,
    /** Ceiling for `accent`/`ring` chroma. */
    val accentCap: Float = 0.070f,
    /**
     * How far `secondary`/`muted` slide from the dominant hue towards the wallpaper's second
     * colour family: `0` keeps everything in one hue, `1` uses the second family fully.
     */
    val secondHue: Float = 1f,
) {
    companion object {
        /** The default: surfaces are visibly tinted, text stays legible. */
        val Balanced = ThemeTuning()

        /** No wallpaper tint at all — the pre-tinting shell. */
        val Off = ThemeTuning(tint = 0f)

        /** Barely-there tint, for wallpapers whose colour should not fight the content. */
        val Subtle = ThemeTuning(tint = 0.55f, accent = 0.85f, neutralCap = 0.030f)

        /** As colourful as dark surfaces can get before they stop reading as surfaces. */
        val Vivid = ThemeTuning(
            tint = 1.55f,
            accent = 1.35f,
            lift = 0.045f,
            neutralCap = 0.090f,
            accentCap = 0.100f,
        )

        /** Presets in display order, for a settings UI that should not hardcode them. */
        val presets: List<Pair<String, ThemeTuning>> = listOf(
            "off" to Off,
            "subtle" to Subtle,
            "balanced" to Balanced,
            "vivid" to Vivid,
        )

        /** Case-insensitive lookup for a config file / CLI flag; `null` for an unknown name. */
        fun preset(name: String): ThemeTuning? =
            presets.firstOrNull { it.first.equals(name.trim(), ignoreCase = true) }?.second
    }
}
