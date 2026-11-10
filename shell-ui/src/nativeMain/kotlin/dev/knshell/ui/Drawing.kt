@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.LongVar
import kn.gtk.cairo_arc
import kn.gtk.cairo_fill
import kn.gtk.cairo_paint
import kn.gtk.cairo_set_source_rgba
import kn.gtk.cairo_t
import platform.posix.localtime
import platform.posix.time
import kotlin.math.PI

/** Fills the drawing area with the panel background and paints one circle. */
fun drawCircle(
    cr: CPointer<cairo_t>,
    width: Int,
    height: Int,
    radius: Float,
    red: Double,
    green: Double,
    blue: Double,
) {
    cairo_set_source_rgba(cr, 0.07, 0.07, 0.10, 1.0)
    cairo_paint(cr)

    cairo_set_source_rgba(cr, red, green, blue, 0.95)
    cairo_arc(cr, width / 2.0, height / 2.0, radius.toDouble(), 0.0, 2.0 * PI)
    cairo_fill(cr)
}

/** Wall-clock time as HH:MM (local time). */
fun currentTimeHm(): String = memScoped {
    val now = alloc<LongVar>()
    time(now.ptr)
    val tm = localtime(now.ptr)?.pointed ?: return@memScoped "--:--"
    "${tm.tm_hour.toString().padStart(2, '0')}:${tm.tm_min.toString().padStart(2, '0')}"
}
