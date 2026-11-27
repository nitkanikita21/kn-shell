package dev.knshell.uikit.components.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.knshell.uikit.theme.UTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * How much of a [duration] is left. The class itself is plain: [advance] takes time away, and no time
 * is taken while [paused]. A [Duration.INFINITE] countdown never finishes and stays full.
 */
class UCountdown(val duration: Duration) {
    /** The time left. */
    var remaining: Duration by mutableStateOf(duration)
        private set

    /** Whether [advance] is ignored for now. */
    var paused: Boolean by mutableStateOf(false)
        private set

    /** Whether the time is up. An infinite countdown is never finished. */
    val finished: Boolean get() = duration.isFinite() && remaining <= Duration.ZERO

    /** The share of [duration] left: 1 at the start, 0 at the end, always 1 when it is infinite. */
    val progress: Float get() {
        if (!duration.isFinite()) return 1f
        if (duration <= Duration.ZERO) return 0f
        val total = duration.inWholeNanoseconds.toDouble()
        return (remaining.inWholeNanoseconds.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    /** Takes [delta] away from the time left, unless paused, finished or infinite. */
    fun advance(delta: Duration) {
        if (paused || !duration.isFinite() || delta <= Duration.ZERO || remaining <= Duration.ZERO) return
        remaining = (remaining - delta).coerceAtLeast(Duration.ZERO)
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /** Gives the whole [duration] back. */
    fun restart() {
        remaining = duration
    }
}

/**
 * A [UCountdown] of [duration] that runs frame by frame while [paused] is `false` and calls [onFinish]
 * once it is up. It is kept as long as [duration] stays the same.
 *
 * It does not run when `motion.animate` is off: then the countdown stays full, as in pictures. An
 * infinite [duration] never runs and never finishes.
 */
@Composable
fun rememberUCountdown(duration: Duration, paused: Boolean, onFinish: () -> Unit): UCountdown {
    val countdown = remember(duration) { UCountdown(duration) }
    val finish by rememberUpdatedState(onFinish)
    SideEffect {
        if (paused) countdown.pause() else countdown.resume()
    }
    val animate = UTheme.tokens.motion.animate
    LaunchedEffect(countdown, animate) {
        if (!animate || !countdown.duration.isFinite()) return@LaunchedEffect
        var last = -1L
        while (!countdown.finished) {
            withFrameNanos { nanos ->
                if (last >= 0L) countdown.advance((nanos - last).nanoseconds)
                last = nanos
            }
        }
        finish()
    }
    return countdown
}
