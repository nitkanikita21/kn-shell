@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import dev.knshell.animation.Easing
import dev.knshell.animation.eased
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kn.gtk.GtkRequisition
import kn.gtk.GtkWidget
import kn.gtk.gtk_widget_get_preferred_size
import kn.gtk.gtk_widget_set_size_request
import kotlin.math.roundToInt

/**
 * Opt-in, QML-like `Behavior on width/height`.
 *
 * Two modes, chosen by the Modifier chain:
 *  - **explicit**: when the chain also has `width()` / `height()` / `size()`, their changes
 *    are tweened;
 *  - **auto**: when the chain has no explicit size (content-driven width), the widget's
 *    natural size is measured and tweened on every content change — this is what Qt's
 *    `Behavior on width { NumberAnimation {} }` does.
 *
 * ```kotlin
 * // content-driven width still animates
 * Box(Modifier.widthBehavior().padding(14)) { Label(title) }
 *
 * // explicit size
 * Box(Modifier.sizeBehavior().size(if (active) 34 else 24, 24))
 * ```
 *
 * The tween runs on the widget node (GTK calls only), not through Compose recomposition.
 * Limits: only the size request is animated; colours are better served by [transition].
 */
/** Which axis a [BehaviorElement] animates. */
internal enum class BehaviorAxis { Width, Height, Both }

internal data class BehaviorElement(
    val durationMs: Int,
    val easing: Easing,
    val axis: BehaviorAxis,
) : ModifierElement

/** Opt-in `Behavior on width`. */
fun Modifier.widthBehavior(durationMs: Int = 180, easing: Easing = Easing.OutCubic): Modifier =
    with(BehaviorElement(durationMs, easing, BehaviorAxis.Width))

/** Opt-in `Behavior on height`. */
fun Modifier.heightBehavior(durationMs: Int = 180, easing: Easing = Easing.OutCubic): Modifier =
    with(BehaviorElement(durationMs, easing, BehaviorAxis.Height))

/** Opt-in `Behavior on size`. */
fun Modifier.sizeBehavior(durationMs: Int = 180, easing: Easing = Easing.OutCubic): Modifier =
    with(BehaviorElement(durationMs, easing, BehaviorAxis.Both))

/** Back-compat alias for [sizeBehavior]. Prefer the explicit names. */
fun Modifier.behavior(durationMs: Int = 180, easing: Easing = Easing.OutCubic): Modifier =
    sizeBehavior(durationMs, easing)

/** Something the shared frame clock advances; animators unregister when [active] is false. */
internal interface SizeTween {
    fun tick(nanos: Long)
    val active: Boolean
}

/** Per-widget tween for **explicit** size requests (see [SizeAnimator.animate]). */
internal class SizeAnimator : SizeTween {
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

    override val active: Boolean get() = running

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

    override fun tick(nanos: Long) {
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
    private val tweens = mutableListOf<SizeTween>()

    fun add(tween: SizeTween) {
        if (tweens.none { it === tween }) tweens += tween
    }

    fun remove(tween: SizeTween) {
        tweens.removeAll { it === tween }
    }

    fun tick(nanos: Long) {
        if (tweens.isEmpty()) return
        var index = tweens.size - 1
        while (index >= 0) {
            val tween = tweens[index]
            if (!tween.active) {
                tweens.removeAt(index)
            } else {
                tween.tick(nanos)
                if (!tween.active) tweens.removeAt(index)
            }
            index -= 1
        }
    }
}
