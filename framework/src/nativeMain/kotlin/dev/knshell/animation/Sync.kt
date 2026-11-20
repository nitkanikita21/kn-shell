// Shared eased progress for a subtree (`Animate`) plus the `lerp` / `lerpColor` helpers.

package dev.knshell.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.knshell.compose.Color
import kotlin.math.roundToInt

/**
 * One eased progress value shared by a whole subtree — the "perfectly synced" animation
 * scope.
 *
 * All values derived from [progress] inside [content] are in phase by construction: there
 * is exactly one `withFrameNanos` source and one curve, instead of N independent
 * animations whose first frames may differ. This is the QML `ParallelAnimation` idea
 * expressed as data: N values, one clock.
 *
 * ```kotlin
 * Animate(active = workspace.active) { t ->
 *     val width = lerp(28, 40, t)
 *     Box(Modifier.size(width, 28).radius(14).background(lerpColor(surface, mauve, t)))
 * }
 * ```
 *
 * Prefer hoisting the shared progress to a common ancestor when several siblings must
 * move together (e.g. an active indicator sliding between fixed slots).
 */
@Composable
fun Animate(
    active: Boolean,
    durationMs: Int = 180,
    easing: Easing = Easing.OutCubic,
    content: @Composable (progress: Float) -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        durationMs = durationMs,
        easing = easing,
    )
    content(progress)
}

/** Linear interpolation of two floats; `t` is the shared progress (0..1). */
fun lerp(start: Float, stop: Float, t: Float): Float = start + (stop - start) * t

/** Linear interpolation of two ints (rounded at the end). */
fun lerp(start: Int, stop: Int, t: Float): Int = (start + (stop - start) * t).roundToInt()

/** Linear interpolation of two colours, including alpha. */
fun lerpColor(start: Color, stop: Color, t: Float): Color = Color(
    r = lerp(start.r, stop.r, t),
    g = lerp(start.g, stop.g, t),
    b = lerp(start.b, stop.b, t),
    alpha = lerp(start.alpha, stop.alpha, t),
)
