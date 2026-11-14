package dev.knshell.services

import dev.knshell.ui.format
import dev.knshell.ui.percent
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {

    @Test
    fun `formats with fixed decimals`() {
        assertEquals("1.5", 1.5f.format(1))
        assertEquals("2.00", 2f.format(2))
        assertEquals("0.0", 0f.format(1))
    }

    @Test
    fun `percent appends the sign`() {
        assertEquals("45%", 45f.percent())
        assertEquals("45.5%", 45.5f.percent(1))
    }
}
