// ---------------------------------------------------------------------------
// The `Modifier` chain itself: the `ModifierElement` marker interface, the
// immutable `Modifier` list and the element-level accessors the applier and the
// layout managers read (`mainAxisWeight`, `aspectRatioValue`, `maxSizeCaps`).
//
// The element records live in ModifierElements.kt, the DSL that builds them in
// ModifierLayout.kt / ModifierText.kt / ModifierPaint.kt, and the CSS they
// produce in ModifierCss.kt.
// ---------------------------------------------------------------------------

package dev.knshell.compose

/**
 * One styling concern of a [Modifier] chain. Elements are immutable records; the chain is a plain
 * list of them and [applyModifiers] is what turns the list into GTK CSS calls. Internal because no
 * public signature exposes an element: the DSL functions in `Modifier*.kt` are the public door.
 */
internal sealed interface ModifierElement

/**
 * Immutable chain of widget decorations, applied when the node is updated.
 *
 * Usage mirrors Compose UI:
 * ```
 * Label("hi", modifier = Modifier.padding(horizontal = 12, vertical = 6)
 *     .background(Color.hex(0x161925), radius = 16)
 *     .textColor(Catppuccin.Text))
 * ```
 */
open class Modifier internal constructor(internal val elements: List<ModifierElement> = emptyList()) {

    /**
     * Concatenates this chain with [other]: [other]'s elements come last, so they win in
     * [applyModifiers] (the later rule overwrites the earlier one). An empty side is dropped, and
     * nothing is ever mutated — a chain is a value. */
    fun then(other: Modifier): Modifier = when {
        other.elements.isEmpty() -> this
        elements.isEmpty() -> other
        else -> Modifier(elements + other.elements)
    }

    override fun equals(other: Any?): Boolean = other is Modifier && elements == other.elements

    override fun hashCode(): Int = elements.hashCode()

    companion object : Modifier()
}

internal fun Modifier.with(element: ModifierElement): Modifier = Modifier(elements + element)

/** The main-axis weight [modifier] asks for, or `0f` when it has none. */
internal fun Modifier.mainAxisWeight(): Double =
    elements.filterIsInstance<WeightElement>().lastOrNull()?.value ?: 0.0

/**
 * The `Modifier.aspectRatio()` a chain asks for, `0f` when it asks for none. Like `weight`, a
 * ratio is a property of the CONTAINER's layout manager (`KnBoxLayout`), not of the widget, so
 * `GtkApplier` pushes it to the parent and nothing is written here.
 */
internal fun Modifier.aspectRatioValue(): Float =
    elements.filterIsInstance<AspectRatioElement>().lastOrNull()?.ratio ?: 0f

/**
 * The `maxWidth()`/`maxHeight()` ceilings [modifier] asks for, as `width to height`, with `-1`
 * for an axis it does not cap. A chain is rebuilt from scratch every recomposition, so an absent
 * cap has to be pushed as `-1` — it clears the previous one (L9).
 */
internal fun Modifier.maxSizeCaps(): Pair<Int, Int> {
    var width = -1
    var height = -1
    for (element in elements) {
        if (element is MaxSizeElement) {
            val w = element.width
            val h = element.height
            if (w != null) width = w
            if (h != null) height = h
        }
    }
    return width to height
}
