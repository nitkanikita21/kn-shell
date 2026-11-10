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
import kn.gtk.cairo_rectangle
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

private val WEEKDAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Wall clock: returns ("10 Sun 14", ":", "03") so the colon can be animated separately. */
fun currentClockParts(): Triple<String, String, String> = memScoped {
    val now = alloc<LongVar>()
    time(now.ptr)
    val tm = localtime(now.ptr)?.pointed ?: return@memScoped Triple("--", ":", "--")
    val day = tm.tm_mday.toString().padStart(2, '0')
    val weekday = WEEKDAYS[tm.tm_wday.coerceIn(0, 7)]
    val hours = tm.tm_hour.toString().padStart(2, '0')
    val minutes = tm.tm_min.toString().padStart(2, '0')
    Triple("$day $weekday $hours", ":", minutes)
}

/** Wall-clock time as HH:MM (local time). */
fun currentTimeHm(): String = memScoped {
    val now = alloc<LongVar>()
    time(now.ptr)
    val tm = localtime(now.ptr)?.pointed ?: return@memScoped "--:--"
    "${tm.tm_hour.toString().padStart(2, '0')}:${tm.tm_min.toString().padStart(2, '0')}"
}

/** Mini equalizer: `values` are 0..1 heights for five vertical bars. */
fun drawEqualizer(
    cr: CPointer<cairo_t>,
    width: Int,
    height: Int,
    values: FloatArray,
    red: Double,
    green: Double,
    blue: Double,
) {
    cairo_set_source_rgba(cr, 0.0, 0.0, 0.0, 0.0)
    cairo_paint(cr)
    val bars = values.size
    val gap = 1.5
    val barWidth = (width - gap * (bars - 1)) / bars
    for (index in 0 until bars) {
        val barHeight = (height * values[index].coerceIn(0.08f, 1f)).toDouble()
        val x = index * (barWidth + gap)
        val y = height - barHeight
        cairo_set_source_rgba(cr, red, green, blue, 0.95)
        cairo_rectangle(cr, x, y, barWidth, barHeight)
        cairo_fill(cr)
    }
}

/** Rounded volume meter: `fraction` is 0..1. */
fun drawMeter(
    cr: CPointer<cairo_t>,
    width: Int,
    height: Int,
    fraction: Float,
    red: Double,
    green: Double,
    blue: Double,
) {
    cairo_set_source_rgba(cr, 0.0, 0.0, 0.0, 0.0)
    cairo_paint(cr)

    val radius = height / 2.0
    // track
    cairo_set_source_rgba(cr, 0.27, 0.28, 0.35, 1.0)
    cairo_arc(cr, radius, radius, radius, PI / 2, 3 * PI / 2)
    cairo_arc(cr, width - radius, radius, radius, -PI / 2, PI / 2)
    cairo_fill(cr)

    // filled part
    val clamped = fraction.coerceIn(0f, 1f)
    val fillWidth = (width * clamped).toDouble()
    if (fillWidth > 1.0) {
        cairo_set_source_rgba(cr, red, green, blue, 0.95)
        val half = height / 2.0
        cairo_arc(cr, half, half, half, PI / 2, 3 * PI / 2)
        val right = (fillWidth - half).coerceAtLeast(half)
        cairo_arc(cr, right, half, half, -PI / 2, PI / 2)
        cairo_fill(cr)
    }
}
