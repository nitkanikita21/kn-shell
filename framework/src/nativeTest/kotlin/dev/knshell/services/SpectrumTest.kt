package dev.knshell.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SpectrumTest {

    @Test
    fun `buckets average the whole spectrum`() {
        // 16 bins; 8 buckets → pairs, 4 buckets → quadruples.
        val values = listOf(0, 100, 0, 100, 0, 100, 0, 100, 0, 100, 0, 100, 0, 100, 0, 100)
        assertEquals(4, bucketLevels(values, 4).size)
        bucketLevels(values, 4).forEach { assertEquals(0.5, it, 1e-6) }
        bucketLevels(values, 8).forEach { assertEquals(0.5, it, 1e-6) }
    }

    @Test
    fun `every bar count samples the full spectrum`() {
        val values = List(16) { it * 6 } // 0, 6, 12, … 90
        val six = bucketLevels(values, 6)
        val eight = bucketLevels(values, 8)
        assertEquals(6, six.size)
        assertEquals(8, eight.size)
        // Both approximations of the same rising ramp: first < last, bounded by the data.
        assertTrue(six.first() < six.last())
        assertTrue(eight.first() < eight.last())
        assertTrue(six.all { it in 0.0..1.0 })
        assertTrue(eight.all { it in 0.0..1.0 })
    }

    @Test
    fun `empty spectrum is flat`() {
        assertTrue(bucketLevels(emptyList(), 5).all { it == 0.0 })
    }

    @Test
    fun `more bars than bins still covers everything`() {
        val values = List(4) { 25 }
        val bars = bucketLevels(values, 10)
        assertEquals(10, bars.size)
        assertTrue(bars.all { it == 0.25 })
    }

    @Test
    fun `rejects non-positive counts`() {
        assertFailsWith<IllegalArgumentException> { bucketLevels(listOf(1, 2), 0) }
    }
}
