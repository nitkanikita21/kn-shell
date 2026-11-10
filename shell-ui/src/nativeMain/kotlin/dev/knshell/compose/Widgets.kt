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
import kn.gtk.GTK_ALIGN_CENTER
import kn.gtk.GTK_ALIGN_FILL
import kn.gtk.GtkOverlay
import kn.gtk.gtk_overlay_add_overlay
import kn.gtk.gtk_overlay_remove_overlay
import kn.gtk.gtk_overlay_new
import kn.gtk.gtk_overlay_set_child
import kn.gtk.gtk_widget_set_halign
import kn.gtk.gtk_widget_set_hexpand
import kn.gtk.gtk_widget_set_valign
import kn.gtk.gtk_widget_set_vexpand
import kn.gtk.gtk_button_new
import kn.gtk.gtk_button_new_with_label
import kn.gtk.gtk_button_set_child
import kn.gtk.gtk_button_set_label
import kn.gtk.gtk_center_box_get_center_widget
import kn.gtk.gtk_center_box_get_end_widget
import kn.gtk.gtk_center_box_get_start_widget
import kn.gtk.gtk_center_box_new
import kn.gtk.gtk_center_box_set_center_widget
import kn.gtk.gtk_center_box_set_end_widget
import kn.gtk.gtk_center_box_set_start_widget
import kn.gtk.gtk_drawing_area_new
import kn.gtk.gtk_drawing_area_set_content_height
import kn.gtk.gtk_drawing_area_set_content_width
import kn.gtk.gtk_drawing_area_set_draw_func
import kn.gtk.PangoEllipsizeMode
import kn.gtk.gtk_label_new
import kn.gtk.gtk_label_set_ellipsize
import kn.gtk.gtk_label_set_lines
import kn.gtk.gtk_label_set_markup
import kn.gtk.gtk_label_set_wrap
import kn.gtk.gtk_label_set_xalign
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
internal fun <T : GtkNode> T.plain(): T {
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
/**
 * GtkOverlay where overlay children are painted on top but **do not affect the measured
 * size** (`gtk_overlay_set_measure_overlay` stays false). The node keeps an invisible
 * empty main child, so the overlay can be as small as its parent requests — e.g. a 16px
 * circle whose label is drawn on top and clipped instead of stretching the chip.
 *
 * Overlay children are centered horizontally and vertically.
 */
class GtkOverlayNode : GtkNode(gtk_overlay_new()!!) {
    init {
        val overlay = widget.reinterpret<GtkOverlay>()
        // Invisible main child: it alone defines the measured size (0x0), so the Overlay
        // itself is sized by the Modifier (size/min-size). No expand here: GtkBox aggregates
        // children's expand flags, and an expanding Overlay would stretch its parent (e.g. a
        // workspace chip inside a Row). Overlay children stay centered.
        gtk_overlay_set_child(overlay, gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!)
    }

    override fun attachChild(child: CPointer<GtkWidget>) {
        gtk_widget_set_halign(child, GTK_ALIGN_CENTER)
        gtk_widget_set_valign(child, GTK_ALIGN_CENTER)
        gtk_overlay_add_overlay(widget.reinterpret<GtkOverlay>(), child)
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        // Overlay children can be conditional (`if (active) Text(...)`): without this the
        // removed label stays in the overlay and the next one is added on top of it.
        gtk_overlay_remove_overlay(widget.reinterpret<GtkOverlay>(), child)
    }
}

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
        // Slots are dynamic: remove the matching region so conditional content does not leak.
        val box = widget.reinterpret<GtkCenterBox>()
        if (gtk_center_box_get_start_widget(box) == child) gtk_center_box_set_start_widget(box, null)
        if (gtk_center_box_get_center_widget(box) == child) gtk_center_box_set_center_widget(box, null)
        if (gtk_center_box_get_end_widget(box) == child) gtk_center_box_set_end_widget(box, null)
    }
}

class GtkLabelNode : GtkNode(gtk_label_new("")!!) {
    private var lastText: String = ""

    fun setText(text: String) {
        lastText = text
        gtk_label_set_label(widget.reinterpret<GtkLabel>(), text)
    }

    /** Pango markup: `<b>`, `<i>`, `<u>`, `<s>`, `<span foreground="#…">`, `<big>`, … */
    fun setMarkup(markup: String) {
        lastText = markup
        gtk_label_set_markup(widget.reinterpret<GtkLabel>(), markup)
    }

    /** Re-applies the last text with the requested markup mode. */
    fun refreshText(markup: Boolean) {
        if (markup) setMarkup(lastText) else setText(lastText)
    }

    fun setWrap(wrap: Boolean) {
        gtk_label_set_wrap(widget.reinterpret<GtkLabel>(), if (wrap) 1 else 0)
    }

    fun setEllipsize(enabled: Boolean) {
        val mode = if (enabled) PangoEllipsizeMode.PANGO_ELLIPSIZE_END else PangoEllipsizeMode.PANGO_ELLIPSIZE_NONE
        gtk_label_set_ellipsize(widget.reinterpret<GtkLabel>(), mode)
    }

    /** Horizontal text alignment inside the label: 0 = left, 0.5 = center, 1 = right. */
    fun setXAlign(align: Float) {
        gtk_label_set_xalign(widget.reinterpret<GtkLabel>(), align)
    }

    /** Maximum number of lines (-1 = unlimited, needs wrapping). */
    fun setLines(lines: Int) {
        gtk_label_set_lines(widget.reinterpret<GtkLabel>(), lines)
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

/**
 * Paints [content] on top of the container without letting it affect the measured size.
 *
 * ```kotlin
 * Box(Modifier.size(16, 16).radius(8).background(color).clipOverflow()) {
 *     Overlay(Modifier.fillMaxSize()) { Text("2", size = 10) }
 * }
 * ```
 */
@Composable
fun Overlay(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ComposeNode<GtkOverlayNode, GtkApplier>(
        factory = { GtkOverlayNode().plain() },
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

/**
 * A text label.
 *
 * [markup] enables Pango markup (`<b>bold</b>`, `<i>`, `<u>`, `<s>`,
 * `<span foreground="#ff0000">`, `<big>`, `<small>`, …); [wrap] allows multi-line text,
 * [ellipsize] truncates it with an ellipsis when the width is constrained, [align] is the
 * horizontal text alignment inside the label (0 = left, 0.5 = center, 1 = right).
 */
@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    markup: Boolean = false,
    wrap: Boolean = false,
    ellipsize: Boolean = false,
    align: Float = 0.5f,
    maxLines: Int = -1,
) {
    ComposeNode<GtkLabelNode, GtkApplier>(
        factory = { GtkLabelNode().plain() },
        update = {
            set(text) { value -> if (markup) setMarkup(value) else setText(value) }
            set(markup) { rich -> refreshText(rich) }
            set(wrap) { value -> setWrap(value) }
            set(ellipsize) { value -> setEllipsize(value) }
            set(align) { value -> setXAlign(value) }
            set(maxLines) { value -> setLines(value) }
            set(modifier) { value -> applyModifier(value) }
        },
    )
}

/** Convenience wrapper for [Label] with Pango [markup] enabled. */
@Composable
fun Markup(markup: String, modifier: Modifier = Modifier) {
    Label(markup, modifier, markup = true)
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
