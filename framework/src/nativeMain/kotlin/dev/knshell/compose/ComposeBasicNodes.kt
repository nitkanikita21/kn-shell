// ---------------------------------------------------------------------------
// Content nodes: the GTK widgets behind `Label` / `Markup`, `Button` (label and
// container variants), `Canvas` and `Slider`. All of them are DSL internals and
// only ever constructed by the composables in Widgets.kt.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

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
import kn.gtk.GtkFlowBox
import kn.gtk.GtkGrid
import kn.gtk.GtkLabel
import kn.gtk.GtkOrientation
import kn.gtk.GtkPolicyType
import kn.gtk.GtkScrolledWindow
import kn.gtk.GtkSelectionMode
import kn.gtk.GtkWidget
import kn.gtk.PangoEllipsizeMode
import kn.gtk.cairo_t
import kn.gtk.g_signal_connect_data
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_insert_child_after
import kn.gtk.gtk_box_new
import kn.gtk.gtk_box_remove
import kn.gtk.gtk_button_new
import kn.gtk.gtk_button_new_with_label
import kn.gtk.gtk_button_set_child
import kn.gtk.gtk_button_set_label
import kn.gtk.gtk_drawing_area_new
import kn.gtk.gtk_drawing_area_set_content_height
import kn.gtk.gtk_drawing_area_set_content_width
import kn.gtk.gtk_drawing_area_set_draw_func
import kn.gtk.gtk_flow_box_append
import kn.gtk.gtk_flow_box_insert
import kn.gtk.gtk_flow_box_new
import kn.gtk.gtk_flow_box_remove
import kn.gtk.gtk_flow_box_set_column_spacing
import kn.gtk.gtk_flow_box_set_max_children_per_line
import kn.gtk.gtk_flow_box_set_row_spacing
import kn.gtk.gtk_flow_box_set_selection_mode
import kn.gtk.gtk_grid_attach
import kn.gtk.gtk_grid_new
import kn.gtk.gtk_grid_remove
import kn.gtk.gtk_grid_set_column_spacing
import kn.gtk.gtk_grid_set_row_spacing
import kn.gtk.gtk_grid_layout_child_set_column
import kn.gtk.gtk_grid_layout_child_set_row
import kn.gtk.gtk_layout_manager_get_layout_child
import kn.gtk.gtk_widget_get_layout_manager
import kn.gtk.gtk_widget_get_parent
import kn.gtk.gtk_label_new
import kn.gtk.gtk_label_set_ellipsize
import kn.gtk.gtk_label_set_label
import kn.gtk.gtk_label_set_lines
import kn.gtk.gtk_label_set_markup
import kn.gtk.gtk_label_set_max_width_chars
import kn.gtk.gtk_label_set_wrap
import kn.gtk.gtk_label_set_xalign
import kn.gtk.gtk_range_set_value
import kn.gtk.gtk_scale_new_with_range
import kn.gtk.gtk_scrolled_window_new
import kn.gtk.gtk_scrolled_window_set_child
import kn.gtk.gtk_scrolled_window_set_policy
import kn.gtk.gtk_widget_queue_draw

internal class GtkLabelNode : GtkNode(gtk_label_new("")!!) {
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

    /**
     * Clamps the label width in average characters and enables end-ellipsis.
     * Unlike pixel clamps this follows the label's own font, so "…" appears exactly where
     * the text stops fitting — no manual per-font tuning. Pass -1 to disable.
     */
    fun setMaxWidthChars(chars: Int) {
        gtk_label_set_max_width_chars(widget.reinterpret<GtkLabel>(), chars)
        if (chars >= 0) {
            gtk_label_set_ellipsize(
                widget.reinterpret<GtkLabel>(),
                PangoEllipsizeMode.PANGO_ELLIPSIZE_END,
            )
        }
    }
}

internal class GtkButtonNode(text: String) : GtkNode(gtk_button_new_with_label(text)!!) {
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
internal class GtkButtonContainerNode : GtkNode(gtk_button_new()!!) {
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

    override fun attachChildAt(index: Int, child: CPointer<GtkWidget>) {
        val previous = children.getOrNull(index - 1)?.widget
        gtk_box_insert_child_after(contentBox.reinterpret<GtkBox>(), child, previous)
    }

