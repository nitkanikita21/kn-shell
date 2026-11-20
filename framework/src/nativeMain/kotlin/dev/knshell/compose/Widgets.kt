// ---------------------------------------------------------------------------
// The composable DSL — `Column` / `Row` / `Box` / `Stack` / `Overlay` /
// `CenterBox` and the content widgets (`Label`, `Markup`, `Button`, `Canvas`,
// `Slider`). Every call emits a node through `ComposeNode`, and `GtkApplier`
// turns nodes into GTK widgets. Node classes live in ComposeNodes.kt and
// ComposeBasicNodes.kt.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kn.gtk.GtkOrientation
import kn.gtk.cairo_t
import kn.gtk.gtk_box_new

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

/**
 * Vertical flex container (`GtkBox` packed by `KnBoxLayout`): children are packed top to bottom
 * with [spacing] pixels between them.
 *
 * [homogeneous] gives every child the same main-axis size. Per-child growth is `Modifier.weight`;
 * cross-axis placement is `Modifier.align`.
 *
 * ```kotlin
 * Column(spacing = 4) { Label("cpu"); Label("42%", modifier = Modifier.weight(1f)) }
 * ```
 */
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

/**
 * Horizontal flex container (`GtkBox` packed by `KnBoxLayout`): children are packed left to right
 * with [spacing] pixels between them.
 *
 * [homogeneous] gives every child the same main-axis size. Per-child growth is `Modifier.weight`;
 * cross-axis placement is `Modifier.align`.
 */
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

/**
 * Three-region bar (`GtkCenterBox`): [start], [center] and [end] are composed into their own
 * horizontal boxes, so a region can hold several widgets without an explicit `Row`.
 *
 * ```kotlin
 * CenterBox(start = { Clock() }, center = { Workspaces() }, end = { Tray() })
 * ```
 *
 * Sizing is GTK's: the regions keep their natural width and the slack goes to whichever region has
 * an expanding child.
 */
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

/** Text button with a plain GTK label ([text]) and a click handler — the simple form of [Button]. */
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

/**
 * Drawing area of [width]×[height] CSS pixels; [onDraw] runs on every GTK frame with a Cairo
 * context and the current allocation, so it can use the real (allocated) size instead of the
 * requested one. Kept out of the Compose tree's layout: only the size request above applies.
 */
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

/**
 * Horizontal `GtkScale` slider over [min]..[max] with [step] granularity; [onValueChange] fires on
 * user drags only (programmatic updates from [value] do not loop back).
 *
 * ```kotlin
 * Slider(value = volume, onValueChange = { player.setVolume(it) },
 *     modifier = Modifier.width(80).slider(highlightColor = Catppuccin.Green))
 * ```
 */
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
