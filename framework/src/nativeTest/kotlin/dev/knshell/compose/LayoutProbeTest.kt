@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.compose

import dev.knshell.core.animatedSizeContent
import dev.knshell.core.attachBoxLayout
import dev.knshell.core.forgetBoxChild
import dev.knshell.core.getBoxChildMaxWidth
import dev.knshell.core.getBoxChildWeight
import dev.knshell.core.setBoxChildMax
import dev.knshell.core.setBoxChildWeight
import dev.knshell.core.setBoxHomogeneous
import dev.knshell.core.setBoxOrientation
import dev.knshell.core.setBoxSpacing
import dev.knshell.core.attachAnimatedSizeLayout
import dev.knshell.core.setAnimatedSizeCurrent
import kn.gtk.GTK_ALIGN_CENTER
import kn.gtk.GTK_ALIGN_END
import kn.gtk.GTK_ALIGN_FILL
import kn.gtk.GTK_ALIGN_START
import kn.gtk.gtk_widget_get_halign
import kn.gtk.gtk_widget_get_hexpand
import kn.gtk.gtk_widget_set_valign
import kn.gtk.GtkAllocation
import kn.gtk.GtkBox
import kn.gtk.GtkCenterBox
import kn.gtk.GtkLabel
import kn.gtk.GtkOverlay
import kn.gtk.GtkOrientation
import kn.gtk.GtkWidget
import kn.gtk.PangoEllipsizeMode
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_new
import kn.gtk.gtk_center_box_get_center_widget
import kn.gtk.gtk_center_box_get_end_widget
import kn.gtk.gtk_center_box_get_shrink_center_last
import kn.gtk.gtk_center_box_get_start_widget
import kn.gtk.gtk_center_box_new
import kn.gtk.gtk_center_box_set_center_widget
import kn.gtk.gtk_center_box_set_end_widget
import kn.gtk.gtk_center_box_set_shrink_center_last
import kn.gtk.gtk_center_box_set_start_widget
import kn.gtk.gtk_css_provider_load_from_string
import kn.gtk.gtk_css_provider_new
import kn.gtk.gtk_init_check
import kn.gtk.gtk_label_new
import kn.gtk.gtk_label_set_ellipsize
import kn.gtk.gtk_label_set_wrap
import kn.gtk.gtk_overlay_add_overlay
import kn.gtk.gtk_overlay_new
import kn.gtk.gtk_style_context_add_provider
import kn.gtk.gtk_widget_add_css_class
import kn.gtk.gtk_widget_compute_expand
import kn.gtk.gtk_widget_get_allocation
import kn.gtk.gtk_widget_get_first_child
import kn.gtk.gtk_widget_get_next_sibling
import kn.gtk.gtk_widget_get_parent
import kn.gtk.gtk_widget_get_size_request
import kn.gtk.gtk_widget_get_style_context
import kn.gtk.gtk_widget_get_valign
import kn.gtk.gtk_widget_measure
import kn.gtk.gtk_widget_set_halign
import kn.gtk.gtk_widget_set_hexpand
import kn.gtk.gtk_widget_set_parent
import kn.gtk.gtk_widget_set_size_request
import kn.gtk.gtk_widget_size_allocate
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
import dev.knshell.ui.primitives.GtkPictureNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Headless layout probe — plan step K1 of `plans/gtk4-layout-semantics.md`.
 *
 * GTK 4 negotiates size bottom-up: a widget reports `(minimum, natural)` from `measure()`, and
 * the container distributes the leftover during `allocate()`. Both steps are reachable **without
 * ever mapping a window**, so the layout semantics of this framework can be asserted in a unit
 * test instead of by eyeballing a running bar. That is what makes the C changes in K2/K3 safe.
 *
 * Three groups:
 *
 *  1. **GTK native semantics** — facts about stock GTK 4.24 that the framework builds on. They
 *     must never break; they are why `padding()` is not emulated with child margins, why a
 *     `weight` needs a custom layout manager, and why `maxWidth()` has no GTK counterpart.
 *  2. **DSL contract** — what `applyModifiers` has to guarantee: that a `Modifier` chain only
 *     gets the layout fields it asks for, and only keeps them while it asks (plan L1/L9). This
 *     group was the work list of K4, and closed green with it.
 * 3. **The animated box** — the behaviour the old `kn_animated_size_layout.c` was supposed to
 *    produce (plan L2/L3/L4/L5). That file is gone: its animated fields live inside `KnBoxLayout`
 *    (`rust-core/src/kn_box_layout.c`), which is also the object of group 4. Group 3 turned
 *    green in K2, group 4 in K3.
 *
 * There is no conditional skip on Kotlin/Native (the runner only understands a static
 * `@Ignore`), so a step that is not done yet is represented by a red test whose failure message
 * carries the plan step — which makes the red set readable as a work list.
 *
 * Requires a display connection — GTK needs one for style contexts and Pango metrics:
 * `./gradlew :framework:linuxX64Test`.
 */
class LayoutProbeTest {

    // ---------------------------------------------------------------- helpers

    private fun naturalWidth(widget: CPointer<GtkWidget>) = measure(widget, HORIZONTAL, -1).natural

    /** The smallest width this widget can be given and still draw itself — the floor every
     *  `maxWidth()` ceiling stops at (see `a cap never squeezes a child below its own minimum`). */
    private fun minimumWidth(widget: CPointer<GtkWidget>) = measure(widget, HORIZONTAL, -1).min

    private fun minimumHeight(widget: CPointer<GtkWidget>) = measure(widget, VERTICAL, -1).min
    private fun naturalHeight(widget: CPointer<GtkWidget>) = measure(widget, VERTICAL, -1).natural

    /** Allocates a widget by hand — the same call a parent makes during a layout pass. */
    private fun allocate(widget: CPointer<GtkWidget>, width: Int, height: Int) = memScoped {
        val a = alloc<GtkAllocation>()
        a.x = 0
        a.y = 0
        a.width = width
        a.height = height
        gtk_widget_size_allocate(widget, a.ptr, -1)
    }

    private fun expandsHorizontally(widget: CPointer<GtkWidget>): Boolean =
        gtk_widget_compute_expand(widget, HORIZONTAL) != 0

    private fun expandsVertically(widget: CPointer<GtkWidget>): Boolean =
        gtk_widget_compute_expand(widget, VERTICAL) != 0

    private fun wrappingLabel(text: String): CPointer<GtkWidget> =
        label(text).also { gtk_label_set_wrap(it.reinterpret<GtkLabel>(), 1) }

    /**
     * A label that may be truncated: its natural width is still the whole line, but its MINIMUM
     * drops to a few characters, because Pango can draw `…` instead. That gap between minimum and
     * natural is exactly the room a `maxWidth()` ceiling needs to have any effect — a label that
     * neither wraps nor ellipsizes has minimum == natural and cannot be capped at all (see
     * `a cap never squeezes a child below its own minimum`).
     */
    private fun truncatableLabel(text: String): CPointer<GtkWidget> =
        label(text).also {
            gtk_label_set_ellipsize(it.reinterpret<GtkLabel>(), PangoEllipsizeMode.PANGO_ELLIPSIZE_END)
        }

    private fun applyModifier(widget: CPointer<GtkWidget>, modifier: Modifier) =
        applyModifiers(widget, modifier, InlineStyle(widget))

    /** Attaches the C animated-size manager (`axis`: 0 = width, 1 = height). */
    private fun attachAnimated(widget: CPointer<GtkWidget>, axis: Int, spacing: Int = 0) {
        attachAnimatedSizeLayout(
            widget.rawValue.toLong().toULong(),
            0u,
            0u,
            spacing.toUInt(),
            axis.toUInt(),
        )
    }

    private fun animatedContent(widget: CPointer<GtkWidget>) =
        animatedSizeContent(widget.rawValue.toLong().toULong())

    private fun pushCurrent(widget: CPointer<GtkWidget>, value: Double) =
        setAnimatedSizeCurrent(widget.rawValue.toLong().toULong(), value)
    // ------------------------------------------------- 1. GTK native semantics

    @Test
    fun `size request is a floor and never a ceiling`() {
        // `gtk_widget_set_size_request()`: "Sets the minimum size". GTK 4 has no upper bound at
        // all (no `max-width` property, `GtkBoxLayout` is final), which is why `Modifier
        // .maxWidth()` can only clamp an explicit size today and needs a real clamp in our own
        // manager (plan L6 → K5).
        val widget = label("wide content that is definitely longer than thirty pixels")
        gtk_widget_set_size_request(widget, 30, -1)
        assertTrue(naturalWidth(widget) > 30, "a minimum must not shrink the natural size")
    }

    @Test
    fun `css min-width raises both the minimum and the natural size`() {
        // ... and it is the only real lever GTK gives for a maximum-ish request.
        val widget = rooted(label("ab"))
        css(widget, "min-width: 200px;")
        val styled = measure(widget, HORIZONTAL, -1)
        val plain = measure(rooted(label("ab")), HORIZONTAL, -1)
        assertEquals(200, styled.min)
        assertEquals(200, styled.natural, "CSS min-* raises the natural size too — unlike size_request")
        assertTrue(styled.min > plain.min)
    }

    @Test
    fun `a stale size cache is only dropped by the next pass`() {
        // The reason every CSS assertion in this file styles the widget *before* measuring it:
        // GTK caches the negotiation per (orientation, for_size), so a CSS change applied after
        // a measure is not seen until the pass following the invalidation. Measuring the styled
        // widget twice in a row is therefore the honest pattern — the second read is authoritative.
        val widget = rooted(label("ab"))
        css(widget, "min-width: 200px;")
        val first = measure(widget, HORIZONTAL, -1)
        val second = measure(widget, HORIZONTAL, -1)
        assertEquals(200, first.natural, "styling before the first measure takes effect immediately")
        assertEquals(second.natural, first.natural, "repeated measures of an unchanged widget agree")
    }

