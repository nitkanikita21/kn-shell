package dev.knshell.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Spring animation for a single float.
 *
 * `org.jetbrains.compose.animation:animation-core` (which provides
 * `animateFloatAsState` / `AnimatedContent` / `spring()`) is **not published for
 * linuxX64** — only `compose.runtime` is. This implementation reproduces the
 * `animateFloatAsState(spring(...))` semantics on top of the Compose Runtime
 * primitives (`mutableStateOf` + `withFrameNanos`), so the UI code is still fully
 * driven by Compose Runtime and the frame clock supplied by `GLibFrameClock`.
 *
 * Damped harmonic oscillator:  a = -k * (x - target) - 2 * z * sqrt(k) * v
 *
 * @param targetValue value to animate towards.
 * @param stiffness   spring stiffness (higher = faster).
 * @param dampingRatio 1.0 = critically damped, < 1 = bouncy.
 */
@Composable
fun animateFloatAsState(
    targetValue: Float,
    stiffness: Float = 500f,
    dampingRatio: Float = 0.7f,
    visibilityThreshold: Float = 0.5f,
): State<Float> {
    val value = remember { mutableStateOf(targetValue) }
    val velocity = remember { floatArrayOf(0f) }
    val lastFrame = remember { longArrayOf(0L) }

    LaunchedEffect(targetValue) {
        var x = value.value
        var v = velocity[0]
        while (true) {
            var settled = false
            withFrameNanos { nanos ->
                val previous = lastFrame[0]
                lastFrame[0] = nanos
                val dt = if (previous == 0L) 0.016f else ((nanos - previous) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)

                val k = stiffness
                val c = 2f * dampingRatio * sqrt(k)
                val acceleration = -k * (x - targetValue) - c * v

                v += acceleration * dt
                x += v * dt

                if (abs(x - targetValue) < visibilityThreshold && abs(v) < visibilityThreshold * 10f) {
                    x = targetValue
                    v = 0f
                    settled = true
                }
                value.value = x
            }
            if (settled) break
        }
        velocity[0] = 0f
    }

    return value
}

/** QML-style easing curves for [animateFloatAsState]. Monotonic curves never overshoot. */
enum class Easing {
    Linear,
    OutCubic,
    InOutCubic,
}

private fun Easing.transform(t: Float): Float = when (this) {
    Easing.Linear -> t
    Easing.OutCubic -> {
        val p = 1f - t
        1f - p * p * p
    }
    Easing.InOutCubic -> if (t < 0.5f) {
        4f * t * t * t
    } else {
        val p = -2f * t + 2f
        1f - (p * p * p) / 2f
    }
}

/**
 * Time-based animation with an easing curve — the QML `NumberAnimation` analogue
 * (`Behavior on width { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }`).
 *
 * Unlike the spring overload, [Easing.OutCubic] / [Easing.InOutCubic] are monotonic: the
 * value never overshoots the target, so a parent layout (GtkBox width = sum of children)
 * does not oscillate while several children animate at once.
 */
@Composable
fun animateFloatAsState(
    targetValue: Float,
    durationMs: Int = 180,
    easing: Easing = Easing.OutCubic,
): State<Float> {
    val value = remember { mutableStateOf(targetValue) }

    LaunchedEffect(targetValue, durationMs, easing) {
        if (durationMs <= 0) {
            value.value = targetValue
            return@LaunchedEffect
        }
        val start = value.value
        if (start == targetValue) return@LaunchedEffect

        var startNanos = 0L
        var progress = 0f
        while (progress < 1f) {
            withFrameNanos { nanos ->
                if (startNanos == 0L) startNanos = nanos
                val elapsedMs = (nanos - startNanos) / 1_000_000f
                progress = (elapsedMs / durationMs).coerceIn(0f, 1f)
                value.value = start + (targetValue - start) * easing.transform(progress)
            }
        }
        value.value = targetValue
    }

    return value
}
