package dev.knshell.animation

import dev.knshell.compose.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimationTest {

    @Test
    fun `easing keeps endpoints`() {
        assertEquals(0f, Easing.Linear.eased(0f), 1e-6f)
        assertEquals(1f, Easing.Linear.eased(1f), 1e-6f)
        assertEquals(0f, Easing.OutCubic.eased(0f), 1e-6f)
        assertEquals(1f, Easing.OutCubic.eased(1f), 1e-6f)
        assertEquals(0f, Easing.InOutCubic.eased(0f), 1e-6f)
        assertEquals(1f, Easing.InOutCubic.eased(1f), 1e-6f)
    }

    @Test
    fun `easing is monotonic`() {
        for (easing in listOf(Easing.Linear, Easing.OutCubic, Easing.InOutCubic)) {
            var previous = -1f
            for (step in 0..20) {
                val value = easing.eased(step / 20f)
                assertTrue(value >= previous - 1e-6f, "$easing decreased at $step")
                previous = value
            }
        }
    }

    @Test
    fun `lerp interpolates`() {
        assertEquals(0f, lerp(0f, 10f, 0f), 1e-6f)
        assertEquals(10f, lerp(0f, 10f, 1f), 1e-6f)
        assertEquals(5f, lerp(0f, 10f, 0.5f), 1e-6f)
        assertEquals(5, lerp(0, 10, 0.5f))
    }

    @Test
    fun `lerpColor blends channels`() {
        val half = lerpColor(Color.hex(0x000000), Color.hex(0xFFFFFF), 0.5f)
        assertTrue(abs(half.r - 127) <= 1)
        assertTrue(abs(half.g - 127) <= 1)
        assertTrue(abs(half.b - 127) <= 1)
    }
}
