package dev.knshell.inspect

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Needs no display: textures are decoded and encoded in memory. */
class PngDiffTest {

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()

    private fun picture(width: Int, height: Int, color: (Int) -> Int): ByteArray =
        encodePixels(Pixels(width, height, IntArray(width * height, color)))

    @Test
    fun `pixels survive encoding and decoding`() {
        val pixels = intArrayOf(red, blue, blue, red)

        assertContentEquals(pixels, decodePng(encodePixels(Pixels(2, 2, pixels))).argb)
    }

    @Test
    fun `equal pictures have no difference picture`() {
        val result = comparePng(picture(4, 3) { red }, picture(4, 3) { red })

        assertTrue(result.isEqual)
        assertNull(result.diff)
    }

    @Test
    fun `differing pixels are counted and marked`() {
        val result = comparePng(picture(4, 3) { red }, picture(4, 3) { if (it < 2) blue else red })

        assertEquals(2, result.differingPixels)
        val marked = decodePng(assertNotNull(result.diff))
        assertEquals(0xFFFF00FF.toInt(), marked.argb[0])
        assertTrue(marked.argb[2] != 0xFFFF00FF.toInt())
    }

    @Test
    fun `a pixel only one picture has differs`() {
        val result = comparePng(picture(4, 3) { red }, picture(5, 3) { red })

        assertEquals(3, result.differingPixels)
        assertEquals(4 to 5, result.expectedWidth to result.actualWidth)
        assertEquals(5, decodePng(assertNotNull(result.diff)).width)
    }

    @Test
    fun `data that is no PNG is rejected`() {
        assertFailsWith<InspectException> { comparePng(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)) }
    }
}
