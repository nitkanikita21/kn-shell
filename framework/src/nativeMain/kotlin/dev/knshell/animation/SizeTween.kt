// The tween/spring integrator for one content-driven size value: no Compose state, one
// `queue_resize` per changed frame.

package dev.knshell.animation

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Node-level animator for a single content-driven size value.
 *
 * It deliberately owns **no** Compose state: no `mutableStateOf`, no snapshot writes, no
 * recomposition. The value is advanced from the GTK frame clock by
 * [SizeAnimationRegistry] and pushed to the native layout manager, so animating the size of
 * a container costs zero compositions and one `queue_resize` per changed frame.
 *
 * Typical loop (see [SizeAnimationRegistry.tick]):
 *  1. the node probes the natural content size and calls [retarget] when it changed;
 *  2. [advance] integrates one frame and reports whether more frames are needed;
 *  3. the caller pushes [current] to the widget.
 *
 * The first frame after construction (or after [snapTo]) only adopts the target — it never
 * animates from a bogus 0/empty size. The same applies to the first frame after a
 * [retarget]: only the timestamp is recorded, so a target set while the frame clock was
 * idle does not integrate the (huge) gap since the previous animation.
 *
 * All members are main-thread only, exactly like the GTK calls around them.
 */
internal class SizeTween {

    /** Value to render, in logical pixels. Never rounded here. */
    var current: Double = 0.0
        private set

    /** Spring velocity in px/s; ignored by [AnimationSpec.Tween] and [AnimationSpec.Snap]. */
    var velocity: Double = 0.0
        private set

    /** Latest target requested through [retarget]. */
    var target: Double = 0.0
        private set

    /** Timestamp of the last [advance], or 0 while waiting for the first frame. */
    var lastNanos: Long = 0L
        private set

    /** True while the value still has to move; drives `SizeAnimationRegistry.hasWork`. */
    var isAnimating: Boolean = false
        private set

    private var spec: AnimationSpec = AnimationSpec.Tween()
    private var from: Double = 0.0
    private var elapsedNanos: Long = 0L
    private var primed: Boolean = false

    /**
     * Requests [newTarget] with [newSpec].
     *
     * Restarts from the *current* value (never from the previous start value), which is what
     * makes interrupting an animation jump-free. A spring keeps its velocity. Repeating the
     * same target and spec is a no-op, so it is safe to call on every frame.
     */
    fun retarget(newTarget: Double, newSpec: AnimationSpec) {
        val specChanged = newSpec != spec
        if (primed && !specChanged && newTarget == target) return

        spec = newSpec
        target = newTarget
        // Wait for the next frame to integrate; the gap since the previous frame may be
        // minutes if the clock was idle while the target changed.
        lastNanos = 0L

        if (!primed) return

        when (newSpec) {
            is AnimationSpec.Snap -> settleAt(newTarget)
            is AnimationSpec.Tween -> {
                from = current
                elapsedNanos = 0L
                velocity = 0.0
                isAnimating = current != newTarget
            }
            is AnimationSpec.Spring -> {
                // Keep the current velocity: interrupting a spring continues its momentum.
                isAnimating = current != newTarget || velocity != 0.0
            }
        }
    }

    /**
     * Integrates one frame.
     *
     * The elapsed time is clamped to 1..50 ms: frames that arrive in a burst (144 Hz) do not
     * speed the tween up, and a long pause (hidden window, idle clock) cannot teleport the
     * value. After a pause the animation continues from where it stopped, just a bit slower
     * than wall clock.
     *
     * @return true if another frame is needed.
     */
    fun advance(nowNanos: Long): Boolean {
        if (!primed) {
            // First frame after construction: adopt the target, do not animate from 0.
            primed = true
            lastNanos = nowNanos
            settleAt(target)
            return false
        }

        if (lastNanos == 0L || nowNanos <= lastNanos) {
            // First frame after a retarget (or a non-monotonic timestamp): only stamp it.
            lastNanos = nowNanos
            return isAnimating
        }

        val dtNanos = (nowNanos - lastNanos).coerceIn(MIN_DT_NANOS, MAX_DT_NANOS)
        lastNanos = nowNanos

        if (!isAnimating) return false

        val dtSeconds = dtNanos.toDouble() / 1_000_000_000.0
        return when (val activeSpec = spec) {
            is AnimationSpec.Snap -> {
                settleAt(target)
                false
            }

            is AnimationSpec.Tween -> {
                if (activeSpec.durationMs <= 0) {
                    settleAt(target)
                    return false
                }
                elapsedNanos += dtNanos
                val fraction = (
                    elapsedNanos.toDouble() / (activeSpec.durationMs.toDouble() * 1_000_000.0)
                    ).coerceIn(0.0, 1.0)
                current = from + (target - from) * activeSpec.easing.eased(fraction.toFloat()).toDouble()
                velocity = 0.0
                if (fraction >= 1.0) {
                    settleAt(target)
                    false
                } else {
                    true
                }
            }

            is AnimationSpec.Spring -> {
                val stiffness = activeSpec.stiffness.toDouble().coerceAtLeast(1.0)
                val damping = 2.0 * activeSpec.dampingRatio.toDouble().coerceAtLeast(0.0) * sqrt(stiffness)
                val threshold = activeSpec.visibilityThreshold.toDouble().coerceAtLeast(0.0)

                val acceleration = -stiffness * (current - target) - damping * velocity
                velocity += acceleration * dtSeconds
                current += velocity * dtSeconds

                if (abs(current - target) < threshold && abs(velocity) < threshold * 10.0) {
                    settleAt(target)
                    false
                } else {
                    true
                }
            }
        }
    }

    /**
     * Jumps to [value] without animating (initialisation, map/resync).
     *
     * The next [advance] only records its timestamp, so the following frames integrate a
     * sane dt instead of the gap since the last animation.
     */
    fun snapTo(value: Double) {
        primed = true
        settleAt(value)
        lastNanos = 0L
    }

    private fun settleAt(value: Double) {
        current = value
        target = value
        from = value
        velocity = 0.0
        elapsedNanos = 0L
        isAnimating = false
    }

    private companion object {
        const val MIN_DT_NANOS = 1_000_000L // 1 ms
        const val MAX_DT_NANOS = 50_000_000L // 50 ms
    }
}
