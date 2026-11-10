@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import dev.knshell.animation.Easing
import dev.knshell.animation.eased
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kn.gtk.GtkWidget
import kn.gtk.gtk_widget_set_size_request
import kotlin.math.roundToInt

/**
 * Opt-in, QML-like `Behavior on width/height`.
 *
 * Attach it to a widget where *you* want it; from then on any change of `size()`, `width()`
 * or `height()` in that Modifier chain is tweened instead of jumping:
 *
 * ```kotlin
 * Box(
 *     Modifier
 *         .behavior(durationMs = 180, easing = Easing.OutCubic)
 *         .size(if (active) 34 else 24, 24),
 * )
 * ```
 *
 * The tween runs on the widget node (GTK calls only), not through Compose recomposition —
 * cheaper than `animateFloatAsState` and it works for values that are computed inline.
 *
 * Limits: only the size request is animated (width/height); the first application snaps to
 * the initial value; colours are better served by [transition] (native GTK transitions).
 */
/** Which axis a [BehaviorElement] animates. */
internal enum class BehaviorAxis { Width, Height, Both }

internal data class BehaviorElement(
    val durationMs: Int,
    val easing: Easing,
    val axis: BehaviorAxis,
) : ModifierElement

/** Opt-in `Behavior on width`: later `width()`/`size()` width changes are tweened. */
fun Modifier.widthBehavior(durationMs: Int = 180, easing: Easing = Easing.OutCubic): Modifier =
    with(BehaviorElement(durationMs, easing, BehaviorAxis.Width))

/** Opt-in `Behavior on height`: later `height()`/`size()` height changes are tweened. */
fun Modifier.heightBehavior(durationMs: Int = 180, easing: Easing = Easing.OutCubic): Modifier =
    with(BehaviorElement(durationMs, easing, BehaviorAxis.Height))

/** Opt-in `Behavior on size`: both width and height changes are tweened. */
fun Modifier.sizeBehavior(durationMs: Int = 180, easing: Easing = Easing.OutCubic): Modifier =
    with(BehaviorElement(durationMs, easing, BehaviorAxis.Both))

/** Back-compat alias for [sizeBehavior]. Prefer the explicit names. */
fun Modifier.behavior(durationMs: Int = 180, easing: Easing = Easing.OutCubic): Modifier =
    sizeBehavior(durationMs, easing)

/** Per-widget size tween driven by [SizeBehaviorRegistry] on the shared frame clock. */
internal class SizeAnimator {
    private var widget: CPointer<GtkWidget>? = null
    private var durationMs = 180
    private var easing: Easing = Easing.OutCubic

    private var initialized = false
    private var currentWidth = -1f
    private var currentHeight = -1f
    private var targetWidth = -1f
    private var targetHeight = -1f
    private var fromWidth = -1f
    private var fromHeight = -1f
    private var startNanos = 0L
    private var pendingStart = false

    internal var running = false
        private set

    fun animate(
        widget: CPointer<GtkWidget>,
        width: Int?,
        height: Int?,
        durationMs: Int,
        easing: Easing,
        axis: BehaviorAxis,
    ) {
        this.widget = widget
        this.durationMs = durationMs.coerceAtLeast(1)
        this.easing = easing

        val targetW = (width ?: -1).toFloat()
        val targetH = (height ?: -1).toFloat()

        if (!initialized) {
            // First application: adopt the value without animating from nothing.
            initialized = true
            currentWidth = targetW
            currentHeight = targetH
            targetWidth = targetW
            targetHeight = targetH
            apply()
            return
        }

        val animateWidth = axis != BehaviorAxis.Height
        val animateHeight = axis != BehaviorAxis.Width

        // Dimensions that are not animated change instantly.
        if (!animateWidth) currentWidth = targetW
        if (!animateHeight) currentHeight = targetH

        val widthChanged = animateWidth && targetW != targetWidth
        val heightChanged = animateHeight && targetH != targetHeight

        targetWidth = targetW
        targetHeight = targetH

        if (!widthChanged && !heightChanged) {
            // No new target for the animated axis; an existing tween keeps running.
            apply()
            return
        }

        fromWidth = currentWidth
        fromHeight = currentHeight
        pendingStart = true
        running = true
        SizeBehaviorRegistry.add(this)
        apply()
    }

    fun cancel() {
        running = false
        SizeBehaviorRegistry.remove(this)
    }

    fun tick(nanos: Long) {
        if (!running) return
        if (pendingStart) {
            pendingStart = false
            startNanos = nanos
        }
        val elapsedMs = (nanos - startNanos) / 1_000_000f
        val progress = (elapsedMs / durationMs).coerceIn(0f, 1f)
        val eased = easing.eased(progress)

        currentWidth = interpolate(fromWidth, targetWidth, eased)
        currentHeight = interpolate(fromHeight, targetHeight, eased)
        if (progress >= 1f) {
            currentWidth = targetWidth
            currentHeight = targetHeight
            running = false
        }
        apply()
    }

    private fun interpolate(from: Float, to: Float, t: Float): Float =
        if (from < 0f || to < 0f) to else from + (to - from) * t

    private fun apply() {
        val w = widget ?: return
        val widthPx = if (currentWidth < 0f) -1 else currentWidth.roundToInt()
        val heightPx = if (currentHeight < 0f) -1 else currentHeight.roundToInt()
        gtk_widget_set_size_request(w, widthPx, heightPx)
    }
}

/** Active size tweens; ticked from the shared GLib frame clock (16 ms). */
internal object SizeBehaviorRegistry {
    private val animators = mutableListOf<SizeAnimator>()

    fun add(animator: SizeAnimator) {
        if (animators.none { it === animator }) animators += animator
    }

    fun remove(animator: SizeAnimator) {
        animators.removeAll { it === animator }
    }

    fun tick(nanos: Long) {
        if (animators.isEmpty()) return
        var index = animators.size - 1
        while (index >= 0) {
            val animator = animators[index]
            animator.tick(nanos)
            if (!animator.running) animators.removeAt(index)
            index -= 1
        }
    }
}
