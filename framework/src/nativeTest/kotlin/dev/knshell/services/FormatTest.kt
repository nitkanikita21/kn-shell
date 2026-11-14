package dev.knshell.services

import dev.knshell.ui.format
import dev.knshell.ui.formatBytes
import dev.knshell.ui.formatBytesPerSec
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
    fun `formats network throughput`() {
        assertEquals("0 B/s", formatBytesPerSec(0))
        assertEquals("512 B/s", formatBytesPerSec(512))
        assertEquals("1.0 KiB/s", formatBytesPerSec(1024))
        assertEquals("1.4 MiB/s", formatBytesPerSec(1_500_000))
        assertEquals("2.0 GiB/s", formatBytesPerSec(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `formats byte totals`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("900 B", formatBytes(900))
        assertEquals("1.0 KiB", formatBytes(1024))
        assertEquals("1.4 MiB", formatBytes(1_500_000))
        assertEquals("3.0 GiB", formatBytes(3L * 1024 * 1024 * 1024))
    }

    @Test
    fun `percent appends the sign`() {
        assertEquals("45%", 45f.percent())
        assertEquals("45.5%", 45.5f.percent(1))
    }
}
