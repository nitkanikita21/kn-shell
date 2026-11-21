// ---------------------------------------------------------------------------
// Gamut fitting — `Color.oklchFitted` must trade chroma away, never hue.
// ---------------------------------------------------------------------------

package dev.knshell.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorOklchFittedTest {

    /** How far a colour is from grey: 0 for `#rrggbb` triplets of equal channels. */
    private fun Color.spread(): Int = maxOf(r, g, b) - minOf(r, g, b)

    @Test
    fun `in-gamut request is returned unchanged`() {
        assertEquals(Color.oklch(0.7f, 0.05f, 250f), Color.oklchFitted(0.7f, 0.05f, 250f))
        assertEquals(Color.oklch(0.9f, 0f, 120f), Color.oklchFitted(0.9f, 0f, 120f))
    }

    @Test
    fun `out-of-gamut request keeps its hue instead of clipping channels`() {
        // At L 0.167 sRGB allows only ~0.042 chroma around hue 126° (green) and ~0.05 around 253°
        // (blue). Asking for more used to clip the red channel to 0 and turn the colour muddy.
        val green = Color.oklchFitted(0.167f, 0.09f, 126f)
        assertTrue(green.g > green.r, "green channel must dominate at hue 126°, got $green")
        assertTrue(green.g > green.b, "blue channel must not dominate at hue 126°, got $green")

        val blue = Color.oklchFitted(0.167f, 0.09f, 253f)
        assertTrue(blue.b > blue.g && blue.b > blue.r, "blue channel must dominate at 253°, got $blue")

        val violet = Color.oklchFitted(0.167f, 0.30f, 295f)
        assertTrue(violet.b > violet.g && violet.r > violet.g, "hue 295° must stay violet, got $violet")
    }

    @Test
    fun `chroma is monotonic and saturates at the gamut wall`() {
        val requested = listOf(0.01f, 0.03f, 0.06f, 0.09f, 0.30f).map { Color.oklchFitted(0.167f, it, 126f) }
        requested.zipWithNext().forEach { (less, more) ->
            assertTrue(less.spread() <= more.spread(), "spread must not shrink: $less → $more")
        }
        assertEquals(requested[3], requested[4], "beyond the wall the result must stop changing")
    }

    @Test
    fun `fitted colour never leaves sRGB`() {
        val lums = listOf(0.05f, 0.15f, 0.4f, 0.7f, 0.95f)
        val hues = listOf(0f, 45f, 126f, 200f, 253f, 295f, 330f)
        for (l in lums) for (h in hues) {
            val c = Color.oklchFitted(l, 0.4f, h)
            assertTrue(c.r in 0..255 && c.g in 0..255 && c.b in 0..255, "out of range at l=$l h=$h: $c")
        }
    }
}
