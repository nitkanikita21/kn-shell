package dev.knshell.uikit.components.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.knshell.compose.Box
import dev.knshell.compose.Modifier
import dev.knshell.compose.Semantics
import dev.knshell.compose.height
import dev.knshell.compose.hExpand
import dev.knshell.compose.opacity
import dev.knshell.compose.semantics
import dev.knshell.compose.width
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.theme.UTheme
import kotlin.math.PI
import kotlin.math.cos

/** Lowest opacity of the pulse. */
private const val PULSE_LOW = 0.5f

/** Time of one pulse, from full to low and back. */
private const val PULSE_PERIOD_NANOS = 1_600_000_000L

/**
 * A stand-in for content that is still loading: an accent block of the given size that pulses while
 * `motion.animate` is on and stays at full opacity otherwise. A screen reader does not see it; the
 * container that waits for the content should be busy.
 */
@Composable
fun USkeleton(
    modifier: Modifier = Modifier,
    width: Int? = null,
    height: Int = 12,
    radius: Int = UTheme.tokens.radius.md,
) {
    val tokens = UTheme.tokens
    val animate = tokens.motion.animate
    var pulse by remember { mutableStateOf(1f) }
    LaunchedEffect(animate) {
        if (animate) {
            while (true) {
                withFrameNanos { nanos -> pulse = pulseOpacity(nanos) }
            }
        }
    }
    val sized = (width?.let { Modifier.width(it) } ?: Modifier.hExpand()).height(height)
    Box(
        modifier = sized
            .uFill(tokens.colors.accent, radius = radius)
            .opacity(if (animate) pulse else 1f)
            .semantics(Semantics(hidden = true))
            .then(modifier),
    )
}

/** The opacity of the pulse at [nanos]: from [PULSE_LOW] up to 1 and back, once per period. */
private fun pulseOpacity(nanos: Long): Float {
    val phase = (nanos % PULSE_PERIOD_NANOS).toDouble() / PULSE_PERIOD_NANOS
    val wave = (1.0 - cos(2.0 * PI * phase)) / 2.0
    return PULSE_LOW + (1f - PULSE_LOW) * wave.toFloat()
}