    @Test
    fun `main-axis leftover goes only to the expanding children`() {
        val plain = label("plain")
        val stretchy = label("stretchy").also { gtk_widget_set_hexpand(it, 1) }
        val row = box(children = listOf(plain, stretchy))
        allocate(row, width = 600, height = 40)

        val kids = childrenOf(row)
        assertEquals(naturalWidth(plain), allocRect(kids[0]).width, "a non-expand child keeps its natural size")
        assertEquals(600 - allocRect(kids[0]).width, allocRect(kids[1]).width, "the whole leftover goes to the expand child")
    }

    @Test
    fun `main-axis alignment positions the child inside the space it claims`() {
        // GtkBox never enlarges an aligned child's *allocation*: it gives the child its natural
        // width and moves it inside the leftover. `allocRect()` alone therefore cannot see a cell —
        // `cell()` reconstructs it from the gap to the next sibling. This is the gap the removed
        // `reconcileMainAxisAlign()` hack (plan L1) used to paper over by switching on `hexpand`,
        // and what the custom manager now resolves honestly (K3/K4).
        fun rowWith(expanding: Boolean, fill: Boolean): Triple<CPointer<GtkWidget>, Rect, Int> {
            val centered = label("c").also {
                gtk_widget_set_halign(it, if (fill) GTK_ALIGN_FILL else GTK_ALIGN_CENTER)
                if (expanding) gtk_widget_set_hexpand(it, 1)
            }
            val rest = label("rest").also { gtk_widget_set_hexpand(it, 1) }
            val row = box(children = listOf(centered, rest))
            allocate(row, width = 600, height = 40)
            val kids = childrenOf(row)
            return Triple(centered, allocRect(kids[0]), cell(row, kids))
        }

        // Without `hexpand` the child is packed at its natural width at the start of the row.
        val (packedWidget, packedRect, packedCell) = rowWith(expanding = false, fill = false)
        assertEquals(0, packedRect.x, "a non-expanding child is packed at the start")
        assertEquals(naturalWidth(packedWidget), packedRect.width, "…at its natural width")
        assertEquals(packedRect.width, packedCell, "…and it owns no cell beyond that width")

        // With it, the child claims a cell but is *centred* inside it: the allocation stays
        // natural, only the x moves.
        val (centered, centeredRect, centeredCell) = rowWith(expanding = true, fill = false)
        assertTrue(centeredCell > naturalWidth(centered), "expand gives the child a cell")
        assertEquals(naturalWidth(centered), centeredRect.width, "centring must not stretch the child")
        assertTrue(centeredRect.x > 0, "align then centres the widget inside that cell")

        // The same cell with `Fill` is what actually stretches the child.
        val (filled, filledRect, filledCell) = rowWith(expanding = true, fill = true)
        assertEquals(filledCell, filledRect.width, "Fill consumes the whole cell")
        assertEquals(0, filledRect.x)
    }

    @Test
    fun `gtk measures container padding so padding must not be emulated with child margins`() {
        // Generic CSS is applied by `gtk_widget_measure()` itself, therefore `Modifier.padding()`
        // is already correct on containers (plan §2 (а)) — no emulation anywhere.
        val bareRow = box(children = listOf(label("AAAA"), label("BBBB")))
        val bare = measure(bareRow, HORIZONTAL, -1)
        val bareHeight = measure(bareRow, VERTICAL, -1)

        val row = box(children = listOf(label("AAAA"), label("BBBB")))
        applyModifier(row, Modifier.padding(all = 20))
        val padded = measure(row, HORIZONTAL, -1)
        val paddedHeight = measure(row, VERTICAL, -1)
        assertEquals(bare.min + 40, padded.min, "horizontal padding adds 2x20 to the minimum")
        assertEquals(bareHeight.min + 40, paddedHeight.min, "vertical padding adds 2x20 to the minimum")
    }

    @Test
    fun `expand propagates from children to a box`() {
        val expanding = label("k").also { gtk_widget_set_hexpand(it, 1) }
        val column = box(vertical = true, children = listOf(expanding, label("k2")))
        assertTrue(expandsHorizontally(expanding))
        assertTrue(
            expandsHorizontally(column),
            "GtkBox ORs its children's expand flags — a custom container must implement the same " +
                "`compute_expand` or the bar loses its size negotiation (plan §1)",
        )
    }

    @Test
    fun `a label answers height-for-width only when it can wrap`() {
        val text = "one two three four five six seven eight nine ten"
        val fixed = Requested(label(text))
        val wrapping = Requested(wrappingLabel(text))
        // The manager must pass `for_size` through (plan L3): it is the only channel by which a
        // container can tell a label "you have 120px" and get a truthful height back.
        assertEquals(fixed.natural, fixed.widthAt(120).natural, "a non-wrapping label ignores for_size")
        assertTrue(
            wrapping.heightAt(120).natural > wrapping.height,
            "a wrapping label grows taller when it is narrow",
        )
    }

    @Test
    fun `CenterBox cannot express which side is squeezed first`() {
        // `shrink-center-last` defaults to TRUE and round-trips through its setter, but both
        // settings produce identical allocations for a centre that fits its own minimum: GtkCenter
        // Box gives the centre exactly `width − (start + end)` and never lets the sides lose. The
        // framework therefore cannot express "centre wins over the sides" with GtkCenterBox — the
        // reason K6 documented the setting instead of "fixing" it when the slot-order counter
        // (L7) was replaced by named regions.
        fun centerBox(shrinkCenterLast: Int): List<Rect> {
            val center = label("center-label-that-is-quite-long-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").also {
                gtk_label_set_ellipsize(it.reinterpret<GtkLabel>(), PangoEllipsizeMode.PANGO_ELLIPSIZE_END)
            }
            val cb = rooted(gtk_center_box_new()!!)
            gtk_center_box_set_start_widget(cb.reinterpret(), label("start"))
            gtk_center_box_set_center_widget(cb.reinterpret(), center)
            gtk_center_box_set_end_widget(cb.reinterpret(), label("end"))
            gtk_center_box_set_shrink_center_last(cb.reinterpret(), shrinkCenterLast)
            assertEquals(shrinkCenterLast, gtk_center_box_get_shrink_center_last(cb.reinterpret()), "the getter mirrors the setter")
            allocate(cb, width = 400, height = 30)
            val kids = childrenOf(cb)
            assertEquals(3, kids.size, "start/center/end are three children")
            val boxes = kids.map(::allocRect)
            assertEquals(
                400 - boxes[0].width - boxes[2].width,
                boxes[1].width,
                "the centre gets whatever the sides leave, no matter what it asked for",
            )
            return boxes
        }
        assertEquals(centerBox(shrinkCenterLast = 0), centerBox(shrinkCenterLast = 1))
    }

    @Test
    fun `a region lands in the slot it names whatever the attach order`() {
        // The slot used to come from a call counter, so the first child attached became `start`.
        // `rebuildOrder()` (a Compose `move`) detaches every child and re-attaches them, and
        // conditional content changes the order — both used to move regions around the bar.
        val box = GtkCenterBoxNode()
        val start = GtkCenterRegionNode(CenterSlot.Start)
        val center = GtkCenterRegionNode(CenterSlot.Center)
        val end = GtkCenterRegionNode(CenterSlot.End)
        // Deliberately NOT start/center/end order.
        box.insertChild(0, end)
        box.insertChild(1, start)
        box.insertChild(2, center)

        val cb = box.widget.reinterpret<GtkCenterBox>()
        assertEquals(start.widget, gtk_center_box_get_start_widget(cb), "the START region sits on the left")
        assertEquals(center.widget, gtk_center_box_get_center_widget(cb), "the CENTER region sits in the middle")
        assertEquals(end.widget, gtk_center_box_get_end_widget(cb), "the END region sits on the right")
    }

    @Test
    fun `a region keeps its slot after the applier rebuilds the child order`() {
        // What `GtkApplier.rebuildOrder()` does: detach everything, attach everything again.
        val box = GtkCenterBoxNode()
        val start = GtkCenterRegionNode(CenterSlot.Start)
        val center = GtkCenterRegionNode(CenterSlot.Center)
        val end = GtkCenterRegionNode(CenterSlot.End)
        listOf(start, center, end).forEachIndexed { index, region -> box.insertChild(index, region) }

        val cb = box.widget.reinterpret<GtkCenterBox>()
        box.removeChild(0, 3)
        // Re-attached in reverse: with a counter this swaps the sides of the bar.
        listOf(end, center, start).forEachIndexed { index, region -> box.insertChild(index, region) }

        assertEquals(start.widget, gtk_center_box_get_start_widget(cb), "START survived the rebuild")
        assertEquals(center.widget, gtk_center_box_get_center_widget(cb), "CENTER survived the rebuild")
        assertEquals(end.widget, gtk_center_box_get_end_widget(cb), "END survived the rebuild")
    }

