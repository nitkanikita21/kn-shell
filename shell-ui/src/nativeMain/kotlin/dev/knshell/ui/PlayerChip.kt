@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.ui

import androidx.compose.runtime.Composable
import kotlinx.cinterop.CPointer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.knshell.compose.Canvas
import dev.knshell.compose.Color
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Overlay
import dev.knshell.compose.centerVertically
import dev.knshell.compose.fillMaxHeight
import dev.knshell.compose.fontSize
import dev.knshell.compose.onClick
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.radius
import dev.knshell.compose.size
import dev.knshell.compose.textColor
import dev.knshell.compose.width
import dev.knshell.services.ShellServices
import kn.gtk.cairo_arc
import kn.gtk.cairo_clip
import kn.gtk.cairo_close_path
import kn.gtk.cairo_new_sub_path
import kn.gtk.cairo_paint
import kn.gtk.cairo_pattern_add_color_stop_rgba
import kn.gtk.cairo_pattern_create_linear
import kn.gtk.cairo_pattern_destroy
import kn.gtk.cairo_restore
import kn.gtk.cairo_save
import kn.gtk.cairo_set_source
import kn.gtk.cairo_t
import kotlin.math.PI

private val TrackText = Color.hex(0x111111)

/**
 * Player chip: animated silver gradient with black track title (MPRIS).
 *
 * The gradient is drawn with cairo: a vertical silver ramp plus two diagonal sheen bands
 * that slowly travel across the chip, so the surface reads like brushed metal. Clicking
 * toggles play/pause.
 */
@Composable
fun PlayerChip(
    width: Int = 260,
    height: Int = 28,
    title: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val media by ShellServices.media.collectAsState()
    val track = title ?: media.title.ifBlank { "Nothing playing" }

    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { phase = (phase + 0.0035f) % 1f }
        }
    }

    // Read the state in composition: the draw lambda only receives a snapshot, so a
    // recomposition (and a redraw) is what makes the sheen move.
    val phaseSnapshot = phase

    Overlay(
        modifier = Modifier
            .size(width, height)
            .centerVertically()
            .radius(8)
            .pointerCursor()
            .onClick { onClick?.invoke() ?: ShellServices.togglePlayPause() },
    ) {
        Canvas(width, height) { cr, w, h ->
            drawAnimatedSilver(cr, w.toDouble(), h.toDouble(), 8.0, phaseSnapshot.toDouble())
        }

        Label(
            text = track,
            modifier = Modifier
                .fontSize(12)
                .textColor(TrackText)
                .width(width - 24),
            ellipsize = true,
            align = 0f,
        )
    }
}

/** Vertical silver ramp + two travelling diagonal sheen bands, clipped to rounded rect. */
private fun drawAnimatedSilver(
    cr: CPointer<cairo_t>,
    width: Double,
    height: Double,
    radius: Double,
    phase: Double,
) {
    cairo_save(cr)

    roundedRect(cr, 0.0, 0.0, width, height, radius)
    cairo_clip(cr)

    val base = cairo_pattern_create_linear(0.0, 0.0, 0.0, height)
    cairo_pattern_add_color_stop_rgba(base, 0.0, 0.97, 0.97, 0.98, 1.0)
    cairo_pattern_add_color_stop_rgba(base, 0.45, 0.84, 0.84, 0.86, 1.0)
    cairo_pattern_add_color_stop_rgba(base, 1.0, 0.56, 0.56, 0.59, 1.0)
    cairo_set_source(cr, base)
    cairo_paint(cr)
    cairo_pattern_destroy(base)

    sheenBand(cr, width, height, phase, alpha = 0.55)
    sheenBand(cr, width, height, (phase + 0.5) % 1.0, alpha = 0.18)

    cairo_restore(cr)
}

private fun sheenBand(
    cr: CPointer<cairo_t>,
    width: Double,
    height: Double,
    phase: Double,
    alpha: Double,
) {
    val band = width * 0.65
    val startX = -band + phase * (width + 2 * band)
    val gradient = cairo_pattern_create_linear(startX, 0.0, startX + band, height)
    cairo_pattern_add_color_stop_rgba(gradient, 0.0, 1.0, 1.0, 1.0, 0.0)
    cairo_pattern_add_color_stop_rgba(gradient, 0.5, 1.0, 1.0, 1.0, alpha)
    cairo_pattern_add_color_stop_rgba(gradient, 1.0, 1.0, 1.0, 1.0, 0.0)
    cairo_set_source(cr, gradient)
    cairo_paint(cr)
    cairo_pattern_destroy(gradient)
}

private fun roundedRect(
    cr: CPointer<cairo_t>,
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    radius: Double,
) {
    cairo_new_sub_path(cr)
    cairo_arc(cr, x + width - radius, y + radius, radius, -PI / 2.0, 0.0)
    cairo_arc(cr, x + width - radius, y + height - radius, radius, 0.0, PI / 2.0)
    cairo_arc(cr, x + radius, y + height - radius, radius, PI / 2.0, PI)
    cairo_arc(cr, x + radius, y + radius, radius, PI, 3.0 * PI / 2.0)
    cairo_close_path(cr)
}
