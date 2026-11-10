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
import kn.gtk.GtkCenterBox
import kn.gtk.GtkDrawingArea
import kn.gtk.GtkLabel
import kn.gtk.GtkOrientation
import kn.gtk.GtkWidget
import kn.gtk.cairo_t
import kn.gtk.g_signal_connect_data
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_new
import kn.gtk.gtk_box_remove
import kn.gtk.gtk_button_new
import kn.gtk.gtk_button_new_with_label
import kn.gtk.gtk_button_set_child
import kn.gtk.gtk_button_set_label
import kn.gtk.gtk_center_box_new
import kn.gtk.gtk_center_box_set_center_widget
import kn.gtk.gtk_center_box_set_end_widget
import kn.gtk.gtk_center_box_set_start_widget
import kn.gtk.gtk_drawing_area_new
import kn.gtk.gtk_drawing_area_set_content_height
import kn.gtk.gtk_drawing_area_set_content_width
import kn.gtk.gtk_drawing_area_set_draw_func
import kn.gtk.gtk_label_new
import kn.gtk.gtk_label_set_label
import kn.gtk.gtk_range_set_value
import kn.gtk.gtk_scale_new_with_range
import kn.gtk.gtk_widget_add_css_class
import kn.gtk.gtk_widget_queue_draw

// ---------------------------------------------------------------------------
// GTK widget nodes
// ---------------------------------------------------------------------------

/**
 * Marks a freshly created node as "plain".
 *
 * [PLAIN_CLASS] is the only hook of [PLAIN_CSS]: every DSL widget carries it, so the
 * theme never paints a background, border, shadow, padding or state highlight and the
 * whole visual definition comes from the [Modifier] DSL.
 */
private fun <T : GtkNode> T.plain(): T {
    gtk_widget_add_css_class(widget, PLAIN_CLASS)
    return this
}

open class GtkBoxNode(widget: CPointer<GtkWidget>) : GtkNode(widget) {
    override fun attachChild(child: CPointer<GtkWidget>) {
        gtk_box_append(widget.reinterpret<GtkBox>(), child)
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        gtk_box_remove(widget.reinterpret<GtkBox>(), child)
    }
}

/**
 * Three-region bar layout (`GtkCenterBox`): start / center / end.
 * Children are attached bottom-up in composition order (start, center, end).
 */
class GtkCenterBoxNode : GtkNode(gtk_center_box_new()!!) {
    private var attached = 0

    override fun attachChild(child: CPointer<GtkWidget>) {
        val box = widget.reinterpret<GtkCenterBox>()
        when (attached) {
            0 -> gtk_center_box_set_start_widget(box, child)
            1 -> gtk_center_box_set_center_widget(box, child)
            2 -> gtk_center_box_set_end_widget(box, child)
        }
        attached += 1
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        // Static bar layout: no dynamic removal needed.
    }
}

class GtkLabelNode : GtkNode(gtk_label_new("")!!) {
    fun setText(text: String) {
        gtk_label_set_label(widget.reinterpret<GtkLabel>(), text)
    }
}

class GtkButtonNode(text: String) : GtkNode(gtk_button_new_with_label(text)!!) {
    var onClick: (() -> Unit)? = null

    // Kept alive for the lifetime of the GTK widget: without this the StableRef created
    // below may be garbage-collected and the handler would receive a dangling pointer.
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

/**
 * A button that hosts arbitrary widgets instead of a plain label.
 *
 * GTK buttons accept a single child, so the composable content is mounted into an
 * internal horizontal box.
 */
class GtkButtonContainerNode : GtkNode(gtk_button_new()!!) {
    var onClick: (() -> Unit)? = null

    private val contentBox = gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, 0)!!

    @Suppress("unused")
    private val selfRef = StableRef.create(this)