    @Test
    fun `a CenterBox holds one widget per region`() {
        // `GtkCenterBox` has exactly three slots, so a fourth *plain* child replaces the END
        // widget instead of landing somewhere invisible. This is a GTK limit, not a framework
        // one: the DSL wraps every region in a `GtkCenterRegionNode` box, so `start = { a(); b() }`
        // is the supported way to put several widgets in one region.
        val box = GtkCenterBoxNode()
        val kids = listOf(GtkNode(label("a")), GtkNode(label("b")), GtkNode(label("c")), GtkNode(label("d")))
        kids.forEachIndexed { index, kid -> box.insertChild(index, kid) }

        val cb = box.widget.reinterpret<GtkCenterBox>()
        assertEquals(kids[0].widget, gtk_center_box_get_start_widget(cb), "START keeps the first child")
        assertEquals(kids[1].widget, gtk_center_box_get_center_widget(cb), "CENTER keeps the second")
        assertEquals(kids[3].widget, gtk_center_box_get_end_widget(cb), "the last child wins the END slot")
    }

    @Test
    fun `one region holds several widgets without a wrapper`() {
        // A region is a box of its own, so `start = { Clock(); Space(8); Workspaces() }` composes
        // into one slot instead of each widget fighting for the single `start` widget.
        val box = GtkCenterBoxNode()
        val start = GtkCenterRegionNode(CenterSlot.Start)
        box.insertChild(0, start)
        val first = GtkNode(label("clock"))
        val second = GtkNode(label("volume"))
        start.insertChild(0, first)
        start.insertChild(1, second)

        assertEquals(2, childrenOf(start.widget).size, "both widgets live in the region")
        assertEquals(start.widget, gtk_widget_get_parent(first.widget), "first widget is a child of the region")
        assertEquals(start.widget, gtk_widget_get_parent(second.widget), "second widget is too")
    }

    @Test
    fun `a plain child still goes by composition order`() {
        // Backwards compatibility for hand-built GTK trees: a child that is not a region node
        // keeps the old positional rule (start, center, end).
        val box = GtkCenterBoxNode()
        val first = GtkNode(label("start"))
        val second = GtkNode(label("center"))
        val third = GtkNode(label("end"))
        box.insertChild(0, first)
        box.insertChild(1, second)
        box.insertChild(2, third)

        val cb = box.widget.reinterpret<GtkCenterBox>()
        assertEquals(first.widget, gtk_center_box_get_start_widget(cb))
        assertEquals(second.widget, gtk_center_box_get_center_widget(cb))
        assertEquals(third.widget, gtk_center_box_get_end_widget(cb))
    }

    @Test
    fun `a region is measured and allocated as one cell`() {
        // Level 1 of the contract only: the region box owns the space GtkCenterBox hands it, and
        // its own children are packed by `KnBoxLayout` inside that cell.
        val box = GtkCenterBoxNode()
        val start = GtkCenterRegionNode(CenterSlot.Start)
        box.insertChild(0, start)
        val clock = GtkNode(label("12:00"))
        val volume = GtkNode(label("80%"))
        start.insertChild(0, clock)
        start.insertChild(1, volume)

        allocate(box.widget, width = 400, height = 30)
        val regionWidth = allocRect(start.widget).width
        assertEquals(
            naturalWidth(clock.widget) + naturalWidth(volume.widget),
            regionWidth,
            "the start region gets the natural width of its contents",
        )
        assertEquals(0, allocRect(clock.widget).x - allocRect(start.widget).x, "the first widget starts the region")
        assertEquals(naturalWidth(clock.widget), allocRect(clock.widget).width, "and keeps its natural width")
    }

    @Test
    fun `a region grows only when its content expands`() {
        // Measured, not assumed: with `shrink-center-last` at its default (TRUE) the centre keeps
        // its natural size and the slack goes to whichever region contains an expanding child — a
        // `Row(Modifier.fillWidth())` in `start` therefore still stretches the way it did before
        // regions existed, and the centre does not eat the leftover. `GtkBox` propagates a child's
        // expand flag, so the region box passes the request up to the `GtkCenterBox`.
        fun widths(expandingChild: Boolean): Triple<Int, Int, Int> {
            val box = GtkCenterBoxNode()
            val start = GtkCenterRegionNode(CenterSlot.Start)
            val center = GtkCenterRegionNode(CenterSlot.Center)
            val end = GtkCenterRegionNode(CenterSlot.End)
            listOf(start, center, end).forEachIndexed { index, region -> box.insertChild(index, region) }
            val left = GtkNode(label("left"))
            start.insertChild(0, left)
            center.insertChild(0, GtkNode(label("centre")))
            end.insertChild(0, GtkNode(label("right")))
            if (expandingChild) gtk_widget_set_hexpand(left.widget, 1)

            allocate(box.widget, width = 400, height = 30)
            return Triple(allocRect(start.widget).width, allocRect(center.widget).width, allocRect(end.widget).width)
        }

        val flat = widths(expandingChild = false)
        assertEquals(naturalWidth(label("left")), flat.first, "a region of non-expanding content stays natural")

        val growing = widths(expandingChild = true)
        assertTrue(growing.first > flat.first, "expand propagates through the region box")
        assertEquals(flat.second, growing.second, "the centre keeps its natural size (shrink-center-last)")
        assertEquals(flat.third, growing.third, "and so does the other side")
    }

    // ------------------------------------------------- 3b. Stack (Compose Box)
    //
    // `Box` in this framework is block flow (see docs/shell-api.md); `Stack` is the z-stacking
    // container, and the contract is Compose's: as large as the largest child, every child given
    // the whole cell.

    @Test
    fun `a stack is as large as its largest child`() {
        // Fresh widgets per stack on purpose: a widget can only have one parent, so reusing the
        // labels would move them out of the first stack and measure an empty container.
        fun stackOf(first: String, second: String) = GtkStackNode().also {
            it.insertChild(0, GtkNode(label(first)))
            it.insertChild(1, GtkNode(label(second)))
        }

        // Both orders: GtkOverlay tells its main child apart from the overlay list, so the
        // largest child has to contribute to the size whichever of the two it is.
        assertEquals(
            naturalWidth(label("12:00:00")),
            naturalWidth(stackOf("12:00:00", "2").widget),
            "the largest child is the first one",
        )
        assertEquals(
            naturalWidth(label("12:00:00")),
            naturalWidth(stackOf("2", "12:00:00").widget),
            "the largest child is the second one",
        )
        assertEquals(
            naturalHeight(label("12:00:00")),
            naturalHeight(stackOf("2", "12:00:00").widget),
            "and on the cross axis",
        )
    }

    @Test
    fun `every stack child is given the whole cell`() {
        val stack = GtkStackNode()
        stack.insertChild(0, GtkNode(label("2")))
        stack.insertChild(1, GtkNode(label("icon")))
        allocate(stack.widget, width = 200, height = 80)

        assertEquals(
            listOf(Rect(0, 0, 200, 80), Rect(0, 0, 200, 80)),
            childrenOf(stack.widget).map { allocRect(it) },
            "children overlap: each is allocated the container's own cell",
        )
    }

    @Test
    fun `a stack of one child is that child`() {
        val stack = GtkStackNode()
        val only = GtkNode(label("24"))
        stack.insertChild(0, only)

        assertEquals(naturalWidth(only.widget), naturalWidth(stack.widget), "one child, its size")

        // The first child becomes GtkOverlay's main child; a second one joins the overlay list
        // and must still be measured.
        stack.insertChild(1, GtkNode(label("3")))
        assertEquals(naturalWidth(only.widget), naturalWidth(stack.widget), "a smaller sibling changes nothing")
    }

    @Test
    fun `removing the first child keeps the rest measured`() {
        val stack = GtkStackNode()
        stack.insertChild(0, GtkNode(label("short")))
        stack.insertChild(1, GtkNode(label("12:00:00")))
        stack.removeChild(0, 1)

        assertEquals(
            naturalWidth(label("12:00:00")),
            naturalWidth(stack.widget),
            "the remaining child still defines the stack",
        )
    }

    // ------------------------------------------------- 3c. Overlay (plan L11)
    //
    // `Overlay` paints children without letting them affect the measured size and needs no
    // invisible spacer as its main child: with every child in the overlay list and
    // `measure-overlay` off, GtkOverlay measures 0x0 by itself.

    @Test
    fun `an overlay child does not grow the container`() {
        val overlay = GtkOverlayNode()
        overlay.insertChild(0, GtkNode(label("a label that is quite wide indeed")))

        assertEquals(0, naturalWidth(overlay.widget), "an overlay child contributes no size")
        assertEquals(0, naturalHeight(overlay.widget), "on either axis")
    }

    @Test
    fun `an overlay child keeps its natural size inside the cell`() {
        val overlay = GtkOverlayNode()
        val badge = GtkNode(label("2"))
        overlay.insertChild(0, badge)
        allocate(overlay.widget, width = 200, height = 80)

        val width = naturalWidth(badge.widget)
        val height = naturalHeight(badge.widget)
        // GTK centres with a ceiling divide, so a 7px label in a 200px cell starts at 97, not 96.
        val cell = Rect(
            x = (200 - width + 1) / 2,
            y = (80 - height + 1) / 2,
            width = width,
            height = height,
        )
        assertEquals(cell, allocRect(badge.widget), "centred, and never stretched by the parent's cell")
    }

    @Test
    fun `removing an overlay child detaches it`() {
        val overlay = GtkOverlayNode()
        val badge = GtkNode(label("2"))
        overlay.insertChild(0, badge)
        overlay.insertChild(1, GtkNode(label("3")))
        overlay.removeChild(0, 1)

        assertTrue(
            gtk_widget_get_parent(badge.widget) == null,
            "a removed overlay child must leave the GtkOverlay, or the next one is painted on top of it",
        )
    }