    override fun attachChild(child: CPointer<GtkWidget>) {
        gtk_box_append(contentBox.reinterpret<GtkBox>(), child)
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        gtk_box_remove(contentBox.reinterpret<GtkBox>(), child)
    }
}

internal class GtkSliderNode(min: Float, max: Float, step: Float) :
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

internal class GtkCanvasNode(width: Int, height: Int) : GtkNode(gtk_drawing_area_new()!!) {

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

/**
 * Scrollable viewport over a single child. GTK's scrolled window holds exactly one child,
 * so several widgets must be wrapped in a Column.
 */
internal class GtkScrollNode : GtkNode(gtk_scrolled_window_new()!!) {
    private var hasChild = false

    fun configurePolicy(horizontal: Boolean, vertical: Boolean) {
        gtk_scrolled_window_set_policy(
            widget.reinterpret<GtkScrolledWindow>(),
            if (horizontal) GtkPolicyType.GTK_POLICY_AUTOMATIC else GtkPolicyType.GTK_POLICY_NEVER,
            if (vertical) GtkPolicyType.GTK_POLICY_AUTOMATIC else GtkPolicyType.GTK_POLICY_NEVER,
        )
    }

    override fun attachChild(child: CPointer<GtkWidget>) {
        check(!hasChild) { "Scroll accepts a single child; wrap several in a Column" }
        hasChild = true
        gtk_scrolled_window_set_child(widget.reinterpret<GtkScrolledWindow>(), child)
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        hasChild = false
        gtk_scrolled_window_set_child(widget.reinterpret<GtkScrolledWindow>(), null)
    }
}

/** Wrapping container: children flow left to right and wrap onto new lines. */
internal class GtkFlowNode : GtkNode(gtk_flow_box_new()!!) {
    init {
        // Plain layout, not a selectable list: no highlight on click.
        gtk_flow_box_set_selection_mode(widget.reinterpret<GtkFlowBox>(), GtkSelectionMode.GTK_SELECTION_NONE)
    }

    fun configure(spacing: Int, maxPerLine: Int) {
        val box = widget.reinterpret<GtkFlowBox>()
        gtk_flow_box_set_column_spacing(box, spacing.toUInt())
        gtk_flow_box_set_row_spacing(box, spacing.toUInt())
        gtk_flow_box_set_max_children_per_line(box, maxPerLine.toUInt())
    }

    override fun attachChildAt(index: Int, child: CPointer<GtkWidget>) {
        gtk_flow_box_insert(widget.reinterpret<GtkFlowBox>(), child, index)
    }

    override fun attachChild(child: CPointer<GtkWidget>) {
        gtk_flow_box_append(widget.reinterpret<GtkFlowBox>(), child)
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        gtk_flow_box_remove(widget.reinterpret<GtkFlowBox>(), child)
    }
}

/**
 * Table container (`GtkGrid`): children fill the cells row by row, [configure] says how many make a
 * row. Every column is as wide as its widest cell and every row as tall as its tallest one.
 */
internal class GtkGridNode : GtkNode(gtk_grid_new()!!) {
    private var columns = 1

    fun configure(columns: Int, spacing: Int) {
        this.columns = columns.coerceAtLeast(1)
        val grid = widget.reinterpret<GtkGrid>()
        gtk_grid_set_column_spacing(grid, spacing.coerceAtLeast(0).toUInt())
        gtk_grid_set_row_spacing(grid, spacing.coerceAtLeast(0).toUInt())
        place()
    }

    override fun attachChildAt(index: Int, child: CPointer<GtkWidget>) = attachChild(child)

    override fun attachChild(child: CPointer<GtkWidget>) {
        gtk_grid_attach(widget.reinterpret<GtkGrid>(), child, 0, 0, 1, 1)
        place()
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        gtk_grid_remove(widget.reinterpret<GtkGrid>(), child)
        place()
    }

    /** Cells are positions, not a sibling order: a move only changes where each child sits. */
    override fun rebuildOrder() = place()

    /** Puts every attached child into the cell its index in [children] stands for. */
    private fun place() {
        val manager = gtk_widget_get_layout_manager(widget) ?: return
        children.forEachIndexed { index, node ->
            // A child still being inserted, or just removed, has no cell yet.
            if (gtk_widget_get_parent(node.widget) != widget) return@forEachIndexed
            val cell = gtk_layout_manager_get_layout_child(manager, node.widget) ?: return@forEachIndexed
            gtk_grid_layout_child_set_column(cell.reinterpret(), index % columns)
            gtk_grid_layout_child_set_row(cell.reinterpret(), index / columns)
        }
    }
}
