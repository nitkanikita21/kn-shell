@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.knshell.compose.Canvas
import dev.knshell.compose.Color
import dev.knshell.compose.Modifier
import dev.knshell.services.Services
import dev.knshell.services.bucketLevels
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kn.gtk.cairo_fill
import kn.gtk.cairo_paint
import kn.gtk.cairo_rectangle
import kn.gtk.cairo_set_source_rgba
import kn.gtk.cairo_t

/**
 * CAVA audio spectrum rendered with cairo.
 *
 * Bars come from the Rust `spectrum` service (a `cava` child process in raw ASCII mode),
 * so this is a real audio visualiser, not a fake animation. The widget needs an explicit
 * size because it is a `GtkDrawingArea`.
 *
 * ```kotlin
 * Row { PlayerChip(); HSpace(8); CavaEqualizer(width = 80, height = 20, color = TrackText) }
 * ```
 */
@Composable
fun CavaEqualizer(
    modifier: Modifier = Modifier,
    width: Int = 96,
    height: Int = 20,
    bars: Int = 16,
    color: Color = Color.hex(0x111111),
    background: Color? = null,
) {
    val spectrum by Services.spectrum.state.collectAsState()

    // Read in composition: the draw lambda receives a snapshot, so a state change here is
    // what schedules the next redraw (same rule as for any Canvas animation).
    val values = spectrum

    Canvas(width, height, modifier) { cr, w, h ->
        drawEqualizer(cr, w.toDouble(), h.toDouble(), values, bars, color, background)
    }
}

private fun drawEqualizer(
    cr: CPointer<cairo_t>,
    width: Double,
    height: Double,
    values: List<Int>,
    requestedBars: Int,
    color: Color,
    background: Color?,
) {
    cairo_set_source_rgba(cr, 0.0, 0.0, 0.0, 0.0)
    cairo_paint(cr)

    if (background != null) {
        cairo_set_source_rgba(
            cr,
            background.r / 255.0,
            background.g / 255.0,
            background.b / 255.0,
            background.alpha.toDouble(),
        )
        cairo_rectangle(cr, 0.0, 0.0, width, height)
        cairo_fill(cr)
    }

    val count = requestedBars.coerceIn(1, 128)
    val gap = 1.5
    val barWidth = ((width - gap * (count - 1)) / count).coerceAtLeast(1.0)

    // Bucket the whole spectrum into `count` bars (average per group) instead of taking the
    // first N values. Without this, a 6-bar and an 8-bar equalizer would show different
    // slices of the spectrum and look unrelated even though the data is identical.
    val bucketLevels = bucketLevels(values, count)

    for (index in 0 until count) {
        val level = bucketLevels[index]
        val barHeight = (height - 1.0) * level
        if (barHeight <= 0.0) continue
        cairo_set_source_rgba(
            cr,
            color.r / 255.0,
            color.g / 255.0,
            color.b / 255.0,
            color.alpha.toDouble(),
        )
        cairo_rectangle(cr, index * (barWidth + gap), height - barHeight, barWidth, barHeight)
        cairo_fill(cr)
    }
}