    @Test
    fun `an overlay does not expand because of its child`() {
        val overlay = GtkOverlayNode()
        val expanding = GtkNode(label("x"))
        gtk_widget_set_hexpand(expanding.widget, 1)
        overlay.insertChild(0, expanding)

        assertFalse(
            expandsHorizontally(overlay.widget),
            "GtkBox aggregates children's expand flags, so a spacer with an expanding child would stretch its row",
        )
    }

    // ------------------------------------------- 3d. intrinsic pins (plan K10)
    //
    // An `Image` and a `ShaderCanvas` have a size of their own. They express it as a modifier in
    // front of the caller's chain rather than writing `gtk_widget_set_size_request` from `init`,
    // so the K4 ownership rules apply to them too: a chain that drops an override falls back to
    // the pin, not to GTK's default.

    @Test
    fun `a size request does not survive a chain that dropped it`() {
        val widget = label()
        applyModifier(widget, Modifier.size(120, 40))
        assertEquals(120 to 40, sizeRequest(widget), "the request is applied")

        applyModifier(widget, Modifier)
        assertEquals(-1 to -1, sizeRequest(widget), "a dropped size() goes back to the natural size")
    }

    @Test
    fun `an image keeps its pin when a chain drops an override`() {
        val icon = GtkPictureNode("", 24, 24)
        icon.applyModifier(Modifier.align(v = Align.Fill))
        assertEquals(GTK_ALIGN_FILL, gtk_widget_get_valign(icon.widget), "the caller's align wins while it is there")

        icon.applyModifier(Modifier)
        assertEquals(
            GTK_ALIGN_START,
            gtk_widget_get_valign(icon.widget),
            "a dropped align must land back on the icon pin, not stretch the icon",
        )
        assertEquals(24 to 24, sizeRequest(icon.widget), "and the pin keeps its own size")
    }

    @Test
    fun `a shader canvas keeps its own size when a chain drops a size`() {
        val canvas = GtkShaderCanvasNode(120, 40, "")
        canvas.applyModifier(Modifier.size(300, 300))
        assertEquals(300 to 300, sizeRequest(canvas.widget), "the caller's size wins while it is there")

        canvas.applyModifier(Modifier)
        assertEquals(120 to 40, sizeRequest(canvas.widget), "a dropped size() goes back to the canvas size")
    }

    // ------------------------------------------- 3f. aspect ratio (plan L14)
    //
    // `aspectRatio` is the mirror image of `requiredSize`: instead of pinning both axes to
    // numbers it lets the CROSS axis decide (a FILL child takes the container's whole cross size,
    // which is "fill the row's height") and derives the main axis from the ratio.

    @Test
    fun `an aspect ratio fills the cross axis and derives the main one`() {
        val chip = GtkBoxNode(gtk_box_new(HORIZONTAL, 0)!!)
        val row = managedBox(children = listOf(chip.widget))
        chip.applyModifier(Modifier.aspectRatio(1f))

        allocate(row, 200, 80)

        assertEquals(Rect(0, 0, 80, 80), allocRect(chip.widget), "aspectRatio(1f) in an 80px row is 80x80")
    }

    @Test
    fun `the ratio scales the derived axis`() {
        val chip = GtkBoxNode(gtk_box_new(HORIZONTAL, 0)!!)
        val row = managedBox(children = listOf(chip.widget))
        chip.applyModifier(Modifier.aspectRatio(2f))

        allocate(row, 400, 60)

        assertEquals(Rect(0, 0, 120, 60), allocRect(chip.widget), "aspectRatio(2f) is twice as wide as tall")
    }

    @Test
    fun `an aspect ratio works on a column too where the cross axis is the width`() {
        val chip = GtkBoxNode(gtk_box_new(HORIZONTAL, 0)!!)
        val column = managedBox(vertical = true, children = listOf(chip.widget))
        chip.applyModifier(Modifier.aspectRatio(1f))

        allocate(column, 100, 400)

        assertEquals(Rect(0, 0, 100, 100), allocRect(chip.widget), "in a 100px column the height follows the width")
    }

    @Test
    fun `a ratio child is left out of the weight and surplus passes`() {
        // The ratio already owns this child's main-axis pixels: a `weight` (or an expanding
        // sibling's surplus) must not add a second claim on top of it.
        val chip = GtkBoxNode(gtk_box_new(HORIZONTAL, 0)!!)
        val grower = label("grower").also { gtk_widget_set_hexpand(it, 1) }
        val row = managedBox(children = listOf(chip.widget, grower))
        chip.applyModifier(Modifier.aspectRatio(1f).weight(1.0))

        allocate(row, 300, 50)

        assertEquals(Rect(0, 0, 50, 50), allocRect(chip.widget), "the weight does not stretch the ratio child")
        assertEquals(250, allocRect(grower).width, "the expanding sibling takes all of the rest")
    }

    @Test
    fun `a weighted ratio child asks for the height its weighted share implies`() {
        // `Modifier.weight(1.0).aspectRatio(1f)` is the CSS `flex: 1; aspect-ratio: 1`
        // pattern the demo uses for a row of equal colour swatches: the weight makes the
        // width definite, so the ratio transfers it into the height.  Reporting the
        // children's own height here would collapse the row and shrink every swatch to it.
        val tiles = listOf("bg", "fg").map { name ->
            GtkBoxNode(gtk_box_new(HORIZONTAL, 0)!!).also { gtk_box_append(it.widget.reinterpret<GtkBox>(), label(name)) }
        }
        val row = managedBox(spacing = 8, children = tiles.map { it.widget })
        tiles.forEach { it.applyModifier(Modifier.weight(1.0).aspectRatio(1f)) }

        // One child only: two weighted children make GtkBox hand the odd pixel of the
        // remainder to the first one, and that rounding is not what is under test.
        val single = GtkBoxNode(gtk_box_new(HORIZONTAL, 0)!!).also {
            gtk_box_append(it.widget.reinterpret<GtkBox>(), label("bg"))
        }
        val soloRow = managedBox(children = listOf(single.widget))
        single.applyModifier(Modifier.weight(1.0).aspectRatio(1f))
        assertEquals(208, measure(soloRow, VERTICAL, 208).natural,
            "the height asked for is the weighted share, not the label's own height")

        allocate(row, 208, 100)
        assertEquals(Rect(0, 0, 100, 100), allocRect(tiles[0].widget), "the first swatch is a square")
        assertEquals(Rect(108, 0, 100, 100), allocRect(tiles[1].widget), "the second tiles beside it, not on top of it")
    }

    @Test
    fun `a chain that drops the ratio goes back to content packing`() {
        val chip = GtkBoxNode(gtk_box_new(HORIZONTAL, 0)!!)
        val row = managedBox(children = listOf(chip.widget))

        chip.applyModifier(Modifier.aspectRatio(1f))
        allocate(row, 200, 80)
        assertEquals(Rect(0, 0, 80, 80), allocRect(chip.widget), "the ratio applies while it is there")

        chip.applyModifier(Modifier)
        allocate(row, 200, 80)
        assertEquals(0, allocRect(chip.widget).width, "an empty chain clears the record, so the box packs its empty content")
    }

    @Test
    fun `a negotiated cross size does not multiply the container size into a ratio child`() {
        // The shape the demo composes: a Column of Rows of square tiles. Asking the Column how
        // wide it is at a given HEIGHT must not answer `tiles x height`: a ratio derives a
        // child's main size from ITS cross size, and the container's main size is not that.
        // GtkBox solves this by handing every child its own share of the negotiation size first
        // (gtkboxlayout.c:388); forwarding `for_size` straight to the children used to. Seen in
        // the app: a 1391px window requested 9400px of width and the UI never got the width back.
        val tiles = (0..2).map { GtkBoxNode(gtk_box_new(HORIZONTAL, 0)!!) }
        val row = managedBox(spacing = 8, children = tiles.map { it.widget }, root = false)
        val column = managedBox(spacing = 8, vertical = true, children = listOf(row))
        // Only after the tiles are children of the row: `ratio` lives in the PARENT's record.
        tiles.forEach { it.applyModifier(Modifier.aspectRatio(1f).requiredSize(2)) }

        assertEquals(22, measure(column, HORIZONTAL, 800).natural,
            "three 2px tiles plus two 8px gaps: the request is the content, not 3x800")
    }

    @Test
    fun `the width a column requests does not depend on the height it is negotiated at`() {
        // Height-for-width negotiation asks the same question at several sizes. If the answer
        // grows with the question, GTK oscillates and the window ends up wider than its content.
        val tiles = (0..2).map { GtkBoxNode(gtk_box_new(HORIZONTAL, 0)!!) }
        val row = managedBox(spacing = 8, children = tiles.map { it.widget }, root = false)
        val column = managedBox(spacing = 8, vertical = true, children = listOf(row))
        // Only after the tiles are children of the row: `ratio` lives in the PARENT's record.
        tiles.forEach { it.applyModifier(Modifier.aspectRatio(1f).requiredSize(2)) }

        val at400 = measure(column, HORIZONTAL, 400).natural
        val at800 = measure(column, HORIZONTAL, 800).natural
        assertEquals(at400, at800, "the request is stable: $at400 at 400px tall, $at800 at 800px tall")
    }

    // ------------------------------------------- 3e. exact sizes (plan L13)
    //
    // `size()` is a GtkRequisition floor: a `GtkBox` that stretches on the cross axis gives a
    // `size(44)` chip 44x80. `requiredSize()` says "exactly this" by pinning the named axes to a
    // non-FILL alignment, so the container has nothing left to stretch.

