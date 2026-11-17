@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import kn.gtk.GTK_ALIGN_CENTER
import kn.gtk.GTK_ALIGN_END
import kn.gtk.GTK_ALIGN_FILL
import kn.gtk.GTK_ALIGN_START

import kotlinx.cinterop.ExperimentalForeignApi
import kn.gtk.GTK_ALIGN_CENTER
import kn.gtk.GTK_ALIGN_END
import kn.gtk.GTK_ALIGN_FILL
import kn.gtk.GTK_ALIGN_START

/** Alignment on a single axis; used by [Modifier.align]. */
enum class Align {
    Start, Center, End, Fill;
}

internal fun Align.toGtk(): UInt = when (this) {
    Align.Start -> GTK_ALIGN_START
    Align.Center -> GTK_ALIGN_CENTER
    Align.End -> GTK_ALIGN_END
    Align.Fill -> GTK_ALIGN_FILL
}

/** Horizontal expand + fill alignment — the layout equivalent of Flex's grow. */
fun Modifier.hExpand(): Modifier = with(ExpandElement(horizontal = true, vertical = false))
    .with(AlignElement(horizontal = GTK_ALIGN_FILL, vertical = null))

/** Vertical expand + fill alignment. */
fun Modifier.vExpand(): Modifier = with(ExpandElement(horizontal = false, vertical = true))
    .with(AlignElement(horizontal = null, vertical = GTK_ALIGN_FILL))

/** Expand in both directions and fill. */
fun Modifier.expand(): Modifier = with(ExpandElement(horizontal = true, vertical = true))
    .with(AlignElement(horizontal = GTK_ALIGN_FILL, vertical = GTK_ALIGN_FILL))

/**
 * Main-axis weight: this child claims a share of the space the other children left over —
 * the analogue of Android's `layout_weight` / Compose's `Modifier.weight`.
 *
 * ```kotlin
 * Row(spacing = 8) {
 *     Label("name")                       // natural width
 *     Label(url, modifier = Modifier.weight(1f)) // everything else
 * }
 * ```
 *
 * Semantics (implemented by `KnBoxLayout` in `rust-core/src/kn_box_layout.c`):
 *  - unweighted children are measured first and take their natural size;
 *  - the leftover is split between weighted children in proportion to their weights;
 *  - a weighted child is never squeezed below its own minimum, and with no leftover it keeps
 *    that natural size — i.e. weights distribute *surplus*, they do not resize content
 *    (this is exactly how `LinearLayout` behaves);
 *  - `weight(0f)` (or no weight at all) means "natural size", and it also CLEARS a weight the
 *    same widget had in a previous recomposition.
 *
 * Only meaningful on a direct child of a `Row`/`Column`/`Box`: any other container ignores it.
 */
fun Modifier.weight(value: Double = 1.0): Modifier = with(WeightElement(value.coerceAtLeast(0.0)))

/**
 * Unified alignment modifier. Replaces `fillMaxWidth`, `fillMaxHeight`, `fillMaxSize`,
 * `centerVertically`, `centerHorizontally`.
 *
 * ```kotlin
 * // old                          // new
 * Modifier.fillMaxWidth()         Modifier.align(h = Align.Fill)
 * Modifier.fillMaxHeight()        Modifier.align(v = Align.Fill)
 * Modifier.fillMaxSize()          Modifier.align(Align.Fill)
 * Modifier.centerVertically()     Modifier.align(v = Align.Center)
 * Modifier.centerHorizontally()   Modifier.align(h = Align.Center)
 * Modifier.align(Center, Fill)    Modifier.align(h = Align.Center, v = Align.Fill)
 * ```
 */
fun Modifier.align(h: Align? = null, v: Align? = null): Modifier =
    with(AlignElement(h?.toGtk(), v?.toGtk()))

/** Shorthand: align both axes at once. */
fun Modifier.align(a: Align): Modifier = with(AlignElement(a.toGtk(), a.toGtk()))

/** Convenience aliases for common cases (kept for ergonomics). */
fun Modifier.fillWidth(): Modifier = hExpand().align(h = Align.Fill)
fun Modifier.fillHeight(): Modifier = vExpand().align(v = Align.Fill)
fun Modifier.fillAll(): Modifier = expand().align(Align.Fill)
fun Modifier.centerH(): Modifier = align(h = Align.Center)
fun Modifier.centerV(): Modifier = align(v = Align.Center)
fun Modifier.centerBoth(): Modifier = align(Align.Center)
