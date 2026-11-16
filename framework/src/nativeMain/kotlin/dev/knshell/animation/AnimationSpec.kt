package dev.knshell.animation

import androidx.compose.runtime.Immutable

/**
 * How a size value travels from its current value to a new target.
 *
 * This is the Compose-owned half of the animated-size feature: the spec lives in Kotlin
 * (and can be replaced on every composition), while the native side only applies the
 * resulting float and reports the natural content size. See `animatedWidth`/`animatedHeight`
 * in `dev.knshell.compose`.
 *
 * The default for containers is [Tween]: it is monotonic, so a container never
 * overshoots its target. Overshoot is not "extra air" here — the container clips its
 * children (`GTK_OVERFLOW_HIDDEN`), so a spring that overshoots would briefly cut the
 * content off. [Spring] is therefore opt-in, meant for leaves and indicators.
 *
 * Rules shared by every spec:
 *  - retargeting mid-flight always restarts from the **current** value, never from the old
 *    start value, so a new target while animating never produces a jump;
 *  - the target is content-driven (= natural size measured with `for_size = -1`) and is
 *    therefore independent of the currently applied animated value; there is no feedback
 *    loop between the animation and the layout;
 *  - float → int rounding happens only in the native allocation, never here.
 */
@Immutable
sealed interface AnimationSpec {

    /**
     * Time-based animation over [durationMs] with a monotonic easing curve.
     *
     * Default for the `animatedWidth`/`animatedHeight`/`animatedSize` modifiers:
     * `Tween(180, OutCubic)` reproduces the previous native C animation. `durationMs <= 0`
     * degenerates to [Snap].
     */
    @Immutable
    data class Tween(
        val durationMs: Int = 180,
        val easing: Easing = Easing.OutCubic,
    ) : AnimationSpec

    /**
     * Damped harmonic oscillator (see [animateFloatAsState]'s spring overload for the
     * physics): `a = -k * (x - target) - 2 * z * sqrt(k) * v`.
     *
     * Velocity is preserved across [retarget], so interrupting a spring continues with the
     * momentum it had. It settles when the distance to the target is below
     * [visibilityThreshold] and the velocity below `10 * visibilityThreshold`.
     *
     * Because it can overshoot the target, use it for leaves (dots, indicators), not for a
     * container that clips its content — unless clipping the overshoot is intended.
     */
    @Immutable
    data class Spring(
        val stiffness: Float = 500f,
        val dampingRatio: Float = 0.7f,
        val visibilityThreshold: Float = 0.5f,
    ) : AnimationSpec

    /**
     * Jump straight to the target on the next frame: no animation, no frames requested.
     * Useful to disable animation for a subtree without changing the call-site structure.
     */
    object Snap : AnimationSpec
}

/** Short constructor alias: `Tween(200, Easing.OutCubic)`. */
typealias Tween = AnimationSpec.Tween

/** Short constructor alias: `Spring(stiffness = 800f)`. */
typealias Spring = AnimationSpec.Spring

/** Short alias for [AnimationSpec.Snap], usable where a spec is expected. */
val Snap: AnimationSpec.Snap get() = AnimationSpec.Snap