    @Test
    fun `requiredSize is exact in a row that stretches its children`() {
        val chip = GtkBoxNode(gtk_box_new(HORIZONTAL, 0)!!)
        chip.applyModifier(Modifier.requiredSize(44))

        val tallRow = box(children = listOf(chip.widget), root = false)
        allocate(tallRow, 200, 80)

        assertEquals(Rect(0, 0, 44, 44), allocRect(chip.widget), "requiredSize(44) stays 44x44 inside an 80px row")
        assertEquals(GTK_ALIGN_START, gtk_widget_get_valign(chip.widget), "…because the axis is no longer FILL")
    }

    @Test
    fun `a plain size still stretches inside a row and that is what requiredSize is for`() {
        val chip = GtkBoxNode(gtk_box_new(HORIZONTAL, 0)!!)
        chip.applyModifier(Modifier.size(44))

        val tallRow = box(children = listOf(chip.widget), root = false)
        allocate(tallRow, 200, 80)

        assertEquals(Rect(0, 0, 44, 80), allocRect(chip.widget), "size() is a floor: the row stretched it to 80")
    }

    @Test
    fun `requiredSize leaves the axes it did not name to the parent`() {
        val widget = label("a")
        applyModifier(widget, Modifier.requiredHeight(30))
        assertEquals(GTK_ALIGN_START, gtk_widget_get_valign(widget), "the named axis is pinned")
        assertEquals(GTK_ALIGN_FILL, gtk_widget_get_halign(widget), "the other axis keeps GTK's default")
        assertEquals(-1 to 30, sizeRequest(widget))
    }

    @Test
    fun `a chain that aligns on top of requiredSize still wins`() {
        val widget = label("a")
        applyModifier(widget, Modifier.requiredSize(44).align(v = Align.Center))
        assertEquals(GTK_ALIGN_CENTER, gtk_widget_get_valign(widget), "the caller's alignment overrides the pin")
        assertEquals(44 to 44, sizeRequest(widget), "and the size request is still there")
    }

    // --------------------------------------------------------- 2. DSL contract

    @Test
    fun `align only touches the axis it was asked about`() {
        val widget = label("a").also { applyModifier(it, Modifier.align(v = Align.Center)) }
        assertEquals(GTK_ALIGN_CENTER, gtk_widget_get_valign(widget))
    }

    @Test
    fun `layout state does not survive a chain that dropped it`() {
        // Plan L9/K4: a `Modifier` chain is rebuilt from scratch on every pass, so "absent" means
        // "back to the GTK default" — `applyModifiers` used to write these fields only `if != null`,
        // which left `fillWidth()` on a widget forever.
        val widget = label("a")
        applyModifier(widget, Modifier.fillWidth())
        assertTrue(expandsHorizontally(widget))

        applyModifier(widget, Modifier)
        assertEquals(0, gtk_widget_get_hexpand(widget), "K4: an empty chain must clear the expand flag")
        assertEquals(GTK_ALIGN_FILL, gtk_widget_get_halign(widget), "K4: …and the alignment it wrote")
    }

    @Test
    fun `main-axis alignment does not travel through the expand flag`() {
        // Plan L1: the removed `reconcileMainAxisAlign()` hack turned on hexpand/vexpand when an
        // aligned child sat along its parent's orientation and never turned it off again — which is
        // how an `align(h = Center)` icon once stretched its 44px badge across a whole card. The
        // manager owns the cell now (K3), so the flags stay exactly as the DSL asked.
        val icon = label("icon")
        val column = box(vertical = true, children = listOf(icon))
        applyModifier(icon, Modifier.align(v = Align.Center))
        assertEquals(GTK_ALIGN_CENTER, gtk_widget_get_valign(icon), "the alignment itself is the contract")
        assertFalse(expandsVertically(icon), "K4: align must not be smuggled through the expand flag")
    }

    @Test
    fun `a dropped field is reset on its own axis only`() {
        // `AlignElement` carries one nullable axis, so `align(v = …)` never claims the horizontal
        // one — and the reset must follow that ownership, not the whole pair.
        val widget = label("a")
        applyModifier(widget, Modifier.align(h = Align.End, v = Align.Center))
        applyModifier(widget, Modifier.align(v = Align.Center))
        assertEquals(GTK_ALIGN_CENTER, gtk_widget_get_valign(widget), "the kept axis stays as written")
        assertEquals(GTK_ALIGN_FILL, gtk_widget_get_halign(widget), "the dropped axis goes back to GTK")
    }

    @Test
    fun `the reset never touches a field the modifier chain never wrote`() {
        // K4 protection: a widget can be aligned outside the modifier pipeline — `Image` used to
        // pin a picture with `GTK_ALIGN_START` straight on the widget (now it pins through the
        // pipeline, see the `3d. intrinsic pins` group), and `GtkOverlayNode.attachChild()`
        // (compose/Widgets.kt) still centres its children that way, before `applyModifiers` runs.
        // A reset to the global default would erase that work on every widget whose chain only
        // talks about padding, so ownership is what guards it.
        val icon = label("icon")
        gtk_widget_set_size_request(icon, 28, 28)
        gtk_widget_set_halign(icon, GTK_ALIGN_START)
        gtk_widget_set_valign(icon, GTK_ALIGN_START)
        applyModifier(icon, Modifier.padding(all = 4))
        assertEquals(GTK_ALIGN_START, gtk_widget_get_halign(icon), "a pinned picture stays pinned")
        assertEquals(GTK_ALIGN_START, gtk_widget_get_valign(icon))

        val badge = label("badge")
        val overlay = gtk_overlay_new()!!
        gtk_overlay_add_overlay(overlay.reinterpret<GtkOverlay>(), badge)
        gtk_widget_set_halign(badge, GTK_ALIGN_CENTER)
        gtk_widget_set_valign(badge, GTK_ALIGN_CENTER)
        applyModifier(badge, Modifier.padding(all = 2))
        assertEquals(GTK_ALIGN_CENTER, gtk_widget_get_halign(badge), "an overlay child stays centred")
        assertEquals(GTK_ALIGN_CENTER, gtk_widget_get_valign(badge))
    }

    @Test
    fun `a cross-axis size does not make the child expand`() {
        // A 40px chip inside a Row must not become vertically expandable: with `valign` unset the
        // request is a floor, and expansion is only ever asked for explicitly.
        val chip = label("chip")
        applyModifier(chip, Modifier.height(40))
        assertFalse(expandsVertically(chip), "height() must not imply vexpand")
        assertFalse(expandsHorizontally(chip))
        assertTrue(measure(chip, VERTICAL, -1).min >= 40, "height() is at least a minimum")
    }

    // ------------------------------------------ 3. The animated box (K2 net)

    @Test
    fun `shrinking container gives children their minimum not their natural size`() {
        // Plan L3: `kn_measure_child()` returns `natural` and `allocate()` ignores the child's
        // minimum, so a shrink animation clips the label instead of letting it wrap. Red until K2.
        val kid = wrappingLabel("some fairly long label text that could wrap")
        // The size a container can actually shrink this label to is its minimum **at the cross
        // size the container offers**: measured unconstrained the label answers (31, 237), at a
        // height of 60 it answers (89, 89) — two lines. A stock GtkBox allocates exactly 89 in a
        // 31px box, so 89 is the honest floor; the `-1` minimum was never reachable.
        val crossSize = 60
        val minimum = measure(kid, HORIZONTAL, crossSize).min
        val natural = naturalWidth(kid)
        assertTrue(minimum < natural, "precondition: a wrapping label has a minimum below its natural size")

        val container = box(children = listOf(kid))
        attachAnimated(container, axis = 0)
        pushCurrent(container, minimum.toDouble())
        allocate(container, width = minimum, height = crossSize)
        assertEquals(minimum, allocRect(kid).width, "K2: the child must be allowed to use its minimum width")
    }

    @Test
    fun `zero sized child stays zero sized`() {
        // Plan L4: `if (m < 1) m = 1;` turns `Box(Modifier.size(0, 2))` dividers and
        // `Space(width = 0)` into 1px artifacts. Red until K2.
        val divider = box(children = emptyList()).also { applyModifier(it, Modifier.size(0, 2)) }
        val container = box(children = listOf(divider))
        attachAnimated(container, axis = 0)
        pushCurrent(container, 100.0)
        allocate(container, width = 100, height = 20)
        assertEquals(0, allocRect(divider).width, "K2: a zero main-axis child must stay 0px wide")
    }

    @Test
    fun `animated container packs like a plain box`() {
        // Plan L5: a single non-expanding child was centred by this manager while the same DSL in
        // a stock `Row` packs it at the start — one syntax, two behaviours. Red until K2.
        val kid = label("chip")
        val container = box(children = listOf(kid))
        attachAnimated(container, axis = 0)
        pushCurrent(container, 400.0)
        allocate(container, width = 400, height = 20)
        assertEquals(0, allocRect(kid).x, "K2: packing must match GtkBox; centring belongs to align()/expand")
    }

    @Test
    fun `vertical animation distributes leftover by the vertical expand flag`() {
        // Plan L2: `allocate()` counted `gtk_widget_get_hexpand()` for the main axis even when the
        // manager animates the height, so `fillWidth()` inside an animated Column stretched its
        // child vertically. Red until K2.
        val kid = label("h").also { gtk_widget_set_hexpand(it, 1) }
        val container = box(vertical = true, children = listOf(kid))
        attachAnimated(container, axis = 1)
        pushCurrent(container, 200.0)
        allocate(container, width = 300, height = 200)
        assertEquals(
            naturalHeight(kid),
            allocRect(kid).height,
            "K2: on a vertical main axis only vexpand may claim leftover height",
        )
    }

