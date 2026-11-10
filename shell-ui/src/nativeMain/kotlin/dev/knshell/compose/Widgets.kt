@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kn.gtk.GtkBox
import kn.gtk.GtkButton
import kn.gtk.GtkDrawingArea
import kn.gtk.GtkLabel
import kn.gtk.GtkOrientation
import kn.gtk.GtkScale
import kn.gtk.GtkWidget
import kn.gtk.cairo_t
import kn.gtk.g_signal_connect_data
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_new
import kn.gtk.gtk_box_remove
import kn.gtk.gtk_button_new_with_label
import kn.gtk.gtk_button_set_label
import kn.gtk.gtk_drawing_area_new
import kn.gtk.gtk_drawing_area_set_content_height
import kn.gtk.gtk_drawing_area_set_content_width
import kn.gtk.gtk_drawing_area_set_draw_func
import kn.gtk.gtk_label_new
import kn.gtk.gtk_label_set_label
import kn.gtk.gtk_range_set_value
import kn.gtk.gtk_scale_new_with_range
import kn.gtk.gtk_widget_queue_draw

// ---------------------------------------------------------------------------
// GTK widget nodes
// ---------------------------------------------------------------------------

open class GtkBoxNode(widget: CPointer<GtkWidget>) : GtkNode(widget) {
    override fun attachChild(child: CPointer<GtkWidget>) {
        gtk_box_append(widget.reinterpret<GtkBox>(), child)
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        gtk_box_remove(widget.reinterpret<GtkBox>(), child)
    }
}

class GtkLabelNode : GtkNode(gtk_label_new("")!!) {
    fun setText(text: String) {
        gtk_label_set_label(widget.reinterpret<GtkLabel>(), text)
    }
}

class GtkButtonNode(text: String) : GtkNode(gtk_button_new_with_label(text)!!) {
    var onClick: (() -> Unit)? = null

    // Kept alive for the lifetime of the GTK widget: without this the StableRef
    // created below may be garbage-collected and the signal handler would receive
    // a dangling user_data pointer.
    @Suppress("unused")
    private val selfRef = StableRef.create(this)

    init {
        val ref = selfRef
        val handler = staticCFunction { _: COpaquePointer?, data: COpaquePointer? ->
            data?.asStableRef<GtkButtonNode>()?.get()?.onClick?.invoke()
            Unit
        }
        g_signal_connect_data(
            instance = widget,
            detailed_signal = "clicked",
            c_handler = handler.reinterpret(),
            data = ref.asCPointer(),
            destroy_data = null,
            connect_flags = 0u,
        )
    }

    fun setText(text: String) {
        gtk_button_set_label(widget.reinterpret<GtkButton>(), text)
    }
}

class GtkSliderNode(min: Float, max: Float, step: Float) :
    GtkNode(gtk_scale_new_with_range(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, min.toDouble(), max.toDouble(), step.toDouble())!!) {

    var onChange: ((Float) -> Unit)? = null

    private var programmaticUpdate = false

    @Suppress("unused")
    private val selfRef = StableRef.create(this)

    init {
        val self = selfRef
        val handler = staticCFunction { _: COpaquePointer?, data: COpaquePointer? ->
            val node = data?.asStableRef<GtkSliderNode>()?.get()
            if (node != null && !node.programmaticUpdate) {
                val value = kn.gtk.gtk_range_get_value(node.widget.reinterpret()).toFloat()
                node.onChange?.invoke(value)
            }
            Unit
        }
        g_signal_connect_data(
            instance = widget,
            detailed_signal = "value-changed",
            c_handler = handler.reinterpret(),
            data = self.asCPointer(),
            destroy_data = null,
            connect_flags = 0u,
        )
    }

    fun setValue(value: Float) {
        if (programmaticUpdate) return
        programmaticUpdate = true
        gtk_range_set_value(widget.reinterpret(), value.toDouble())
        programmaticUpdate = false
    }
}

class GtkCanvasNode(width: Int, height: Int) : GtkNode(gtk_drawing_area_new()!!) {

    /** Draw callback supplied by the composable; re-read on every GTK frame. */
    var drawFn: ((CPointer<cairo_t>, Int, Int) -> Unit)? = null

    /** Diagnostics: how many times GTK asked this area to draw. */
    var drawCalls: Int = 0

    // Retained for the lifetime of the widget: the draw function receives this
    // StableRef as user_data, so it must never be collected.
    @Suppress("unused")
    private val selfRef = StableRef.create(this)

    init {
        gtk_drawing_area_set_content_width(widget.reinterpret<GtkDrawingArea>(), width)
        gtk_drawing_area_set_content_height(widget.reinterpret<GtkDrawingArea>(), height)

        val self = selfRef
        val draw = staticCFunction { _: CPointer<GtkDrawingArea>?, cr: CPointer<cairo_t>?, w: Int, h: Int, data: COpaquePointer? ->
            val node = data?.asStableRef<GtkCanvasNode>()?.get()
            if (cr != null && node != null) {
                node.drawFn?.invoke(cr, w, h)
            }
            Unit
        }
        gtk_drawing_area_set_draw_func(widget.reinterpret(), draw.reinterpret(), self.asCPointer(), null)
    }

    fun requestRedraw() {
        gtk_widget_queue_draw(widget)
    }
}

// ---------------------------------------------------------------------------
// Compose DSL — emitted through ComposeNode, applied by GtkApplier
// ---------------------------------------------------------------------------

@Composable
fun Column(spacing: Int = 8, content: @Composable () -> Unit) {
    ComposeNode<GtkBoxNode, GtkApplier>(
        factory = { GtkBoxNode(gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, spacing)!!) },
        update = {},
    ) {
        content()
    }
}

@Composable
fun Row(spacing: Int = 8, content: @Composable () -> Unit) {
    ComposeNode<GtkBoxNode, GtkApplier>(
        factory = { GtkBoxNode(gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, spacing)!!) },
        update = {},
    ) {
        content()
    }
}

@Composable
fun Label(text: String) {
    ComposeNode<GtkLabelNode, GtkApplier>(
        factory = { GtkLabelNode() },
        update = {
            set(text) { value -> setText(value) }
        },
    )
}

@Composable
fun Button(text: String, onClick: () -> Unit) {
    ComposeNode<GtkButtonNode, GtkApplier>(
        factory = { GtkButtonNode(text) },
        update = {
            set(text) { value -> setText(value) }
            set(onClick) { value -> this.onClick = value }
        },
    )
}

@Composable
fun Canvas(width: Int, height: Int, onDraw: (CPointer<cairo_t>, Int, Int) -> Unit) {
    ComposeNode<GtkCanvasNode, GtkApplier>(
        factory = { GtkCanvasNode(width, height) },
        update = {
            set(onDraw) { value ->
                drawFn = value
                requestRedraw()
            }
        },
    )
}

@Composable
fun Slider(value: Float, min: Float = 0f, max: Float = 100f, step: Float = 1f, onValueChange: (Float) -> Unit) {
    ComposeNode<GtkSliderNode, GtkApplier>(
        factory = { GtkSliderNode(min, max, step) },
        update = {
            set(value) { newValue -> setValue(newValue) }
            set(onValueChange) { fn -> onChange = fn }
        },
    )
}
