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
import dev.knshell.core.attachBoxLayout
import dev.knshell.core.forgetBoxChild
import dev.knshell.core.setBoxChildWeight
import dev.knshell.core.setBoxHomogeneous
import dev.knshell.core.setBoxOrientation
import dev.knshell.core.setBoxSpacing
import kotlinx.cinterop.rawValue
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_insert_child_after
import kn.gtk.gtk_widget_get_layout_manager
import kn.gtk.gtk_widget_get_parent
import kn.gtk.gtk_box_new
import kn.gtk.gtk_box_remove
import kn.gtk.GTK_ALIGN_CENTER
import kn.gtk.GTK_ALIGN_FILL
import kn.gtk.GtkOverlay
import kn.gtk.gtk_overlay_add_overlay
import kn.gtk.gtk_overlay_get_child
import kn.gtk.gtk_overlay_remove_overlay
import kn.gtk.gtk_overlay_new
import kn.gtk.gtk_overlay_set_child
import kn.gtk.gtk_overlay_set_measure_overlay
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
import kn.gtk.gtk_label_set_max_width_chars
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
/**
 * Marks a node as "plain" — public so satellite widget modules (`:icons:core`)
 * can register their own widgets through the same DSL styling pipeline.
 */
fun <T : GtkNode> T.plain(): T {
    gtk_widget_add_css_class(widget, PLAIN_CLASS)
    return this
}

/**
 * The framework's box container: a `GtkBox` whose packing is done by the native
 * `KnBoxLayout` manager (`rust-core/src/kn_box_layout.c`) instead of GtkBox's own final
 * `GtkBoxLayout`.
 *
 * A manager is the only way to express the parts of the Android/Compose layout contract GTK has
 * no API for: a per-child `weight`, a real height-for-width negotiation (`for_size` forwarded to
 * the children), main-axis alignment inside a claimed cell, and the Compose-owned animated size.
 * The widget stays a `GtkBox`, so its node tree, CSS name and child-management API
 * (`gtk_box_append`/`gtk_box_insert_child_after`) are untouched — only the *packing* moved, which
 * is why `spacing` must now be pushed to the manager rather than to `gtk_box_set_spacing()`.
 */
open class GtkBoxNode(widget: CPointer<GtkWidget>) : GtkNode(widget) {
    private val containerHandle: ULong = widget.rawValue.toLong().toULong()

    init {
        attachBoxLayout(containerHandle, AXIS_WIDTH, 0, false)
    }

    /** Called once by [gtkBox] right after the widget is created. */
    internal fun configureBox(vertical: Boolean, spacing: Int, homogeneous: Boolean) {
        setBoxOrientation(containerHandle, if (vertical) AXIS_HEIGHT else AXIS_WIDTH)
        setBoxSpacing(containerHandle, spacing.coerceAtLeast(0))
        setBoxHomogeneous(containerHandle, homogeneous)
    }

    /** Re-pushes every child's layout properties; covers the case where a child changed its
     * weight or cap without leaving the tree (its modifier is applied before this node's
     * recomposition runs). */
    private fun syncChildLayouts() {
        children.forEach { it.pushChildLayout() }
    }

    internal override fun applyModifier(modifier: Modifier) {
        super.applyModifier(modifier)
        // Our own chain changed nothing about the children, but theirs may have: a child that
        // changed its weight or its cap without leaving the tree is only visible through its node.
        syncChildLayouts()
    }

    /**
     * Order-aware insert: GTK appends by default, which breaks conditional siblings — the
     * new widget must land *before* the child that currently follows [index].
     */
    override fun attachChildAt(index: Int, child: CPointer<GtkWidget>) {
        val box = widget.reinterpret<GtkBox>()
        val previous = children.getOrNull(index - 1)?.widget
        gtk_box_insert_child_after(box, child, previous)
        // The child's own modifier was applied before it got a parent, so its layout properties
        // can only be pushed now (main-axis align needs no such revisit: the manager gives an
        // aligned child a cell itself — plan L1).
        children.lastOrNull { it.widget == child }?.pushChildLayout()
    }

    override fun attachChild(child: CPointer<GtkWidget>) {
        gtk_box_append(widget.reinterpret<GtkBox>(), child)
        children.lastOrNull { it.widget == child }?.pushChildLayout()
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        forgetBoxChild(containerHandle, child.rawValue.toLong().toULong())
        gtk_box_remove(widget.reinterpret<GtkBox>(), child)
    }