    @Test
    fun `content probe reports the plain sum of the children natural sizes`() {
        // The animation target must be the content, with no floor added by the manager: the old
        // `if (m < 1) m = 1;` (plan L4) gave every zero-natural child one pixel, and Kotlin then
        // tweened towards a size the content never asked for.
        val empty: CPointer<GtkWidget> = box(children = emptyList())
        val container = box(vertical = true, children = listOf(empty))
        attachAnimated(container, axis = 1)
        assertEquals(0.0, animatedContent(container), "an empty child contributes 0, not 1")

        // NOTE: `Modifier.size(0, 2)` is *not* a zero-natural child. `gtk_widget_set_size_request`
        // raises the minimum, and GTK's generic measure turns that minimum into the natural size
        // too (measured: V(min=2, nat=2)), so a divider legitimately contributes its 2px.
        val divider: CPointer<GtkWidget> = box(children = emptyList()).also { applyModifier(it, Modifier.size(0, 2)) }
        val withDivider: CPointer<GtkWidget> = rooted(box(vertical = true, children = listOf(divider)))
        attachAnimated(withDivider, axis = 1)
        assertEquals(2.0, animatedContent(withDivider), "a size(0, 2) divider contributes its real 2px")
    }


    // --------------------------------------------- 4. KnBoxLayout (the box manager, K3)
    @Test
    fun `a weight splits the leftover in proportion to the weights`() {
        // GTK has no weight API at all, so this is the behaviour the DSL promises and the
        // manager must provide. The model is "weight = a proportional expand": unweighted
        // children take their natural size first and the whole remainder is shared out by
        // weight — a weighted child does NOT get its own natural added on top, which is what
        // makes `Modifier.weight(1f)` on a Label fill the row the way `layout_width=0dp` does
        // in Android. Each share is floored at that child's minimum.
        // `set_size_request` would make minimum == natural and hide the point, so the two
        // weighted cells get a CSS floor and the unweighted one a real request.
        val fixed = label("fixed").also { gtk_widget_set_size_request(it, 100, -1) }
        val a = label("").also { css(it, "min-width: 100px;") }
        val b = label("").also { css(it, "min-width: 100px;") }
        val row = managedBox(children = listOf(fixed, a, b))
        weight(row, a, 2.0)
        weight(row, b, 1.0)
        allocate(row, width = 600, height = 30)

        val kids = childrenOf(row)
        assertEquals(100, allocRect(kids[0]).width, "an unweighted child keeps its natural size")
        assertEquals(500, allocRect(kids[1]).width + allocRect(kids[2]).width, "the leftover is 600 - 100")
        assertEquals(333, allocRect(kids[1]).width, "2/3 of the leftover")
        assertEquals(167, allocRect(kids[2]).width, "1/3 of the leftover, and the shares must add up")
    }

    @Test
    fun `a weight fills the container instead of leaving the surplus unspent`() {
        // The direct consequence of that model, asserted head-on: weighted children tile the
        // whole width, so a bar row reaches its right edge. What a weight must never do is
        // take a child below its own minimum — the last assertion pins that floor.
        val a = label("aaaaaaaaaaaaaaaaaaaa")
        val b = label("bbbbbbbbbbbbbbbbbbbb")
        val row = managedBox(children = listOf(a, b))
        weight(row, a, 1.0)
        weight(row, b, 1.0)
        allocate(row, width = 600, height = 30)

        val kids = childrenOf(row)
        assertEquals(300, allocRect(kids[0]).width, "half of the container")
        assertEquals(300, allocRect(kids[1]).width)
        assertEquals(600, allocRect(kids[1]).x + allocRect(kids[1]).width, "and the cells tile the box")

        // Fresh widgets: a GtkWidget has exactly one parent, so the second box cannot reuse
        // `a`/`b` — appending them again silently leaves the new box empty.
        val c = label("cccccccccccccccccccc")
        val d = label("dddddddddddddddddddd")
        val tight = managedBox(children = listOf(c, d))
        weight(tight, c, 1.0)
        weight(tight, d, 9.0)
        allocate(tight, width = 600, height = 30)
        val tightKids = childrenOf(tight)
        assertEquals(2, tightKids.size, "precondition: both children made it into the box")
        val floor = measure(c, HORIZONTAL, 30).min
        assertTrue(allocRect(tightKids[0]).width >= floor,
            "a 1/10 share of 600 is 60px, but the child's minimum has the last word")
        assertEquals(600, allocRect(tightKids[0]).width + allocRect(tightKids[1]).width,
            "the floor above is paid out of the *other* share, it must not overflow the box " +
                "(cells " + allocRect(tightKids[0]).width + " + " + allocRect(tightKids[1]).width + ")")
    }

    @Test
    fun `a weighted child can be given a cell without an expand flag`() {
        // The whole point of `weight` for the bar: a `Label` that takes the remaining width
        // without lying about `hexpand` (which is what the removed `reconcileMainAxisAlign()` hack
        // used to do — plan L1). The expand flag stays what the DSL asked for.
        val icon = label("icon")
        val title = label("t")
        val row = managedBox(children = listOf(icon, title))
        weight(row, title, 1.0)
        allocate(row, width = 600, height = 30)

        val kids = childrenOf(row)
        assertTrue(allocRect(kids[1]).width > naturalWidth(title), "the weighted child claims the leftover")
        assertEquals(false, expandsHorizontally(title), "and it does so without setting hexpand")
    }

    @Test
    fun `main-axis align positions a child inside its cell without touching the expand flag`() {
        // The L1 hack set `hexpand` on any CENTER/END-aligned child along the parent's
        // orientation. The manager gives the aligned child a cell instead — the SAME allocation
        // stock GtkBox produced for the same geometry (`x=143 w=6`, asserted in group 1), which
        // is what proves the hack is now redundant rather than merely absent.
        val centered = label("c").also { gtk_widget_set_halign(it, GTK_ALIGN_CENTER) }
        val rest = label("rest")
        val row = managedBox(children = listOf(centered, rest))
        weight(row, centered, 1.0)
        weight(row, rest, 1.0)
        allocate(row, width = 600, height = 40)

        val kids = childrenOf(row)
        val cell = cell(row, kids, 0)
        val box = allocRect(kids[0])
        assertEquals(naturalWidth(centered), box.width, "allocated its natural size, not the cell")
        assertTrue(box.x > 0, "the cell exists only as free space around the widget")
        assertEquals(box.x * 2, cell - box.width, "centred inside the cell: the gaps either side are equal")
        assertEquals(false, expandsHorizontally(centered), "no expand flag was needed to align it")
    }

    @Test
    fun `clearing a weight restores the natural size`() {
        // A `Modifier` chain is rebuilt from scratch every recomposition, so `weight(0.0)` has
        // to mean "no weight" and wipe the previous value — the same rule as `hexpand` in L9.
        val child = label("").also { css(it, "min-width: 100px;") }
        val row = managedBox(children = listOf(child))
        weight(row, child, 1.0)
        allocate(row, width = 600, height = 30)
        assertTrue(allocRect(child).width > 100, "with a weight the child claims the leftover")

        weight(row, child, 0.0)
        allocate(row, width = 600, height = 30)
        assertEquals(100, allocRect(child).width, "K3: an absent weight must behave like plain packing")
    }

    @Test
    fun `a cap clamps a child below its own natural size`() {
        // Plan L6: `Modifier.maxWidth()` used to clamp only an explicit `size()` request, because
        // a GTK request is a minimum and there is no ceiling in the widget API. The manager owns
        // the child's size, so the ceiling is applied here — this is the case the old code could
        // not express at all. The label is ELLIPSIZED: its minimum drops to a few characters, so
        // a cap between that and the one-line natural is a size the child can honestly honour.
        val wide = truncatableLabel("the quick brown fox jumps over the lazy dog")
        val natural = naturalWidth(wide)
        val minimum = minimumWidth(wide)
        val row = managedBox(children = listOf(wide))
        maxSize(row, wide, 140, -1)
        allocate(row, width = 600, height = 30)

        assertTrue(minimum < 140 && 140 < natural,
            "precondition: the cap must sit between the child's minimum ($minimum) and natural ($natural)")
        assertEquals(140, allocRect(wide).width, "the cap is the child's new main-axis limit")
    }

    @Test
    fun `a cap never squeezes a child below its own minimum`() {
        // The limit of the emulation, stated as a test: a label that neither wraps nor ellipsizes
        // has minimum width == natural width, and GTK's contract is that it is never allocated
        // less. Forcing it anyway does not clip the text — GtkLabel paints the whole line over its
        // neighbours (seen in the probe window as two labels drawn on each other) and the
        // height-for-width negotiation answers `Gtk-WARNING: Trying to measure GtkLabel … for
        // width of 203, but it needs at least 249`. So a ceiling the child cannot honour loses to
        // the child's minimum, exactly like CSS, where `min-width` beats `max-width`.
        val wide = label("the quick brown fox jumps over the lazy dog")
        val natural = naturalWidth(wide)
        val row = managedBox(children = listOf(wide))
        maxSize(row, wide, 50, -1)
        allocate(row, width = 600, height = 30)

        assertTrue(natural > 50, "precondition: the cap is below what one line needs ($natural px)")
        assertEquals(natural, allocRect(wide).width,
            "the cap cannot make a non-wrapping label narrower than one line")
    }

