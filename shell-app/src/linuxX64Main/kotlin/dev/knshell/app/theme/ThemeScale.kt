package dev.knshell.app.theme

import dev.knshell.compose.Color
import kotlin.math.abs

/**
 * Turns a wallpaper palette into the shadcn token set.
 *
 * `palette-core` says *which* colour family the wallpaper belongs to ([WallpaperPalette.hue]),
 * *how saturated* that family gets ([WallpaperPalette.familyChroma]) and whether a second family
 * is present ([WallpaperPalette.secondHue]); this object is the whole "how does the UI look"
 * layer on top of that. The structure is deliberately the shadcn one: a neutral ramp tinted by
 * the wallpaper, one brand step (`primary`) built from the wallpaper's own chroma, and a
 * semantic error colour that stays red but leans towards the wallpaper instead of ignoring it.
 *
 * Three rules make the result read as one design rather than a grey box over a photo:
 *
 * 1. **Surfaces carry the wallpaper's hue and a slice of its chroma** — not a fixed 0.008 sliver,
 *    which is invisible on a saturated green wallpaper and identical on every other one.
 * 2. **The mean chroma is not the input, the family maximum is.** `suggested_chroma` averages the
 *    saturated half of an image against its grey half, so it collapses towards zero exactly when
 *    the wallpaper is bold. [WallpaperPalette.familyChroma] answers the real question.
 * 3. **Hue and chroma come from the wallpaper, lightness from a fixed ramp** (± a small adaptive
 *    pull). That keeps text contrast stable no matter how dark the photo is.
 *
 * How loud the tint is allowed to be is [ThemeTuning]'s job, not this file's; every colour here
 * is built with [Color.oklchFitted] so a tint that does not fit sRGB loses chroma instead of
 * drifting to another hue.
 */
object ThemeScale {

    /** Share of the wallpaper's family chroma that surface tokens carry. */
    private const val NEUTRAL_GAIN = 0.90f

    /** Surfaces below this chroma read as grey, so a chromatic wallpaper gets a floor. */
    private const val NEUTRAL_FLOOR = 0.015f

    /** `accent`/`ring` are the "this is a colour" steps: a bit more chroma than the ramp. */
    private const val TINT_GAIN = 1.10f

    /** `accent` must stay clearly above the ramp it sits on. */
    private const val TINT_OVER_NEUTRAL = 1.5f

    /** Extra chroma for `secondary`/`muted` when a second hue family exists. */
    private const val SECOND_HUE_GAIN = 1.25f

    /** `ring` is the most saturated non-brand token: a visible focus indicator. */
    private const val RING_GAIN = 1.8f

    private const val RING_MAX_CHROMA = 0.13f

    /** Secondary text may hint at the hue, but text that *is* a colour reads as an error. */
    private const val MUTED_FOREGROUND_MAX_CHROMA = 0.05f

    /** How far the wallpaper's chroma is pushed for `primary`: subtle walls stay subtle. */
    private const val PRIMARY_GAIN = 2.5f

    /** Ceiling for `primary`'s chroma, so a neon wallpaper cannot produce a glowing button. */
    private const val PRIMARY_MAX_CHROMA = 0.18f

    /** Wallpapers with no chroma at all keep `primary` at its neutral floor instead of going grey. */
    private const val PRIMARY_FAMILY_FALLBACK = 0.8f

    /** ± range the dominant lightness is allowed to shift the surface ramp. */
    private const val LIGHTNESS_PULL_RANGE = 0.018f

    /** Base hues for semantic colours: red for errors, close enough that a nudge reads as style. */
    private const val DESTRUCTIVE_HUE = 25f

    /** How much of the wallpaper's hue the error colour follows (0 = ignore the wallpaper). */
    private const val DESTRUCTIVE_PULL = 0.35f

    /** Hard limit on that nudge: whatever the wallpaper, `destructive` must still read as red. */
    private const val DESTRUCTIVE_MAX_SHIFT = 12f

