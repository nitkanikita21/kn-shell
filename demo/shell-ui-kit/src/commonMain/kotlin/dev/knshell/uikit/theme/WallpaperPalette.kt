package dev.knshell.uikit.theme

import kotlin.math.abs
import kotlin.math.max

/**
 * One cluster of the wallpaper as `palette-core` reports it: an OKLCH swatch plus the share of
 * the image it covers (`weight`, 0..1, sorted descending by the Rust side).
 */
data class PaletteSwatch(
    /** OKLCH lightness, 0..1. */
    val l: Float,
    /** OKLCH chroma; 0 for a true grey cluster. */
    val c: Float,
    /** OKLCH hue in degrees, `null` when the cluster is achromatic (Rust reports NAN). */
    val h: Float?,
    /** Fraction of sampled pixels in this cluster. */
    val weight: Float,
)

/**
 * Everything the theme needs to know about a wallpaper, instead of the two numbers the shell
 * used to pass around.
 *
 * The palette is the design input: [hue] says *which* colour family the wallpaper belongs to,
 * the swatches say *how saturated* that family actually gets and whether a second family is
 * present. [chroma] alone cannot answer either question — it is the circular weighted mean over
 * chromatic clusters, so a wallpaper that is half saturated green and half grey reports a small
 * chroma and the UI would come out grey.
 */
data class WallpaperPalette(
    /** Dominant hue in degrees — `palette-core`'s `suggested_hue`, or [NEUTRAL_HUE]. */
    val hue: Float,
    /** `palette-core`'s `suggested_chroma`: a weighted mean, kept for `primary`. */
    val chroma: Float,
    /** Clusters of the wallpaper, strongest first. Empty for an achromatic/fallback palette. */
    val swatches: List<PaletteSwatch> = emptyList(),
) {

    /**
     * The most saturated cluster **of this wallpaper's own hue family** (`|Δh| ≤ 30°`).
     *
     * This is the number surfaces are built from: the mean [chroma] averages a saturated accent
     * against the grey half of the image and lands near zero, while the family maximum answers
     * the question the theme actually asks — "how colourful does this hue get here?". It is
     * independent of area coverage, so a small saturated detail can still tint the shell; a
     * genuinely grey wallpaper (no chromatic cluster, [swatches] empty) gets exactly 0 and the UI
     * stays neutral instead of inventing a colour.
     */
    val familyChroma: Float
        get() {
            var best = 0f
            for (swatch in swatches) {
                val h = swatch.h ?: continue
                if (hueDistance(h, hue) <= FAMILY_HUE_DELTA) best = max(best, swatch.c)
            }
            return best
        }

    /**
     * The heaviest cluster whose hue sits at least [SECOND_HUE_MIN_DELTA] away from [hue].
     *
     * `null` when the wallpaper is monochromatic (the common case): then the second surface step
     * has nothing to borrow and simply stays in the dominant family.
     */
    val secondHue: Float?
        get() {
            var bestHue: Float? = null
            var bestWeight = 0f
            for (swatch in swatches) {
                val h = swatch.h ?: continue
                if (swatch.c < SECOND_HUE_MIN_CHROMA) continue
                if (hueDistance(h, hue) < SECOND_HUE_MIN_DELTA) continue
                if (swatch.weight > bestWeight) {
                    bestWeight = swatch.weight
                    bestHue = h
                }
            }
            return bestHue
        }

    /**
     * The same "how saturated is this family" question for [secondHue], falling back to
     * [familyChroma] so a second surface step never comes out more muted than the first.
     */
    val secondFamilyChroma: Float
        get() {
            val second = secondHue ?: return familyChroma
            var best = familyChroma
            for (swatch in swatches) {
                val h = swatch.h ?: continue
                if (hueDistance(h, second) <= FAMILY_HUE_DELTA) best = max(best, swatch.c)
            }
            return best
        }

    /** Lightness of the cluster that covers the most pixels — the wallpaper's overall "value". */
    val dominantLightness: Float
        get() = swatches.maxByOrNull { it.weight }?.l ?: 0.5f

    companion object {
        /** A wallpaper with no chromatic clusters: every tint is zero, the UI stays neutral. */
        val Achromatic = WallpaperPalette(hue = NEUTRAL_HUE, chroma = 0f, swatches = emptyList())
    }
}

/**
 * Hue used when the wallpaper has no chromatic clusters (`palette-core` reports NAN) and for
 * [WallpaperPalette.Achromatic]: cool blue-grey is the least opinionated default.
 */
const val NEUTRAL_HUE = 250f

/** Hue spread (`±`) that still counts as "the same family" as the dominant hue. */
private const val FAMILY_HUE_DELTA = 30f

/** Minimum hue distance for a cluster to be usable as the palette's second colour. */
private const val SECOND_HUE_MIN_DELTA = 40f

/** Below this chroma a cluster is too grey to be a deliberate second hue. */
private const val SECOND_HUE_MIN_CHROMA = 0.02f

/** Shortest angular distance between two hue angles, in degrees (0..180). */
internal fun hueDistance(a: Float, b: Float): Float {
    val delta = abs(a - b) % 360f
    return if (delta > 180f) 360f - delta else delta
}

/** Shortest **signed** distance `from → to`, in degrees (-180..180). */
internal fun signedHueDelta(from: Float, to: Float): Float {
    val delta = ((to - from) % 360f + 540f) % 360f - 180f
    return delta
}