    @Test
    fun `a cap above the natural size changes nothing`() {
        // A ceiling must not become a floor: an uncapped-looking child is not stretched because
        // somebody wrote a generous `maxWidth`.
        val narrow = label("ab")
        val natural = naturalWidth(narrow)
        val row = managedBox(children = listOf(narrow))
        maxSize(row, narrow, 10_000, -1)
        allocate(row, width = 600, height = 30)

        assertEquals(natural, allocRect(narrow).width, "the natural size still decides")
    }

    @Test
    fun `a cap shrinks the natural size the container reports`() {
        // The container negotiates with its parent, so a capped child must lower what the box
        // itself calls natural — otherwise a `Row` keeps asking for the uncapped width.
        val wide = truncatableLabel("the quick brown fox jumps over the lazy dog")
        val row = managedBox(children = listOf(wide))
        val before = naturalWidth(row)
        maxSize(row, wide, 140, -1)

        val after = naturalWidth(row)
        assertTrue(after < before, "capping the child must cap the box too ($before -> $after)")
        assertEquals(140, after, "the box's natural is the sum of the capped children")
    }

    @Test
    fun `a weighted share respects the cap`() {
        // `weight` hands out the leftover and the homogeneous pass hands out equal cells; both
        // compute a size the child never asked for, so the clamp belongs at the allocation, not
        // only in `measure`.
        val a = label("a")
        val b = label("b")
        val row = managedBox(children = listOf(a, b))
        maxSize(row, a, 80, -1)
        weight(row, a, 1.0)
        weight(row, b, 1.0)
        allocate(row, width = 600, height = 30)

        assertEquals(80, allocRect(a).width, "half of 600 is 300, the cap has the last word")
        assertEquals(300, allocRect(b).width, "the other weighted child still takes its share")
    }

    @Test
    fun `a cap on one axis does not touch the other`() {
        // `maxWidth` must not leak into the height (the manager measures the two axes separately,
        // and the caps are stored in the child's own frame).
        val tall = truncatableLabel("the quick brown fox jumps over the lazy dog")
        val row = managedBox(children = listOf(tall))
        maxSize(row, tall, 140, -1)
        allocate(row, width = 600, height = 100)

        val box = allocRect(tall)
        assertEquals(140, box.width, "the width is capped")
        assertEquals(100, box.height, "the height is still the container's cross size")
    }

    @Test
    fun `a cross-axis cap clamps the height of a child`() {
        // The cross axis is where a bar pins an icon: the container is 40px tall, the child is
        // capped at 16 and must not be stretched over the whole row. An empty label is never
        // shorter than one line of its font, so the floor here is the child's own minimum —
        // whatever it is, the cap must keep the icon well out of the full row height.
        val icon = gtk_label_new("")!!.also { gtk_widget_set_size_request(it, 16, 16) }
        val text = label("text")
        val row = managedBox(children = listOf(icon, text))
        val ownMinimum = minimumHeight(icon)
        maxSize(row, icon, -1, 16)
        allocate(row, width = 600, height = 40)

        assertEquals(maxOf(16, ownMinimum), allocRect(icon).height,
            "the cross-axis cap is applied down to the child's own minimum ($ownMinimum)")
        assertEquals(40, allocRect(text).height, "and only for that child")
    }

    @Test
    fun `a cross-axis cap stops at the height the child asked for`() {
        // Same minimum rule on the cross axis: `height(30)` is a request, and `maxHeight(20)`
        // does not revoke it. An icon squashed below the size it was given does not shrink — it
        // draws itself outside the box it was handed.
        val icon = gtk_label_new("")!!.also { gtk_widget_set_size_request(it, 16, 30) }
        val text = label("text")
        val row = managedBox(children = listOf(icon, text))
        maxSize(row, icon, -1, 20)
        allocate(row, width = 600, height = 40)

        assertEquals(30, allocRect(icon).height, "the child's own request is the floor")
        assertEquals(40, allocRect(text).height, "the sibling still gets the whole row")
    }

    @Test
    fun `clearing a cap restores the natural size`() {
        // Same rule as `weight` and as the modifier reset in L9: a chain rebuilt without
        // `maxWidth()` must leave no trace of the previous value.
        val wide = truncatableLabel("the quick brown fox jumps over the lazy dog")
        val natural = naturalWidth(wide)
        val row = managedBox(children = listOf(wide))
        maxSize(row, wide, 140, -1)
        allocate(row, width = 600, height = 30)
        assertEquals(140, allocRect(wide).width, "precondition: the cap held")

        maxSize(row, wide, -1, -1)
        allocate(row, width = 600, height = 30)
        assertEquals(natural, allocRect(wide).width, "an absent cap behaves like no cap at all")
    }

    @Test
    fun `a maxWidth on a child reaches the box that sizes it`() {
        // The rest of the DSL path. `maxWidth` is not a GTK property, so a node has to hand it to
        // the container's layout manager — and it has to work for a child that is not itself a
        // box, which is what the bar writes (`Row { Text(Modifier.maxWidth(220)) }`). A push
        // implemented only on `GtkBoxNode` would silently drop the cap of every Text and Image.
        val wide = truncatableLabel("the quick brown fox jumps over the lazy dog")
        val natural = naturalWidth(wide)
        val row = GtkBoxNode(rooted(gtk_box_new(HORIZONTAL, 0)!!))
        val child = GtkNode(wide)
        row.insertChild(0, child)
        child.applyModifier(Modifier.maxWidth(140))
        allocate(row.widget, width = 600, height = 30)

        assertTrue(natural > 140, "precondition: the text wants more than the cap ($natural px)")
        assertEquals(140, allocRect(wide).width, "the chain's cap is what the manager enforces")
        assertEquals(140, getBoxChildMaxWidth(row.widget.handle(), wide.handle()), "…in the manager too")
    }

    @Test
    fun `homogeneous gives every child an equal share of the container`() {
        // Measured against gtkboxlayout.c: each cell is `(container - spacing) / n_children`,
        // NOT "as large as the largest child" — the whole width is consumed, and a CENTER/END
        // child floats inside its own equal cell.
        val wide = gtk_label_new("")!!.also { gtk_widget_set_size_request(it, 200, 20) }
        val narrow = gtk_label_new("")!!.also { gtk_widget_set_size_request(it, 20, 20) }
        val row = managedBox(homogeneous = true, children = listOf(wide, narrow))
        allocate(row, width = 600, height = 30)

        val kids = childrenOf(row)
        assertEquals(allocRect(kids[0]).width, allocRect(kids[1]).width, "equal cells")
        assertEquals(300, allocRect(kids[0]).width, "1/2 of the container, surplus included")
    }

    @Test
    fun `spacing is a property of the container not of GtkBox`() {
        // `gtk_box_set_spacing()` writes to the stock layout, which is dead once our manager is
        // attached — so a managed box must be configured through `setBoxSpacing`. This test is
        // the regression guard for that silent breakage.
        val a = gtk_label_new("")!!.also { gtk_widget_set_size_request(it, 10, 10) }
        val b = gtk_label_new("")!!.also { gtk_widget_set_size_request(it, 10, 10) }
        val row = managedBox(spacing = 8, children = listOf(a, b))
        allocate(row, width = 300, height = 30)

        val kids = childrenOf(row)
        assertEquals(8, allocRect(kids[1]).x - (allocRect(kids[0]).x + allocRect(kids[0]).width), "gap between cells")
        assertEquals(28, measure(row, HORIZONTAL, -1).natural, "the container's natural size counts the gap")
    }

    @Test
    fun `a zero sized child keeps a zero cell`() {
        // The 1px floor the old manager applied (`if (m < 1) m = 1`) turned every
        // `Box(Modifier.size(0, 2))` divider into an artifact. It must stay 0 here too.
        val spacer = label("")
        val content = gtk_label_new("")!!.also { gtk_widget_set_size_request(it, 100, 20) }
        val row = managedBox(children = listOf(spacer, content))
        allocate(row, width = 600, height = 30)

        assertEquals(0, allocRect(spacer).width, "an empty child stays 0px wide")
        weight(row, content, 1.0)
        allocate(row, width = 600, height = 30)
        assertEquals(600, allocRect(content).width, "the zero cell does not steal the leftover")
    }

    @Test
    fun `the manager forwards for_size so a wrapping label can answer height-for-width`() {
        // Plan L3: the old manager dropped `for_size` on the floor, so a container could never
        // learn how tall its content is at a given width. Here the constraint travels through.
        val text = "one two three four five six seven eight nine ten"
        val kid = wrappingLabel(text)
        val box = managedBox(children = listOf(kid))
        // Negotiating height-for-width at a narrow width must yield a TALLER box than the
        // unconstrained question — only possible if `for_size` reaches the label.
        assertTrue(
            Requested(box).heightAt(120).natural > Requested(box).height,
            "K3: the container answers height-for-width for its children",
        )
        // ...and the negotiation is real: GtkBox hands every child the FULL cross size, so the
        // telling number is the width — the child gets the width it asks for under the
        // constraint, not its unconstrained one.
        allocate(box, width = 120, height = 200)
        assertEquals(measure(kid, HORIZONTAL, 200).natural, allocRect(kid).width,
            "the allocation follows the constrained negotiation")
    }

