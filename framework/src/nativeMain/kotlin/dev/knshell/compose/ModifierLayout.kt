// ---------------------------------------------------------------------------
// Layout modifiers — padding, margin, size requests (`size`, `requiredSize`),
// `aspectRatio` and the `min*` / `max*` bounds. Units are CSS pixels.
// Alignment and `weight` live in Layout.kt next to the `Align` enum.
// ---------------------------------------------------------------------------

package dev.knshell.compose

/**
 * Inner spacing in CSS pixels: room inside the widget's own background and border, between it and
 * its content. Both arguments are symmetric pairs, so `padding(horizontal = 12, vertical = 6)` is
 * 12 px left/right and 6 px top/bottom.
 *
 * ```kotlin
 * Label("hi", modifier = Modifier.padding(horizontal = 12, vertical = 6).background(color, radius = 8))
 * ```
 */
fun Modifier.padding(horizontal: Int = 0, vertical: Int = 0): Modifier =
    with(PaddingElement(horizontal, vertical))

/** Uniform padding of [all] CSS pixels on every side (see [padding]). */
fun Modifier.padding(all: Int): Modifier = with(PaddingElement(all, all))

/**
 * Outer spacing in CSS pixels, written as the GTK margins of the widget — room *outside* the
 * background, between this widget and its siblings.
 */
fun Modifier.margin(horizontal: Int = 0, vertical: Int = 0): Modifier =
    with(MarginElement(horizontal, vertical))

/**
 * Size request in CSS pixels; `null` leaves an axis to the content.
 *
 * A GTK size request is a **minimum**, not an exact size — a stretching parent may still give the
 * widget more. Use [requiredSize] when the size must not grow.
 */
fun Modifier.size(width: Int? = null, height: Int? = null): Modifier = with(SizeElement(width, height))

/** Square size request: [size] pixels on both axes (see [size]). */
fun Modifier.size(size: Int? = null): Modifier = with(SizeElement(size, size))

/** Width request in CSS pixels, height left to the content (see [size]). */
fun Modifier.width(width: Int): Modifier = with(SizeElement(width, null))

/** Height request in CSS pixels, width left to the content (see [size]). */
fun Modifier.height(height: Int): Modifier = with(SizeElement(null, height))

/**
 * An **exact** size: compose's `requiredSize`. `size()` is a GTK size request, which is only a
 * minimum — a `GtkBox` stretching its children on the cross axis happily gives a `size(44)` chip
 * 44×80. This pins the axes it names (request + `Align.Start`, so the parent cannot grow it) and
 * leaves the rest to the chain: a later `align()`/`fillWidth()` still wins.
 */
fun Modifier.requiredSize(width: Int? = null, height: Int? = null): Modifier =
    with(RequiredSizeElement(width, height))

/** Exact square size: [requiredSize] with the same value on both axes. */
fun Modifier.requiredSize(size: Int? = null): Modifier = with(RequiredSizeElement(size, size))

/**
 * Locks the child's shape: `ratio` = main / cross, where the main axis is the one the container
 * lays children out on. In a `Row`, `aspectRatio(1f)` means "as tall as the row, and exactly that
 * wide" — the mirror image of `requiredSize`, which pins both axes to numbers.
 *
 * The cross axis keeps deciding (a `FILL` child takes the container's whole cross size, an
 * aligned one its own), and the main axis follows from it. A `weight` or `hExpand()` on the same
 * child is ignored, because both would claim the pixels the ratio already owns.
 *
 * Like `weight` it lives in `KnBoxLayout`, so it works inside `Row`/`Column`/`Box`; in a `Stack`,
 * `Overlay` or `CenterBox` region every child gets the same cell and there is no main axis to
 * resize along.
 */
fun Modifier.aspectRatio(ratio: Float): Modifier = with(AspectRatioElement(ratio))

/** Exact width, height left to the parent (see [requiredSize]). */
fun Modifier.requiredWidth(width: Int): Modifier = with(RequiredSizeElement(width, null))

/** Exact height, width left to the parent (see [requiredSize]). */
fun Modifier.requiredHeight(height: Int): Modifier = with(RequiredSizeElement(null, height))


/** Minimum width in CSS pixels (the plain reset removes the theme minimums). */
fun Modifier.minWidth(px: Int): Modifier = with(MinSizeElement(px, null))

/** Minimum height in CSS pixels. */
fun Modifier.minHeight(px: Int): Modifier = with(MinSizeElement(null, px))

/** Both minimums. */
fun Modifier.minSize(width: Int, height: Int): Modifier = with(MinSizeElement(width, height))

/** Caps an explicit `width()`/`size()` request. Text: use `Label(maxWidthChars)`. */
fun Modifier.maxWidth(px: Int): Modifier = with(MaxSizeElement(px, null))

/** Caps an explicit `height()`/`size()` request. */
fun Modifier.maxHeight(px: Int): Modifier = with(MaxSizeElement(null, px))

/** Both maximums. */
fun Modifier.maxSize(width: Int, height: Int): Modifier = with(MaxSizeElement(width, height))
