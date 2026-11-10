@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

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

// ── Backward-compat aliases (deprecated, migrate to align/hExpand/vExpand) ──
@Deprecated("Use Modifier.align(h = Align.Fill)", ReplaceWith("align(h = Align.Fill)"))
fun Modifier.fillMaxWidth(): Modifier = hExpand().align(h = Align.Fill)

@Deprecated("Use Modifier.align(v = Align.Fill)", ReplaceWith("align(v = Align.Fill)"))
fun Modifier.fillMaxHeight(): Modifier = vExpand().align(v = Align.Fill)

@Deprecated("Use Modifier.align(Align.Fill)", ReplaceWith("align(Align.Fill)"))
fun Modifier.fillMaxSize(): Modifier = expand().align(Align.Fill)

@Deprecated("Use Modifier.align(v = Align.Center)", ReplaceWith("align(v = Align.Center)"))
fun Modifier.centerVertically(): Modifier = align(v = Align.Center)

@Deprecated("Use Modifier.align(h = Align.Center)", ReplaceWith("align(h = Align.Center)"))
fun Modifier.centerHorizontally(): Modifier = align(h = Align.Center)