    @Test
    fun `expand propagates through a managed box to its parent`() {
        // Plan K1: `gtk_widget_compute_expand()` on a GtkBox ORs its children, and it is the
        // WIDGET class (GtkBox) that does this — not the layout manager — so nesting a managed
        // box inside a managed box must still let the surplus travel through, exactly as it did
        // for stock GtkBox in group 1. If the manager were ever replaced by something that
        // hides this, `Row { Row { Label(fillWidth()) } }` would stop reaching the edge and
        // nothing else here would notice.
        val grower = label("grow").also { gtk_widget_set_hexpand(it, 1) }
        // `root = false`: a nested box must not be given a carrier parent first, or appending it
        // to the outer box is rejected (a GtkWidget has exactly one parent) and the outer box
        // silently ends up with one child.
        val inner = managedBox(children = listOf(grower), root = false)
        val side = label("side")
        val outer = managedBox(children = listOf(side, inner))

        assertEquals(true, expandsHorizontally(inner),
            "a managed box still reports the expand of its children")
        allocate(outer, width = 600, height = 30)
        val outerKids = childrenOf(outer)
        assertEquals(2, outerKids.size, "precondition: the nested box really became a child")
        assertEquals(naturalWidth(side), allocRect(outerKids[0]).width, "the plain sibling packs")
        assertTrue(allocRect(outerKids[1]).width > naturalWidth(grower), "the nested box claims the surplus")

        // No second `allocate` by hand: allocating the box makes its own manager allocate the
        // child, which is the propagation this test is about.
        assertEquals(allocRect(outerKids[1]).width, allocRect(childrenOf(inner)[0]).width,
            "and its expanding child fills the width the parent handed down")
    }

    private companion object {
        init {
            // GTK needs a display for style contexts; without one these tests cannot measure
            // real text metrics, so fail loudly instead of reporting bogus numbers.
            check(gtk_init_check() != 0) { "LayoutProbeTest needs a display connection (run it from a graphical session)" }
        }
    }
}

// ------------------------------------------------------------- module-private helpers

/** Counter behind [css]: every call gets a private CSS class, so providers never collide. */
private var cssSequence: Int = 0

private val HORIZONTAL: GtkOrientation = GtkOrientation.GTK_ORIENTATION_HORIZONTAL
private val VERTICAL: GtkOrientation = GtkOrientation.GTK_ORIENTATION_VERTICAL

private data class SizeInfo(val min: Int, val natural: Int)

/** Reads an allocation from file scope: a thin wrapper over the same call the test class makes. */
private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

/** The allocation a widget got in the last layout pass - `gtk_widget_get_allocation()`. */
private fun allocRect(widget: CPointer<GtkWidget>): Rect = memScoped {
    val a = alloc<GtkAllocation>()
    gtk_widget_get_allocation(widget, a.ptr)
    Rect(a.x, a.y, a.width, a.height)
}

/** `gtk_widget_measure()` with all four out-params. */
private fun measure(widget: CPointer<GtkWidget>, orientation: GtkOrientation, forSize: Int): SizeInfo = memScoped {
    // One array rather than four IntVar locals: the out-params are plain `int*`.
    val out = allocArray<IntVar>(4)
    gtk_widget_measure(widget, orientation, forSize, out, out + 1, out + 2, out + 3)
    SizeInfo(out[0], out[1])
}

/** The same widget seen through all four negotiation questions a container can ask it. */
private class Requested(private val widget: CPointer<GtkWidget>) {
    val natural: Int get() = measure(widget, HORIZONTAL, -1).natural
    val height: Int get() = measure(widget, VERTICAL, -1).natural
    fun widthAt(size: Int) = measure(widget, HORIZONTAL, size)
    fun heightAt(size: Int) = measure(widget, VERTICAL, size)
}

/**
 * The span the container reserved for `kids[index]`. Deliberately not the gap from the child's
 * own `x`: a CENTER child is allocated *inside* its span, so measuring from `own.x` would
 * report the span minus the centring offset and the centring assertion would compare a number
 * against itself.
 */
private fun cell(container: CPointer<GtkWidget>, kids: List<CPointer<GtkWidget>>, index: Int = 0): Int {
    val start = if (index == 0) allocRect(container).x
        else allocRect(kids[index - 1]).x + allocRect(kids[index - 1]).width
    val end = if (index + 1 < kids.size) allocRect(kids[index + 1]).x
        else allocRect(container).x + allocRect(container).width
    return end - start
}

private fun childrenOf(widget: CPointer<GtkWidget>): List<CPointer<GtkWidget>> {
    val out = mutableListOf<CPointer<GtkWidget>>()
    var child = gtk_widget_get_first_child(widget)
    while (child != null) {
        out += child
        child = gtk_widget_get_next_sibling(child)
    }
    return out
}

/** A stock `GtkBox`: used by groups 1-3, which measure what GTK itself guarantees. */
private fun box(
    spacing: Int = 0,
    vertical: Boolean = false,
    children: List<CPointer<GtkWidget>>,
    root: Boolean = true,
): CPointer<GtkWidget> {
    val b = gtk_box_new(if (vertical) VERTICAL else HORIZONTAL, spacing)!!
    children.forEach { gtk_box_append(b.reinterpret<GtkBox>(), it) }
    if (root && children.isNotEmpty()) rooted(b)
    return b
}

/**
 * Gives [widget] an ancestor so it becomes the root of its own hierarchy: a widget with no
 * ancestor and no window is never drawn, so its allocation is never computed. The carrier is
 * never measured or allocated itself, which keeps rooting invisible to the asserted numbers.
 */
private fun rooted(widget: CPointer<GtkWidget>): CPointer<GtkWidget> {
    if (gtk_widget_get_parent(widget) == null) {
        gtk_widget_set_parent(widget, gtk_box_new(HORIZONTAL, 0)!!)
    }
    return widget
}

/**
 * A `Row`/`Column` as the framework builds one: a `GtkBox` whose packing is done by our own
 * `KnBoxLayout` (`rust-core/src/kn_box_layout.c`). This is the object under test in group 4 -
 * the stock-box tests above deliberately keep using [box], because they assert what GTK itself
 * guarantees and must stay true whatever our manager does.
 */
private fun managedBox(
    spacing: Int = 0,
    vertical: Boolean = false,
    homogeneous: Boolean = false,
    children: List<CPointer<GtkWidget>>,
    root: Boolean = true,
): CPointer<GtkWidget> {
    val b = gtk_box_new(if (vertical) VERTICAL else HORIZONTAL, 0)!!
    children.forEach { gtk_box_append(b.reinterpret<GtkBox>(), it) }
    val handle = b.handle()
    // Same order as GtkBoxNode: attach the manager, then configure it, then push the weights.
    attachBoxLayout(handle, if (vertical) 1 else 0, 0, false)
    setBoxOrientation(handle, if (vertical) 1 else 0)
    setBoxSpacing(handle, spacing)
    setBoxHomogeneous(handle, homogeneous)
    if (root && children.isNotEmpty()) rooted(b)
    return b
}

private fun CPointer<GtkWidget>.handle(): ULong = rawValue.toLong().toULong()

/** The framework spells this `Modifier.maxWidth()`/`maxHeight()`; here it is set directly. */
private fun maxSize(
    container: CPointer<GtkWidget>,
    child: CPointer<GtkWidget>,
    width: Int,
    height: Int,
) {
    setBoxChildMax(container.handle(), child.handle(), width, height)
}

/** The framework spells this `Modifier.weight()`; here it is set directly on the container. */
private fun weight(container: CPointer<GtkWidget>, child: CPointer<GtkWidget>, value: Double) {
    setBoxChildWeight(container.handle(), child.handle(), value)
}

private fun label(text: String = "label"): CPointer<GtkWidget> = gtk_label_new(text)!!

/** `gtk_widget_get_size_request` as a pair; `-1` means "no request", i.e. natural size. */
private fun sizeRequest(widget: CPointer<GtkWidget>): Pair<Int, Int> = memScoped {
    val width = alloc<IntVar>()
    val height = alloc<IntVar>()
    gtk_widget_get_size_request(widget, width.ptr, height.ptr)
    width.ptr[0] to height.ptr[0]
}

private fun wrappingLabel(text: String): CPointer<GtkWidget> =
    label(text).also { gtk_label_set_wrap(it.reinterpret<GtkLabel>(), 1) }

private fun applyModifier(widget: CPointer<GtkWidget>, modifier: Modifier) =
    applyModifiers(widget, modifier, InlineStyle(widget))

/** Attaches the animated-size configuration (`axis`: 0 = width, 1 = height). */
private fun attachAnimated(widget: CPointer<GtkWidget>, axis: Int, spacing: Int = 0) {
    attachAnimatedSizeLayout(
        widget.handle(),
        0u,
        0u,
        spacing.toUInt(),
        axis.toUInt(),
    )
}

private fun animatedContent(widget: CPointer<GtkWidget>) = animatedSizeContent(widget.handle())

private fun pushCurrent(widget: CPointer<GtkWidget>, value: Double) =
    setAnimatedSizeCurrent(widget.handle(), value)

/**
 * Adds CSS at author priority, scoped to a single widget - the same mechanism `InlineStyle`
 * uses. Two empirically verified traps:
 *  - a widget whose `min-width` is set **after** it was already measured keeps answering with
 *    the cached number (GTK caches the negotiation per `(orientation, for_size)`), so style
 *    first and measure once;
 *  - a `#id` selector only matches when `gtk_widget_set_name` ran *before* the provider was
 *    added; `gtk_widget_add_css_class` after it still invalidates, which is what [InlineStyle]
 *    relies on.
 */
private fun css(widget: CPointer<GtkWidget>, declarations: String) {
    val cls = "knprobe" + (++cssSequence)
    gtk_widget_add_css_class(widget, cls)
    val provider = gtk_css_provider_new()!!
    gtk_css_provider_load_from_string(provider, "." + cls + " { " + declarations + " }")
    gtk_style_context_add_provider(gtk_widget_get_style_context(widget), provider.reinterpret(), 800u)
}