    fun dark(palette: WallpaperPalette, tuning: ThemeTuning = ThemeTuning.Balanced): ThemeTokens {
        val tint = Tints(palette, tuning)
        val pull = tint.lightnessPull
        val lift = tuning.lift

        val background = tint.color(0.145f + lift + pull * 0.6f, tint.neutral)
        val card = tint.color(0.205f + lift * 0.9f + pull * 0.7f, tint.neutral)
        val foreground = tint.color(0.985f, 0.004f + tint.neutral * 0.3f, second = true)
        return ThemeTokens(
            background = background,
            foreground = foreground,
            card = card,
            cardForeground = foreground,
            popover = card,
            popoverForeground = foreground,
            primary = tint.color(0.705f, tint.brand),
            primaryForeground = background,
            secondary = tint.color(0.269f + lift * 1.1f + pull * 0.8f, tint.second, second = true),
            secondaryForeground = foreground,
            muted = tint.color(0.269f + lift * 1.1f + pull * 0.8f, tint.second, second = true),
            mutedForeground = tint.color(0.708f, minOf(tint.tint, MUTED_FOREGROUND_MAX_CHROMA), second = true),
            // shadcn's `accent` is the hover wash, one step above `secondary` — not the brand.
            accent = tint.color(0.371f + lift * 1.3f + pull, tint.tint),
            accentForeground = foreground,
            // Warm wallpapers pull the error colour a few degrees; the chroma floor keeps it red.
            destructive = tint.color(0.680f, minOf(maxOf(tint.chroma * 1.5f, 0.15f), 0.21f), hue = tint.destructiveHue),
            destructiveForeground = foreground,
            // Translucent hairlines: the wallpaper shows through the shell's edges. Tinted, so the
            // edge belongs to the same colour family instead of being a grey line.
            border = tint.color(0.62f, tint.neutral * 1.4f, alpha = 0.22f),
            input = tint.color(0.68f, tint.neutral * 1.6f, alpha = 0.28f),
            ring = tint.color(0.556f, minOf(tint.tint * RING_GAIN, RING_MAX_CHROMA), second = true),
            isDark = true,
        )
    }

    fun light(palette: WallpaperPalette, tuning: ThemeTuning = ThemeTuning.Balanced): ThemeTokens {
        val tint = Tints(palette, tuning)
        val pull = tint.lightnessPull
        val lift = tuning.lift

        // The window itself is tinted too: a pure white background next to a coloured panel is
        // what makes a light theme feel bolted on to its wallpaper.
        val background = tint.color(0.975f - lift * 0.6f + pull * 0.12f, tint.neutral * 0.80f)
        val card = tint.color(0.998f - lift * 0.25f, tint.neutral * 0.45f)
        val foreground = tint.color(0.205f + pull * 0.3f, maxOf(tint.neutral * 0.5f, 0.004f))
        return ThemeTokens(
            background = background,
            foreground = foreground,
            card = card,
            cardForeground = foreground,
            popover = card,
            popoverForeground = foreground,
            // Darker than the dark theme's brand: on white, lightness 0.55 is what stays legible.
            primary = tint.color(0.55f, tint.brand),
            primaryForeground = background,
            secondary = tint.color(0.945f - lift * 0.9f + pull * 0.15f, maxOf(tint.second * 1.05f, 0.006f), second = true),
            secondaryForeground = foreground,
            muted = tint.color(0.945f - lift * 0.9f + pull * 0.15f, maxOf(tint.second * 1.05f, 0.006f), second = true),
            mutedForeground = tint.color(0.50f, minOf(tint.tint, MUTED_FOREGROUND_MAX_CHROMA), second = true),
            accent = tint.color(0.915f - lift * 0.5f, tint.tint * 0.95f),
            accentForeground = foreground,
            destructive = tint.color(0.577f, minOf(maxOf(tint.chroma * 1.6f, 0.17f), 0.245f), hue = tint.destructiveHue),
            destructiveForeground = tint.color(0.985f, 0.004f),
            border = tint.color(0.885f, maxOf(tint.neutral, 0.006f)),
            input = tint.color(0.885f, maxOf(tint.neutral, 0.006f)),
            ring = tint.color(0.55f, minOf(tint.tint * RING_GAIN, RING_MAX_CHROMA), second = true),
            isDark = false,
        )
    }

