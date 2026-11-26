// ---------------------------------------------------------------------------
// Pixel comparison of two PNGs, for visual tests. Decoding and encoding go through GdkTexture, which
// needs no display, so pictures can be compared anywhere.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.inspect

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kn.gtk.GError
import kn.gtk.GdkMemoryFormat
import kn.gtk.g_bytes_new
import kn.gtk.g_bytes_unref
import kn.gtk.g_error_free
import kn.gtk.g_object_unref
import kn.gtk.gdk_memory_texture_new
import kn.gtk.gdk_texture_download
import kn.gtk.gdk_texture_get_height
import kn.gtk.gdk_texture_get_width
import kn.gtk.gdk_texture_new_from_bytes

/** A picture as 32-bit pixels, row by row: alpha in the high byte, colours premultiplied by it. */
internal class Pixels(val width: Int, val height: Int, val argb: IntArray)

/** How two pictures differ. [diff] marks the differing pixels and is `null` when there are none. */
class PngDiff(
    val expectedWidth: Int,
    val expectedHeight: Int,
    val actualWidth: Int,
    val actualHeight: Int,
    /** Pixels that differ; a pixel only one picture has counts as differing. */
    val differingPixels: Int,
    val diff: ByteArray?,
) {
    val isEqual: Boolean get() = differingPixels == 0
}

/** Colour of a differing pixel in [PngDiff.diff]: opaque magenta. */
private const val MARK: Int = 0xFFFF00FF.toInt()

/**
 * Compares two PNGs pixel by pixel, with no tolerance. In the difference picture, differing pixels
 * are magenta over a dimmed grey copy of [expected]. Throws [InspectException] on unreadable data.
 */
fun comparePng(expected: ByteArray, actual: ByteArray): PngDiff {
    val before = decodePng(expected)
    val after = decodePng(actual)
    val width = maxOf(before.width, after.width)
    val height = maxOf(before.height, after.height)
    val marked = IntArray(width * height)
    var differing = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val old = before.at(x, y)
            val new = after.at(x, y)
            marked[y * width + x] = if (old == null || new == null || old != new) {
                differing++
                MARK
            } else {
                dimmed(old)
            }
        }
    }
    return PngDiff(
        before.width,
        before.height,
        after.width,
        after.height,
        differing,
        if (differing == 0) null else encodePixels(Pixels(width, height, marked)),
    )
}

private fun Pixels.at(x: Int, y: Int): Int? = if (x < width && y < height) argb[y * width + x] else null

/** An opaque grey a third as bright as [pixel], so that the marks stand out over it. */
private fun dimmed(pixel: Int): Int {
    val red = pixel ushr 16 and 0xFF
    val green = pixel ushr 8 and 0xFF
    val blue = pixel and 0xFF
    val grey = (red * 3 + green * 6 + blue) / 30
    return (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
}

internal fun decodePng(png: ByteArray): Pixels = memScoped {
    if (png.isEmpty()) throw InspectException("not a readable PNG: no data")
    val bytes = png.usePinned { g_bytes_new(it.addressOf(0), png.size.convert()) }
    val error = alloc<CPointerVar<GError>>()
    val texture = gdk_texture_new_from_bytes(bytes, error.ptr)
    g_bytes_unref(bytes)
    if (texture == null) {
        val reason = error.value?.pointed?.message?.toKString()
        error.value?.let { g_error_free(it) }
        throw InspectException("not a readable PNG: $reason")
    }
    try {
        val width = gdk_texture_get_width(texture)
        val height = gdk_texture_get_height(texture)
        val argb = IntArray(width * height)
        argb.usePinned { gdk_texture_download(texture, it.addressOf(0).reinterpret(), (width * 4).convert()) }
        Pixels(width, height, argb)
    } finally {
        g_object_unref(texture.reinterpret())
    }
}

internal fun encodePixels(pixels: Pixels): ByteArray {
    val bytes = pixels.argb.usePinned { g_bytes_new(it.addressOf(0), (pixels.argb.size * 4).convert()) }
    // The layout `gdk_texture_download` produces on a little-endian machine.
    val texture = gdk_memory_texture_new(
        pixels.width,
        pixels.height,
        GdkMemoryFormat.GDK_MEMORY_B8G8R8A8_PREMULTIPLIED,
        bytes,
        (pixels.width * 4).convert(),
    )
    g_bytes_unref(bytes)
    if (texture == null) throw InspectException("could not create a ${pixels.width}x${pixels.height} picture")
    try {
        return encodePng(texture.reinterpret())
    } finally {
        g_object_unref(texture.reinterpret())
    }
}
