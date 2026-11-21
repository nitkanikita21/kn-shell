// ---------------------------------------------------------------------------
// Colour — the `Color` value type (ARGB + CSS), its sRGB/OKLCH helpers and the
// `Catppuccin` Mocha palette used by the reference theme.
// ---------------------------------------------------------------------------

package dev.knshell.compose

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Bisection steps used by [Color.oklchFitted]; 20 halvings of 0..0.4 resolve ~4e-7 chroma. */
private const val GAMUT_SEARCH_STEPS = 20

/** Slack on the linear channels: a fitted colour may land a hair outside after rounding. */
private const val GAMUT_EPSILON = 1e-4f

/** A simple ARGB color with a CSS representation. */
data class Color(val r: Int, val g: Int, val b: Int, val alpha: Float = 1f) {

    /** This colour as GTK CSS accepts it: `rgba(r, g, b, a)` with every channel clamped to range. */
    fun toCss(): String {
        val red = r.coerceIn(0, 255)
        val green = g.coerceIn(0, 255)
        val blue = b.coerceIn(0, 255)
        return "rgba($red, $green, $blue, ${alpha.coerceIn(0f, 1f)})"
    }

    companion object {
        /** Opaque white. */
        val White = Color(255, 255, 255)

        /** Fully transparent (`alpha = 0`), for "no fill" backgrounds. */
        val Transparent = Color(0, 0, 0, 0f)

        /** `Color.hex(0x1E1E2E, alpha = 0.9f)` */
        fun hex(value: Long, alpha: Float = 1f): Color = Color(
            r = ((value shr 16) and 0xFF).toInt(),
            g = ((value shr 8) and 0xFF).toInt(),
            b = (value and 0xFF).toInt(),
            alpha = alpha,
        )

        /** OKLCH (l, c, h) → Color. `l` ∈ 0..1 lightness, `c` ∈ 0..~0.4 chroma, `h` in degrees. */
        fun oklch(l: Float, c: Float, h: Float, alpha: Float = 1f): Color {
            val (rLin, gLin, bLin) = oklchToLinear(l, c, h)

            return Color(
                r = (rLin.linearToSrgb().coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255),
                g = (gLin.linearToSrgb().coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255),
                b = (bLin.linearToSrgb().coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255),
                alpha = alpha,
            )
        }

        /**
         * OKLCH → Color that **stays inside sRGB**, by giving up chroma instead of hue.
         *
         * [oklch] clips every channel on its own, so an out-of-gamut `(l, c, h)` comes back as a
         * different colour: the hue drifts and saturated hues (green worst of all) lose most of
         * their punch. A theme that derives surfaces from a wallpaper hits this constantly — a dark
         * surface sits at L ≈ 0.16, where sRGB only allows chroma ≈ 0.04 for green and ≈ 0.05 for
         * blue. Bisecting on `c` keeps `l` and `h` exactly as asked and returns the most saturated
         * colour that is still representable, so a requested tint degrades gracefully instead of
         * turning a different hue.
         */
        fun oklchFitted(l: Float, c: Float, h: Float, alpha: Float = 1f): Color {
            val lightness = l.coerceIn(0f, 1f)
            val chroma = c.coerceAtLeast(0f)
            if (chroma == 0f || inGamut(oklchToLinear(lightness, chroma, h))) {
                return oklch(lightness, chroma, h, alpha)
            }
            var low = 0f
            var high = chroma
            repeat(GAMUT_SEARCH_STEPS) {
                val mid = (low + high) / 2f
                if (inGamut(oklchToLinear(lightness, mid, h))) low = mid else high = mid
            }
            return oklch(lightness, low, h, alpha)
        }
    }
}

/** OKLCH → linear-light sRGB, unclamped (the caller decides what to do out of gamut). */
private fun oklchToLinear(l: Float, c: Float, h: Float): Triple<Float, Float, Float> {
    val hRad = h * PI.toFloat() / 180f
    val a = c * cos(hRad)
    val b = c * sin(hRad)

    val l_ = l + 0.3963377774f * a + 0.2158037573f * b
    val m_ = l - 0.1055613458f * a - 0.0638541728f * b
    val s_ = l - 0.0894841775f * a - 1.2914855480f * b

    val l3 = l_ * l_ * l_
    val m3 = m_ * m_ * m_
    val s3 = s_ * s_ * s_

    return Triple(
        4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3,
        -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3,
        -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3,
    )
}

private fun inGamut(linear: Triple<Float, Float, Float>): Boolean {
    val (r, g, b) = linear
    return r >= -GAMUT_EPSILON && r <= 1f + GAMUT_EPSILON &&
        g >= -GAMUT_EPSILON && g <= 1f + GAMUT_EPSILON &&
        b >= -GAMUT_EPSILON && b <= 1f + GAMUT_EPSILON
}

private fun Float.linearToSrgb(): Float =
    if (this <= 0.0031308f) 12.92f * this
    else 1.055f * kotlin.math.exp(kotlin.math.ln(this.toDouble()) / 2.4).toFloat() - 0.055f

/** Catppuccin Mocha, used by the reference waybar theme. */
object Catppuccin {
    val Base = Color.hex(0x1E1E2E)
    val Mantle = Color.hex(0x181825)
    val Crust = Color.hex(0x11111B)
    val Text = Color.hex(0xCDD6F4)
    val Subtext = Color.hex(0xA6ADC8)
    val Surface0 = Color.hex(0x161925, 0.9f)
    val Surface1 = Color.hex(0x45475A)
    val Surface3 = Color.hex(0x394161)
    val Overlay = Color.hex(0x6C7086)
    val Accent = Color.hex(0xCBA6F7)
    val Blue = Color.hex(0x89B4FA)
    val Lavender = Color.hex(0xB4BEFE)
    val Sapphire = Color.hex(0x74C7EC)
    val Sky = Color.hex(0x89DCEB)
    val Teal = Color.hex(0x94E2D5)
    val Green = Color.hex(0xA6E3A1)
    val Yellow = Color.hex(0xF9E2AF)
    val Peach = Color.hex(0xFAB387)
    val Maroon = Color.hex(0xEBA0AC)
    val Red = Color.hex(0xF38BA8)
    val Mauve = Color.hex(0xCBA6F7)
    val Pink = Color.hex(0xF5C2E7)
    val Flamingo = Color.hex(0xF2CDCD)
    val Rosewater = Color.hex(0xF5E0DC)
}

// ---------------------------------------------------------------------------
// Modifier model — deliberately shaped like Compose UI's Modifier
// ---------------------------------------------------------------------------