    private companion object {
        /** Axis constants of `KnBoxLayout` (`KN_AXIS_WIDTH` / `KN_AXIS_HEIGHT`). */
        const val AXIS_WIDTH = 0
        const val AXIS_HEIGHT = 1
    }
}

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

/**
 * Stacks children on top of each other and is as large as its **largest** child — Compose's
 * `Box`, and the z-stacking container [Box] deliberately is not (`GtkBox` block flow).
 *
 * GtkOverlay does the work: `measure-overlay` is switched on for every child, so each of them
 * contributes to the size instead of the container having to invent an invisible main child.
 * Every child is allocated the whole cell and decides its own position inside it with
 * `Modifier.align` (GTK fills by default, and GTK fills *both* axes, so a `Stack` child that
 * must keep its natural size needs `Modifier.align(h = Align.Start, v = Align.Start)`).
 *
 * Children paint in composition order (main child first, then the overlay list), which is what
 * makes a badge drawn after its icon land on top. A child composed at index 0 of a non-empty
 * stack joins the overlay list instead of evicting the current main child — GtkOverlay holds
 * exactly one of those, and evicting it would unparent a live widget.
 */
class GtkStackNode : GtkNode(gtk_overlay_new()!!) {
    override fun attachChildAt(index: Int, child: CPointer<GtkWidget>) = place(index, child)

    override fun attachChild(child: CPointer<GtkWidget>) = place(children.size - 1, child)

    private fun place(index: Int, child: CPointer<GtkWidget>) {
        val overlay = widget.reinterpret<GtkOverlay>()
        if (index == 0 && gtk_overlay_get_child(overlay) == null) {
            gtk_overlay_set_child(overlay, child)
        } else {
            gtk_overlay_add_overlay(overlay, child)
            gtk_overlay_set_measure_overlay(overlay, child, 1)
        }
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        val overlay = widget.reinterpret<GtkOverlay>()
        if (gtk_overlay_get_child(overlay) == child) {
            gtk_overlay_set_child(overlay, null)
        } else {
            gtk_overlay_remove_overlay(overlay, child)
        }
    }
}

/**
 * Which region of a [CenterBox] a node occupies.
 */
enum class CenterSlot { Start, Center, End }

/**
 * The horizontal box that hosts one region of a [CenterBox].
 *
 * A region is a real box, so it can hold several widgets without the caller wrapping them in a
 * `Row` (`start = { Clock(); Space(8) }`). The parent [GtkCenterBoxNode] reads [slot] off the
 * node instead of counting attach calls: the slot therefore survives `rebuildOrder()` (which
 * detaches and re-attaches every child) and composition order cannot shift a region to the
 * other side of the bar.
 */
class GtkCenterRegionNode(slot: CenterSlot) :
    GtkBoxNode(gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, 0)!!) {
    /** Set again on recomposition, so a region can move between slots. */
    var slot: CenterSlot = slot
}

/**
 * Three-region bar layout (`GtkCenterBox`): start / center / end.
 *
 * A child that is a [GtkCenterRegionNode] goes to the slot the region names. Any other child
 * falls back to composition order (`start`, `center`, `end`), and — because `GtkCenterBox`
 * holds exactly one widget per region — a fourth one takes the `end` slot instead of being
 * left unparented.
 *
 * Sizing is GTK's, not Compose's: the start and end regions get their natural width, the centre
 * region keeps its natural width too and the slack goes to a region with an expanding child
 * (`gtk_center_box_set_shrink_center_last()` defaults to TRUE — see `docs/shell-api.md`).
 */
class GtkCenterBoxNode : GtkNode(gtk_center_box_new()!!) {
    override fun attachChildAt(index: Int, child: CPointer<GtkWidget>) = place(child)

    override fun attachChild(child: CPointer<GtkWidget>) = place(child)