    init {
        gtk_button_set_child(widget.reinterpret<GtkButton>(), contentBox)

        val ref = selfRef
        val handler = staticCFunction { _: COpaquePointer?, data: COpaquePointer? ->
            data?.asStableRef<GtkButtonContainerNode>()?.get()?.onClick?.invoke()
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

    override fun attachChild(child: CPointer<GtkWidget>) {
        gtk_box_append(contentBox.reinterpret<GtkBox>(), child)
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        gtk_box_remove(contentBox.reinterpret<GtkBox>(), child)
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
private fun gtkBox(
    vertical: Boolean,
    spacing: Int,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    ComposeNode<GtkBoxNode, GtkApplier>(
        factory = {
            GtkBoxNode(
                gtk_box_new(
                    if (vertical) GtkOrientation.GTK_ORIENTATION_VERTICAL else GtkOrientation.GTK_ORIENTATION_HORIZONTAL,
                    spacing,
                )!!,
            ).plain()
        },
        update = {
            set(modifier) { value -> applyModifier(value) }
        },
    ) {
        content()
    }
}

@Composable
fun Column(spacing: Int = 8, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    gtkBox(vertical = true, spacing = spacing, modifier = modifier, content = content)
}

@Composable
fun Row(spacing: Int = 8, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    gtkBox(vertical = false, spacing = spacing, modifier = modifier, content = content)
}

/**
 * Generic container — the "div" of this toolkit.
 *
 * Children stack vertically (block flow); use [Row] / [Column] for flex layouts. All
 * styling comes from the [Modifier]: size, padding, background, radius, hover, etc.
 * A plain visible bar surface is just
 * `Box(Modifier.fillMaxWidth().height(40).background(color)) { ... }`.
 *
 * Layer-shell windows are transparent: if nothing paints, the bar is invisible.
 */
@Composable
fun Box(modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    ComposeNode<GtkBoxNode, GtkApplier>(
        factory = {
            GtkBoxNode(
                gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!,
            ).plain()
        },
        update = {
            set(modifier) { value -> applyModifier(value) }
        },
    ) {
        content()
    }
}

@Composable
fun CenterBox(modifier: Modifier = Modifier, start: @Composable () -> Unit, center: @Composable () -> Unit, end: @Composable () -> Unit) {
    ComposeNode<GtkCenterBoxNode, GtkApplier>(
        factory = { GtkCenterBoxNode().plain() },
        update = {
            set(modifier) { value -> applyModifier(value) }
        },
    ) {
        start()
        center()
        end()
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    ComposeNode<GtkLabelNode, GtkApplier>(
        factory = { GtkLabelNode().plain() },
        update = {
            set(text) { value -> setText(value) }
            set(modifier) { value -> applyModifier(value) }
        },
    )
}

@Composable
fun Button(text: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    ComposeNode<GtkButtonNode, GtkApplier>(
        factory = { GtkButtonNode(text).plain() },
        update = {
            set(text) { value -> setText(value) }
            set(onClick) { value -> this.onClick = value }
            set(modifier) { value -> applyModifier(value) }
        },
    )
}

/** Button with arbitrary content (e.g. a clickable pill with an icon and an equalizer). */
@Composable
fun Button(modifier: Modifier = Modifier, onClick: () -> Unit = {}, content: @Composable () -> Unit) {
    ComposeNode<GtkButtonContainerNode, GtkApplier>(
        factory = { GtkButtonContainerNode().plain() },
        update = {
            set(onClick) { value -> this.onClick = value }
            set(modifier) { value -> applyModifier(value) }
        },
    ) {
        content()
    }
}

@Composable
fun Canvas(width: Int, height: Int, modifier: Modifier = Modifier, onDraw: (CPointer<cairo_t>, Int, Int) -> Unit) {
    ComposeNode<GtkCanvasNode, GtkApplier>(
        factory = { GtkCanvasNode(width, height).plain() },
        update = {
            set(onDraw) { value ->
                drawFn = value
                requestRedraw()
            }
            set(modifier) { value -> applyModifier(value) }
        },
    )
}

@Composable
fun Slider(
    value: Float,
    min: Float = 0f,
    max: Float = 100f,
    step: Float = 1f,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
) {
    ComposeNode<GtkSliderNode, GtkApplier>(
        factory = { GtkSliderNode(min, max, step).plain() },
        update = {
            set(value) { newValue -> setValue(newValue) }
            set(onValueChange) { fn -> onChange = fn }
            set(modifier) { m -> applyModifier(m) }
        },
    )
}
