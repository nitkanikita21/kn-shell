// ---------------------------------------------------------------------------
// Container nodes: `plain()` plus the GTK-backed nodes the layout composables
// build on — `GtkBoxNode` (native `KnBoxLayout` packing), the overlay-based
// `GtkOverlayNode` / `GtkStackNode` and the three-region `GtkCenterBoxNode`.
//
// These are internals of the DSL: only `GtkNode`, `GtkApplier` and `plain()`
// are part of the public API. Content nodes are in ComposeBasicNodes.kt.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
import kn.gtk.GtkBox
import kn.gtk.GtkCenterBox
import kn.gtk.GtkOrientation
import kn.gtk.GtkOverlay
import kn.gtk.GtkWidget
import kn.gtk.GTK_ALIGN_CENTER
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_insert_child_after
import kn.gtk.gtk_box_remove
import kn.gtk.gtk_center_box_get_center_widget
import kn.gtk.gtk_center_box_get_end_widget
import kn.gtk.gtk_center_box_get_start_widget
import kn.gtk.gtk_center_box_new
import kn.gtk.gtk_center_box_set_center_widget
import kn.gtk.gtk_center_box_set_end_widget
import kn.gtk.gtk_center_box_set_start_widget
import kn.gtk.gtk_overlay_add_overlay
import kn.gtk.gtk_overlay_new
import kn.gtk.gtk_overlay_remove_overlay
import kn.gtk.gtk_overlay_set_measure_overlay
import kn.gtk.gtk_widget_insert_before
import kn.gtk.gtk_widget_add_css_class
import kn.gtk.gtk_widget_set_halign
import kn.gtk.gtk_widget_set_valign
import dev.knshell.core.attachBoxLayout
import dev.knshell.core.forgetBoxChild
import dev.knshell.core.setBoxHomogeneous
import dev.knshell.core.setBoxOrientation
import dev.knshell.core.setBoxSpacing

/**
 * Marks a node as "plain".
 *
 * [PLAIN_CLASS] is the only hook of [PLAIN_CSS]: every DSL widget carries it, so the
 * theme never paints a background, border, shadow, padding or state highlight and the
 * whole visual definition comes from the [Modifier] DSL.
 *
 * Public so satellite widget modules (`:icons:core`) can register their own widgets through
 * the same DSL styling pipeline.
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
internal open class GtkBoxNode(widget: CPointer<GtkWidget>) : GtkNode(widget) {
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
 * size** (`gtk_overlay_set_measure_overlay` stays false), so the overlay can be as small as
 * its parent requests — e.g. a 16px circle whose label is drawn on top and clipped instead of
 * stretching the chip.
 *
 * `GtkOverlay` needs no main child for that: a main child is always measured, and with every
 * child in the overlay list and `measure-overlay` off the container measures 0×0 on its own
 * (plan L11 — the invisible spacer GtkBox an earlier version kept here was unnecessary, and it
 * also made the node report expand through a child nobody composed).
 *
 * Overlay children are centered horizontally and vertically.
 */
internal class GtkOverlayNode : GtkOverlayBaseNode(measureChildren = false, centerChildren = true)

/**
 * Shared plumbing for [GtkOverlayNode] and [GtkStackNode]: every child joins the overlay list.
 *
 * Which of the two behaviours applies is a property of the node, not of the child, so there is
 * no main-child slot to shuffle: a child that is remeasured, removed or reordered never has to
 * be evicted from `gtk_overlay_set_child()`.
 */
internal abstract class GtkOverlayBaseNode(
    private val measureChildren: Boolean,
    private val centerChildren: Boolean,
) : GtkNode(gtk_overlay_new()!!) {
    override fun attachChild(child: CPointer<GtkWidget>) {
        val overlay = widget.reinterpret<GtkOverlay>()
        if (centerChildren) {
            gtk_widget_set_halign(child, GTK_ALIGN_CENTER)
            gtk_widget_set_valign(child, GTK_ALIGN_CENTER)
        }
        gtk_overlay_add_overlay(overlay, child)
        gtk_overlay_set_measure_overlay(overlay, child, if (measureChildren) 1 else 0)
    }

    /** Paint order follows composition order, also for a child composed later than its siblings. */
    override fun attachChildAt(index: Int, child: CPointer<GtkWidget>) {
        attachChild(child)
        val next = children.getOrNull(index + 1)?.widget
        if (next != null) gtk_widget_insert_before(child, widget, next)
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        // Children can be conditional (`if (active) Text(...)`): without this the removed label
        // stays in the overlay and the next one is drawn on top of it.
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
 * Children paint in composition order, which is what makes a badge drawn after its icon land on
 * top. No child ever becomes GtkOverlay's main child (that one cannot be switched off from the
 * measured size), so composing at any index — including 0 — is just an overlay append.
 */
internal class GtkStackNode : GtkOverlayBaseNode(measureChildren = true, centerChildren = false)

/**
 * Which region of a [CenterBox] a node occupies.
 */
internal enum class CenterSlot { Start, Center, End }

/**
 * The horizontal box that hosts one region of a [CenterBox].
 *
 * A region is a real box, so it can hold several widgets without the caller wrapping them in a
 * `Row` (`start = { Clock(); Space(8) }`). The parent [GtkCenterBoxNode] reads [slot] off the
 * node instead of counting attach calls: the slot therefore survives `rebuildOrder()` (which
 * detaches and re-attaches every child) and composition order cannot shift a region to the
 * other side of the bar.
 */
internal class GtkCenterRegionNode(slot: CenterSlot) :
    GtkBoxNode(newBox(GtkOrientation.GTK_ORIENTATION_HORIZONTAL)) {
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
internal class GtkCenterBoxNode : GtkNode(gtk_center_box_new()!!) {
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