    /** The pre-palette theme: neutral, no invented colour. */
    fun fallback(isDark: Boolean = true, tuning: ThemeTuning = ThemeTuning.Balanced): ThemeTokens =
        if (isDark) dark(WallpaperPalette.Achromatic, tuning)
        else light(WallpaperPalette.Achromatic, tuning)

    /** Derives the chroma/hue numbers the two scales share, in one place. */
    private class Tints(palette: WallpaperPalette, tuning: ThemeTuning) {

        /** Hue of the dominant family; `secondary`/`ring` may slide towards the second one. */
        val hue: Float = palette.hue

        /** Hue of the second-colour steps, slid towards the wallpaper's second family. */
        val secondHue: Float = run {
            val second = palette.secondHue ?: return@run palette.hue
            val amount = tuning.secondHue.coerceIn(0f, 1f)
            if (amount == 0f) palette.hue
            else normalizeHue(palette.hue + signedHueDelta(palette.hue, second) * amount)
        }

        /** Chroma of the surface ramp. Zero for an achromatic wallpaper: no invented tint. */
        val neutral: Float = if (palette.familyChroma <= 0f) 0f else {
            val requested = palette.familyChroma * NEUTRAL_GAIN * tuning.tint
            val floor = NEUTRAL_FLOOR * tuning.tint
            minOf(maxOf(requested, floor), tuning.neutralCap)
        }

        /** Chroma of the "coloured surface" steps: `accent`, `ring`, secondary text. */
        val tint: Float = if (palette.familyChroma <= 0f) 0f else {
            val requested = palette.familyChroma * TINT_GAIN * tuning.tint
            minOf(maxOf(requested, neutral * TINT_OVER_NEUTRAL), tuning.accentCap)
        }

        /** Chroma of `secondary`/`muted`, borrowed from whichever family they point at. */
        val second: Float = if (palette.familyChroma <= 0f) 0f else {
            val requested = palette.secondFamilyChroma * NEUTRAL_GAIN * SECOND_HUE_GAIN * tuning.tint
            minOf(maxOf(requested, neutral * 1.2f), tuning.neutralCap * 1.3f)
        }

        /** How much of the wallpaper's own chroma `primary` inherits. */
        val brand: Float = minOf(
            maxOf(palette.chroma, palette.familyChroma * PRIMARY_FAMILY_FALLBACK) * PRIMARY_GAIN * tuning.accent,
            PRIMARY_MAX_CHROMA,
        )

        val destructiveHue: Float = run {
            val delta = signedHueDelta(palette.hue, DESTRUCTIVE_HUE)
            // Only a warm wallpaper nudges the error colour; a cold one must not drag red to teal.
            val pull = if (abs(delta) > 90f) 0f else DESTRUCTIVE_PULL * delta
            normalizeHue(DESTRUCTIVE_HUE + pull.coerceIn(-DESTRUCTIVE_MAX_SHIFT, DESTRUCTIVE_MAX_SHIFT))
        }

        /** The wallpaper's mean chroma — how red the error colour is allowed to be. */
        val chroma: Float = palette.chroma

        /** ± shift of the surface ramp from how bright the wallpaper's dominant cluster is. */
        val lightnessPull: Float =
            ((palette.dominantLightness - 0.5f) * LIGHTNESS_PULL_RANGE * 2f)
                .coerceIn(-LIGHTNESS_PULL_RANGE, LIGHTNESS_PULL_RANGE)

        /**
         * One token: OKLCH, gamut-fitted, in the dominant hue unless [second] asks for the
         * secondary family. Every token goes through here, so `border`'s alpha and the fitted
         * chroma cannot be forgotten at one call site.
         */
        fun color(
            lightness: Float,
            chroma: Float,
            alpha: Float = 1f,
            second: Boolean = false,
            hue: Float = if (second) secondHue else this.hue,
        ): Color = Color.oklchFitted(lightness, chroma, hue, alpha)
    }

    private fun normalizeHue(hue: Float): Float = ((hue % 360f) + 360f) % 360f
}
