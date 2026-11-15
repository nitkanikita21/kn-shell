@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kn.gtk.GdkFrameClock
import kn.gtk.GtkOrientation
import kn.gtk.GtkWidget
import kn.gtk.graphene_rect_t
import kn.gtk.gtk_box_new
import kn.gtk.gtk_widget_add_tick_callback
import kn.gtk.gtk_widget_compute_bounds
import kn.gtk.gtk_widget_get_root

/** Position and size of a widget, in its window's coordinates. */
data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Reports the bounds of the node's position every time they change.
 *
 * Zero-size node sampling `gtk_widget_compute_bounds` on every frame; used to build surface
 * input regions for bars whose surface grows while an island is expanded.
 */
@Composable
fun BoundsObserver(onBounds: (Bounds) -> Unit) {
    ComposeNode<GtkBoundsObserverNode, GtkApplier>(
        factory = { GtkBoundsObserverNode() },
        update = {
            set(onBounds) { value -> callback = value }
        },
    )
}

internal class GtkBoundsObserverNode : GtkNode(gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!) {

    internal var callback: (Bounds) -> Unit = {}

    private var last: Bounds? = null

    @Suppress("unused")
    private val selfRef = StableRef.create(this)

    init {
        val tick = staticCFunction { _: CPointer<GtkWidget>?, _: CPointer<GdkFrameClock>?, data: COpaquePointer? ->
            data?.asStableRef<GtkBoundsObserverNode>()?.get()?.sample()
            1 // G_SOURCE_CONTINUE
        }
        gtk_widget_add_tick_callback(widget, tick.reinterpret(), selfRef.asCPointer(), null)
    }

    private fun sample() {
        val root = gtk_widget_get_root(widget) ?: return
        memScoped {
            val rectangle = alloc<graphene_rect_t>()
            if (gtk_widget_compute_bounds(widget, root.reinterpret(), rectangle.ptr) == 0) return
            val bounds = Bounds(
                x = rectangle.origin.x.toInt(),
                y = rectangle.origin.y.toInt(),
                width = rectangle.size.width.toInt(),
                height = rectangle.size.height.toInt(),
            )
            // Only horizontal geometry is interesting for input regions: reporting every
            // frame while an island animates would recompose the shell needlessly (visible
            // as neighbour jitter).
            val previous = last
            if (previous == null || previous.x != bounds.x || previous.width != bounds.width) {
                last = bounds
                callback(bounds)
            }
        }
    }
}
