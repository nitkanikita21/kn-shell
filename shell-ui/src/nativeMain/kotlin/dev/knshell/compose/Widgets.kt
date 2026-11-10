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
import kn.gtk.gtk_button_new_with_label
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
import kn.gtk.gtk_widget_activate
import kn.gtk.gtk_widget_remove_css_class
import kn.gtk.gtk_widget_set_opacity
import kn.gtk.gtk_widget_set_size_request

// ---------------------------------------------------------------------------
// GTK widget nodes
// ---------------------------------------------------------------------------

open class GtkBoxNode(widget: CPointer<GtkWidget>) : GtkNode(widget) {
    private var stateClass: String? = null

    override fun attachChild(child: CPointer<GtkWidget>) {
        gtk_box_append(widget.reinterpret<GtkBox>(), child)
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        gtk_box_remove(widget.reinterpret<GtkBox>(), child)
    }

    fun setStateClass(cls: String?) {
        stateClass = widget.swapStateClass(stateClass, cls)
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

/** Common CSS state handling: a single "state class" that can be swapped. */
private fun CPointer<GtkWidget>.swapStateClass(current: String?, next: String?): String? {
    if (current == next) return current
    current?.let { gtk_widget_remove_css_class(this, it) }
    next?.let { gtk_widget_add_css_class(this, it) }
    return next
}

class GtkLabelNode : GtkNode(gtk_label_new("")!!) {
    private var stateClass: String? = null

    fun setText(text: String) {
        gtk_label_set_label(widget.reinterpret<GtkLabel>(), text)
    }

    fun setStateClass(cls: String?) {
        stateClass = widget.swapStateClass(stateClass, cls)
    }

    fun setOpacity(opacity: Float) {
        gtk_widget_set_opacity(widget, opacity.toDouble())
    }
}

class GtkButtonNode(text: String) : GtkNode(gtk_button_new_with_label(text)!!) {
    var onClick: (() -> Unit)? = null

    // Kept alive for the lifetime of the GTK widget: without this the StableRef
    // created below may be garbage-collected and the signal handler would receive
    // a dangling user_data pointer.
    @Suppress("unused")
    private val selfRef = StableRef.create(this)

    private var stateClass: String? = null

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

    fun setStateClass(cls: String?) {
        stateClass = widget.swapStateClass(stateClass, cls)
    }

    fun setMinWidth(width: Int) {
        gtk_widget_set_size_request(widget, width, 0)
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
            node?.drawCalls = (node?.drawCalls ?: 0) + 1
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

/** Debug-only registry used by the self-test to click workspace chips programmatically. */
object DebugWidgets {
    private val byText = mutableMapOf<String, CPointer<GtkWidget>>()

    fun register(text: String, widget: CPointer<GtkWidget>) {
        byText[text] = widget
    }

    /** Emits the `clicked` signal (gtk_widget_activate) on the widget with this label. */
    fun click(text: String): Boolean {
        val widget = byText[text] ?: return false
        gtk_widget_activate(widget)
        return true
    }
}

// ---------------------------------------------------------------------------
// Compose DSL — emitted through ComposeNode, applied by GtkApplier
// ---------------------------------------------------------------------------

@Composable
private fun gtkBox(
    vertical: Boolean,
    spacing: Int,
    cssClass: String?,
    stateClass: String?,
    content: @Composable () -> Unit,
) {
    ComposeNode<GtkBoxNode, GtkApplier>(
        factory = {
            val widget = gtk_box_new(
                if (vertical) GtkOrientation.GTK_ORIENTATION_VERTICAL else GtkOrientation.GTK_ORIENTATION_HORIZONTAL,
                spacing,
            )!!
            cssClass?.let { gtk_widget_add_css_class(widget, it) }
            GtkBoxNode(widget)
        },
        update = {
            set(stateClass) { value -> setStateClass(value) }
        },
    ) {
        content()
    }
}

@Composable
fun Column(spacing: Int = 8, cssClass: String? = null, stateClass: String? = null, content: @Composable () -> Unit) {
    gtkBox(vertical = true, spacing = spacing, cssClass = cssClass, stateClass = stateClass, content = content)
}

@Composable
fun Row(spacing: Int = 8, cssClass: String? = null, stateClass: String? = null, content: @Composable () -> Unit) {
    gtkBox(vertical = false, spacing = spacing, cssClass = cssClass, stateClass = stateClass, content = content)
}

@Composable
fun CenterBox(cssClass: String? = null, start: @Composable () -> Unit, center: @Composable () -> Unit, end: @Composable () -> Unit) {
    ComposeNode<GtkCenterBoxNode, GtkApplier>(
        factory = {
            val node = GtkCenterBoxNode()
            cssClass?.let { gtk_widget_add_css_class(node.widget, it) }
            node
        },
        update = {},
    ) {
        start()
        center()
        end()
    }
}

@Composable
fun Label(
    text: String,
    cssClass: String? = null,
    stateClass: String? = null,
    opacity: Float = 1f,
) {
    ComposeNode<GtkLabelNode, GtkApplier>(
        factory = {
            val node = GtkLabelNode()
            cssClass?.let { gtk_widget_add_css_class(node.widget, it) }
            node
        },
        update = {
            set(text) { value -> setText(value) }
            set(stateClass) { value -> setStateClass(value) }
            set(opacity) { value -> setOpacity(value) }
        },
    )
}

@Composable
fun Button(
    text: String,
    cssClass: String? = null,
    stateClass: String? = null,
    minWidth: Int = 0,
    onClick: () -> Unit = {},
) {
    ComposeNode<GtkButtonNode, GtkApplier>(
        factory = {
            val node = GtkButtonNode(text)
            cssClass?.let { gtk_widget_add_css_class(node.widget, it) }
            if (cssClass == "ws") DebugWidgets.register(text, node.widget)
            node
        },
        update = {
            set(text) { value -> setText(value) }
            set(stateClass) { value -> setStateClass(value) }
            set(minWidth) { value -> setMinWidth(value) }
            set(onClick) { value -> this.onClick = value }
        },
    )
}

@Composable
fun Canvas(width: Int, height: Int, cssClass: String? = null, onDraw: (CPointer<cairo_t>, Int, Int) -> Unit) {
    ComposeNode<GtkCanvasNode, GtkApplier>(
        factory = {
            val node = GtkCanvasNode(width, height)
            cssClass?.let { gtk_widget_add_css_class(node.widget, it) }
            node
        },
        update = {
            set(onDraw) { value ->
                drawFn = value
                requestRedraw()
            }
        },
    )
}

@Composable
fun Slider(
    value: Float,
    min: Float = 0f,
    max: Float = 100f,
    step: Float = 1f,
    cssClass: String? = null,
    onValueChange: (Float) -> Unit,
) {
    ComposeNode<GtkSliderNode, GtkApplier>(
        factory = {
            val node = GtkSliderNode(min, max, step)
            cssClass?.let { gtk_widget_add_css_class(node.widget, it) }
            node
        },
        update = {
            set(value) { newValue -> setValue(newValue) }
            set(onValueChange) { fn -> onChange = fn }
        },
    )
}
