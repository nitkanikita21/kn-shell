@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import dev.knshell.animation.AnimationSpec
import dev.knshell.animation.applyAnimatedSizeModifier
import kotlinx.cinterop.ExperimentalForeignApi

/** Which axis of an `animatedSize` modifier is animated. */
enum class AnimatedAxis { Width, Height }

/**
 * Modifier element that tweens the **main-axis size** of the target container widget in
 * Compose-owned time, with no dedicated component — `animatedWidth`/`animatedHeight`/
 * `animatedSize` attach this element to a `Row`/`Column`.
 *
 * The element carries no animation state itself — it is a stateless descriptor, exactly like
 * every other [ModifierElement]. The real tween lives in [dev.knshell.animation], keyed by the
 * target widget's pointer (see [applyAnimatedSizeModifier]); `applyModifiers` get-or-creates
 * that animator and pushes the latest parameters on every modifier change.
 *
 * ## Semantics
 *
 * - **Content-driven target** = the natural size of the children measured with
 *   `for_size = -1` (the C helper sums them plus the container's own spacing). It does **not**
 *   depend on the currently applied animated value, so there is no feedback loop: children are
 *   laid out at their natural size and the container clips (`GTK_OVERFLOW_HIDDEN`). Content is
 *   *revealed*, never reflowed per frame.
 * - **Clip, not reflow.** An animating container does not change its children's layout; only
 *   the visible window into it grows/shrinks.
 * - **Retarget from the current value.** Changing [spec] or [target] while an animation is
 *   running restarts the animation from wherever it is right now — never from the old start
 *   value, so there is no jump.
 * - **Default is a monotonic [AnimationSpec.Tween].** Overshoot of a container would clip the
 *   content for a few frames, so [AnimationSpec.Spring] is opt-in and meant for leaves and
 *   indicators.
 * - **Explicit [target]** overrides the content-driven target; `null` returns to
 *   content-driven. The transition is animated from the current value (see `setAnimatedSizeFixed`).
 * - **Rounding happens only in C**, when the native layout manager allocates.
 *
 * [vertical] and [spacing] describe the host container: the animated layout manager is attached
 * to a **Row** (`vertical = false`) or **Column** (`vertical = true`). Spacing is read from the
 * container itself, so it can stay `0` here.
 *
 * @param target explicit main-axis target in px, or `null` for content-driven.
 * @param spec how the value travels to the target; see [AnimationSpec].
 * @param minMainSize a floor on the main-axis size in px. When content-driven ([target] is
 *   `null`) or when the content is narrower than this, the container is at least this wide (so
 *   e.g. a workspace chip never collapses below its neighbours); when the content is wider it
 *   still grows to fit, so wide labels are never clipped.
 * @param crossSize explicit cross-axis size in px, or `null` for content-driven.
 * @param vertical `false` for a Row (animate width), `true` for a Column (animate height).
 * @param spacing gap between children; the container's own spacing is used automatically, so
 *   this is only a fallback for non-box hosts.
 */
internal data class AnimatedSizeElement(
    val target: Int? = null,
    val spec: AnimationSpec = AnimationSpec.Tween(),
    val minMainSize: Int? = null,
    val crossSize: Int? = null,
    val vertical: Boolean = false,
    val spacing: Int = 0,
) : ModifierElement

/**
 * Animates the **width** of the receiver (a Row / container) to follow its content, or to an
 * explicit [target].
 *
 * ```kotlin
 * Row(spacing = 10) {
 *     Icon(Lucide.Clock) // dev.knshell.compose.Icon + an IconRef, see docs/icons.md
 *     Label(now.format(format))
 * }.animatedWidth(spec = Tween(200, Easing.OutCubic))
 * ```
 *
 * Only meaningful on a **container** widget (Row/Column): the native layout manager measures the
 * children, so a leaf (Label/Icon) would collapse to zero when content-driven.
 */
fun Modifier.animatedWidth(
    target: Int? = null,
    spec: AnimationSpec = AnimationSpec.Tween(),
    minMainSize: Int? = null,
    crossSize: Int? = null,
    spacing: Int = 0,
): Modifier = with(
    AnimatedSizeElement(
        target = target,
        spec = spec,
        minMainSize = minMainSize,
        crossSize = crossSize,
        vertical = false,
        spacing = spacing,
    ),
)

/**
 * Animates the **height** of the receiver (a Column / container) to follow its content, or to
 * an explicit [target]. See [animatedWidth] for the semantics.
 */
fun Modifier.animatedHeight(
    target: Int? = null,
    spec: AnimationSpec = AnimationSpec.Tween(),
    minMainSize: Int? = null,
    crossSize: Int? = null,
    spacing: Int = 0,
): Modifier = with(
    AnimatedSizeElement(
        target = target,
        spec = spec,
        minMainSize = minMainSize,
        crossSize = crossSize,
        vertical = true,
        spacing = spacing,
    ),
)

/**
 * Animates the size along [axis] of the receiver (a container) to follow its content, or to an
 * explicit [target]. [animatedWidth] / [animatedHeight] read better at most call sites.
 */
fun Modifier.animatedSize(
    axis: AnimatedAxis,
    target: Int? = null,
    spec: AnimationSpec = AnimationSpec.Tween(),
    minMainSize: Int? = null,
    crossSize: Int? = null,
    spacing: Int = 0,
): Modifier = with(
    AnimatedSizeElement(
        target = target,
        spec = spec,
        minMainSize = minMainSize,
        crossSize = crossSize,
        vertical = axis == AnimatedAxis.Height,
        spacing = spacing,
    ),
)
