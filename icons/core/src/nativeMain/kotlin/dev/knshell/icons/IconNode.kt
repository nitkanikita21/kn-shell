@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.icons

import dev.knshell.compose.Color
import dev.knshell.compose.GtkNode
import dev.knshell.compose.Modifier
import dev.knshell.compose.applyModifierFromModule
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.reinterpret
import kn.gtk.GtkDrawingArea
import kn.gtk.cairo_t
import kn.gtk.gtk_drawing_area_new
import kn.gtk.gtk_drawing_area_set_content_height
import kn.gtk.gtk_drawing_area_set_content_width
import kn.gtk.gtk_drawing_area_set_draw_func
import kn.gtk.gtk_widget_queue_draw

/**
 * GTK drawing area that rasterizes one [IconRef] through librsvg.
 *
 * Mutated directly by the `Icon` composable and by the color-transition frame
 * loop — assigning [colorHex] alone repaints the widget, so an animated tint
 * costs zero recompositions.
 *
 * Sizing mirrors the `Image` primitive (`framework/src/nativeMain/kotlin/
 * dev/knshell/ui/primitives/Image.kt`): content width/height are pinned so a
 * parent Row/Column can never stretch the icon box. Containers still grow the
 * widget along their main axis, so the artwork is centered inside whatever
 * allocation arrives — see [RsvgCache.render].
 *
 * Node class, not a composable: `:icons:core` owns it because the renderer
 * lives here, but only `dev.knshell.compose.Icon` (same module) constructs it.
 *
 * @param ref icon painted on the next draw; the `Icon` composable swaps it and
 *   calls [requestRedraw].
 * @param size initial box edge in logical px, used until [resize].
 */
internal class IconNode(var ref: IconRef, size: Int) : GtkNode(gtk_drawing_area_new()!!) {

    /** Tint `#RRGGBB` written into the pack's `tintAttribute`; null = keep the pack's native colors. */
    var colorHex: String? = null

    /** `stroke-width` override in the document's user units; null = the pack's default. */
    var strokeWidth: Double? = null

    /** Target tint of the running tween as `#RRGGBB`; null = no tween requested. */
    var transitionTargetHex: String? = null

    /** Frame-clock timestamp in nanoseconds when the tween started; `0` = not running. */
    var transitionStartNs: Long = 0L

    /** Tween length in nanoseconds (`transitionMs * 1_000_000`); `0` paints immediately. */
    var transitionDurationNs: Long = 0L

    private var sizePx: Int = size

    // The draw func receives this as user_data; the widget keeps the node alive.
    @Suppress("unused")
    private val selfRef = StableRef.create(this)

    init {
        val area = widget.reinterpret<GtkDrawingArea>()
        pinSize(area, size)
        val self = selfRef
        val draw = staticCFunction { _: CPointer<GtkDrawingArea>?, cr: CPointer<cairo_t>?, w: Int, h: Int, data: COpaquePointer? ->
            val node = data?.asStableRef<IconNode>()?.get()
            if (cr != null && node != null) node.drawNow(cr, w, h)
            Unit
        }
        gtk_drawing_area_set_draw_func(area, draw.reinterpret(), self.asCPointer(), null)
    }

    /**
     * Resize the widget slot to [newSize]×[newSize] logical px (content request, same
     * mechanism as `Image`). No-op when the edge is already [newSize]; otherwise the
     * widget is repinned and redrawn on the next frame.
     */
    fun resize(newSize: Int) {
        if (newSize == sizePx) return
        sizePx = newSize
        pinSize(widget.reinterpret(), newSize)
        requestRedraw()
    }

    private fun pinSize(area: CPointer<GtkDrawingArea>, s: Int) {
        gtk_drawing_area_set_content_width(area, s)
        gtk_drawing_area_set_content_height(area, s)
    }

    /** Same modifier pipeline as every built-in DSL widget. */
    fun applyModifier(modifier: Modifier) {
        applyModifierFromModule(modifier)
    }

    /**
     * Queues one redraw of the drawing area (GTK main thread). Safe to call per frame —
     * a queued draw is coalesced by GTK, so a tint tween costs no recomposition.
     */
    fun requestRedraw() {
        gtk_widget_queue_draw(widget)
    }

    private fun drawNow(cr: CPointer<cairo_t>, w: Int, h: Int) {
        RsvgCache.render(widget, ref, cr, w, h, sizePx, colorHex?.let { hexToColor(it) }, strokeWidth)
    }
}

/**
 * `#RRGGBB` → opaque [Color]. Anything that is not exactly `#` + 6 hex digits yields
 * opaque black; invalid digits read as `0`, so this never throws.
 */
internal fun hexToColor(hex: String): Color {
    if (hex.length != 7 || hex[0] != '#') return Color(0, 0, 0)
    fun nibble(c: Char): Int = c.digitToIntOrNull(16) ?: 0
    return Color(
        (nibble(hex[1]) shl 4) or nibble(hex[2]),
        (nibble(hex[3]) shl 4) or nibble(hex[4]),
        (nibble(hex[5]) shl 4) or nibble(hex[6]),
    )
}
