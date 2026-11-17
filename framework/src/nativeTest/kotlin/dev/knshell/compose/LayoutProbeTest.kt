@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.compose

import dev.knshell.core.animatedSizeContent
import dev.knshell.core.attachBoxLayout
import dev.knshell.core.forgetBoxChild
import dev.knshell.core.setBoxChildWeight
import dev.knshell.core.setBoxHomogeneous
import dev.knshell.core.setBoxOrientation
import dev.knshell.core.setBoxSpacing
import dev.knshell.core.attachAnimatedSizeLayout
import dev.knshell.core.setAnimatedSizeCurrent
import kn.gtk.GTK_ALIGN_CENTER
import kn.gtk.GTK_ALIGN_FILL
import kn.gtk.gtk_widget_get_halign
import kn.gtk.gtk_widget_get_hexpand
import kn.gtk.gtk_widget_set_valign
import kn.gtk.GtkAllocation
import kn.gtk.GtkBox
import kn.gtk.GtkLabel
import kn.gtk.GtkOrientation
import kn.gtk.GtkWidget
import kn.gtk.PangoEllipsizeMode
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_new
import kn.gtk.gtk_center_box_get_shrink_center_last
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
import kn.gtk.gtk_style_context_add_provider
import kn.gtk.gtk_widget_add_css_class
import kn.gtk.gtk_widget_compute_expand
import kn.gtk.gtk_widget_get_allocation
import kn.gtk.gtk_widget_get_first_child
import kn.gtk.gtk_widget_get_next_sibling
import kn.gtk.gtk_widget_get_parent
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
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
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
 *  2. **DSL contract** — what `applyModifiers` has to guarantee (plan L1/L9). These are the
 *     assertions that turn green in K4.
 * 3. **The animated box** — the behaviour the old `kn_animated_size_layout.c` was supposed to
 *    produce (plan L2/L3/L4/L5). That file is gone: its animated fields live inside `KnBoxLayout`
 *    (`rust-core/src/kn_box_layout.c`), which is also the object of group 4. Group 3 turned
 *    green in K2, group 4 in K3.
 *
 * There is no conditional skip on Kotlin/Native (the runner only understands a static
 * `@Ignore`), so group 2 deliberately stays red until the step that fixes it lands: the
 * failure messages carry the plan step, which makes the red set readable as a work list.
 *
 * Requires a display connection — GTK needs one for style contexts and Pango metrics:
 * `./gradlew :framework:linuxX64Test`.
 */
class LayoutProbeTest {

    // ---------------------------------------------------------------- helpers

    private fun naturalWidth(widget: CPointer<GtkWidget>) = measure(widget, HORIZONTAL, -1).natural
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
        // `cell()` reconstructs it from the gap to the next sibling. This is the gap
        // `reconcileMainAxisAlign()` (plan L1) papers over by switching on `hexpand`, and what
        // the custom manager must resolve honestly (K3/K4).
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
        // framework therefore cannot express "centre wins over the sides" with GtkCenterBox — which
        // is what plan K6 needs to know before replacing the slot-order counter (L7) with named
        // slots.
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

    // --------------------------------------------------------- 2. DSL contract

    @Test
    fun `align only touches the axis it was asked about`() {
        val widget = label("a").also { applyModifier(it, Modifier.align(v = Align.Center)) }
        assertEquals(GTK_ALIGN_CENTER, gtk_widget_get_valign(widget))
    }

    @Test
    fun `layout state does not survive a chain that dropped it`() {
        // Plan L9: `applyModifiers` writes hexpand/vexpand/valign/margins only `if != null`, so
        // recomposing without `fillWidth()` leaves the old flag on. A Modifier chain is rebuilt
        // from scratch on every pass, so "absent" has to mean "default" — red until K4.
        val widget = label("a")
        applyModifier(widget, Modifier.fillWidth())
        assertTrue(expandsHorizontally(widget))

        applyModifier(widget, Modifier)
        assertEquals(0, gtk_widget_get_hexpand(widget), "K4: an empty chain must clear the expand flag")
        assertEquals(GTK_ALIGN_FILL, gtk_widget_get_halign(widget), "K4: …and the alignment it wrote")
    }

    @Test
    fun `main-axis alignment does not travel through the expand flag`() {
        // Plan L1: `reconcileMainAxisAlign()` turns on hexpand/vexpand when an aligned child sits
        // along its parent's orientation and never turns it off again — which is how an
        // `align(h = Center)` icon once stretched its 44px badge across a whole card. After K4/K3
        // the manager owns the cell and the flags stay exactly as the DSL asked: red until K4.
        val icon = label("icon")
        val column = box(vertical = true, children = listOf(icon))
        applyModifier(icon, Modifier.align(v = Align.Center))
        assertEquals(GTK_ALIGN_CENTER, gtk_widget_get_valign(icon), "the alignment itself is the contract")
        assertFalse(expandsVertically(icon), "K4: align must not be smuggled through the expand flag")
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
        // without lying about `hexpand` (which is what `reconcileMainAxisAlign()` used to do —
        // plan L1). The expand flag stays what the DSL asked for.
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

/** The framework spells this `Modifier.weight()`; here it is set directly on the container. */
private fun weight(container: CPointer<GtkWidget>, child: CPointer<GtkWidget>, value: Double) {
    setBoxChildWeight(container.handle(), child.handle(), value)
}

private fun label(text: String = "label"): CPointer<GtkWidget> = gtk_label_new(text)!!

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