    private fun place(child: CPointer<GtkWidget>) {
        val box = widget.reinterpret<GtkCenterBox>()
        val node = children.firstOrNull { it.widget == child }
        // Region nodes carry their slot; a plain child is placed by its position in the child
        // list (never by a mutable call counter — `rebuildOrder()` re-attaches out of order).
        val slot = (node as? GtkCenterRegionNode)?.slot
            ?: CenterSlot.entries.getOrElse(children.indexOfFirst { it.widget == child }) { CenterSlot.End }
        when (slot) {
            CenterSlot.Start -> gtk_center_box_set_start_widget(box, child)
            CenterSlot.Center -> gtk_center_box_set_center_widget(box, child)
            CenterSlot.End -> gtk_center_box_set_end_widget(box, child)
        }
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
    homogeneous: Boolean,
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
            ).plain().also { it.configureBox(vertical, spacing, homogeneous) }
        },
        update = {
            set(vertical) { value -> configureBox(value, spacing, homogeneous) }
            set(spacing) { value -> configureBox(vertical, value, homogeneous) }
            set(homogeneous) { value -> configureBox(vertical, spacing, value) }
            set(modifier) { value -> applyModifier(value) }
        },
    ) {
        content()
    }
}

@Composable
fun Column(
    spacing: Int = 8,
    homogeneous: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    gtkBox(vertical = true, spacing = spacing, homogeneous = homogeneous,
        modifier = modifier, content = content)
}

@Composable
fun Row(
    spacing: Int = 8,
    homogeneous: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    gtkBox(vertical = false, spacing = spacing, homogeneous = homogeneous,
        modifier = modifier, content = content)
}

/**
 * Generic block-flow container — the "div" of this toolkit, **not** Compose's `Box`.
 *
 * Children stack vertically (block flow), each one a full-width row: use [Row] / [Column]
 * for flex layouts, [Stack] to put children on top of each other (Compose `Box`), [Overlay]
 * when the overlay children must not affect the measured size. All styling comes from the
 * [Modifier]: size, padding, background, radius, hover, etc. A plain visible bar surface is
 * just `Box(Modifier.align(h = Align.Fill).height(40).background(color)) { ... }`.
 *
 * Layer-shell windows are transparent: if nothing paints, the bar is invisible.
 */
@Composable
fun Box(modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    ComposeNode<GtkBoxNode, GtkApplier>(
        factory = {
            GtkBoxNode(
                gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!,
            ).plain().also { it.configureBox(vertical = true, spacing = 0, homogeneous = false) }
        },
        update = {
            set(modifier) { value -> applyModifier(value) }
        },
    ) {
        content()
    }
}

/**
 * Stacks [content] on top of each other: the container is as large as its largest child, and
 * every child is given the whole cell (this is Compose's `Box`; the widget named [Box] here is
 * the block-flow "div").
 *
 * ```kotlin
 * Stack(Modifier.padding(4)) {
 *     Icon(Lucide.Bell)                                        // badge under
 *     Text("3", Modifier.align(h = Align.End, v = Align.Start)) // badge on top
 * }
 * ```
 *
 * A child fills the cell unless `Modifier.align` says otherwise — GTK fills both axes by
 * default, so a child that must keep its natural size asks for `Align.Start` (or `Center`).
 */
@Composable
fun Stack(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    ComposeNode<GtkStackNode, GtkApplier>(
        factory = { GtkStackNode().plain() },
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
 *     Overlay(Modifier.align(Align.Fill)) { Text("2", size = 10) }
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
        // Each region is a node of its own, so its slot is structural rather than positional:
        // conditional content inside a region cannot move the others (plan L7).
        CenterRegion(CenterSlot.Start) { start() }
        CenterRegion(CenterSlot.Center) { center() }
        CenterRegion(CenterSlot.End) { end() }
    }
}

/** One region of a [CenterBox]: children compose into the region's own horizontal box. */
@Composable
private fun CenterRegion(slot: CenterSlot, content: @Composable () -> Unit) {
    ComposeNode<GtkCenterRegionNode, GtkApplier>(
        factory = { GtkCenterRegionNode(slot).plain() },
        update = {
            set(slot) { value -> this.slot = value }
        },
    ) {
        content()
    }
}

/**
 * A text label.
 *
 * [markup] enables Pango markup (`<b>bold</b>`, `<i>`, `<u>`, `<s>`,
 * `<span foreground="#ff0000">`, `<big>`, `<small>`, …); [wrap] allows multi-line text,
 * [ellipsize] truncates it with an ellipsis when the width is constrained, [align] is the
 * horizontal text alignment inside the label (0 = left, 0.5 = center, 1 = right),
 * [maxWidthChars] clamps the width in characters and forces end-ellipsis (font-aware,
 * unlike pixel clamps).
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
    maxWidthChars: Int = -1,
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
            set(maxWidthChars) { value -> setMaxWidthChars(value) }
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
