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
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.clipOverflow
import dev.knshell.compose.height
import dev.knshell.compose.hExpand
import dev.knshell.compose.minWidth
import dev.knshell.compose.semantics
import dev.knshell.compose.weight
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.colorSet
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme
import kotlin.math.roundToInt

/** Slots of [UProgress] an application can restyle. */
data class UProgressUi(
    /** The track. */
    val base: Modifier = Modifier,
    /** The filled part of the track. */
    val fill: Modifier = Modifier,
)

data class UProgressDefaults(
    val color: UColor = UColor.Primary,
    val size: USize = USize.Md,
    val ui: UProgressUi = UProgressUi(),
) : UComponentDefaults

/** The progress defaults of the current theme. */
@Composable
fun uProgressDefaults(): UProgressDefaults = UTheme.config.find() ?: UProgressDefaults()

/** Width of a track that is not given one. */
private const val MIN_TRACK_WIDTH = 200

/** Share of the track the indeterminate segment takes. */
private const val SEGMENT = 0.3f

/** Time of one slide of the indeterminate segment, from one end to the other and back. */
private const val SLIDE_PERIOD_NANOS = 1_600_000_000L

/** Thickness of the track, in pixels. */
private fun USize.trackHeight(): Int = when (this) {
    USize.Xs -> 4
    USize.Sm -> 6
    USize.Md -> 8
    USize.Lg -> 10
    USize.Xl -> 12
}

/**
 * A progress bar. With a [value] up to [max] it fills that share of the track. With `null` it is
 * indeterminate: a segment slides along the track while `motion.animate` is on, and holds still
 * otherwise. [accessibleLabel] names the bar; the value is announced as a number, or the bar as busy.
 */
@Composable
fun UProgress(
    value: Float?,
    accessibleLabel: String,
    modifier: Modifier = Modifier,
    max: Float = 1f,
    color: UColor = uProgressDefaults().color,
    size: USize = uProgressDefaults().size,
    ui: UProgressUi = UProgressUi(),
) {
    require(max > 0f) { "a UProgress needs a max above zero" }
    val tokens = UTheme.tokens
    val themed = uProgressDefaults().ui
    val trackHeight = size.trackHeight()
    val fraction = value?.let { (it / max).coerceIn(0f, 1f) }
    val fillModifier = Modifier.uFill(tokens.colors.colorSet(color).fill).then(themed.fill).then(ui.fill)

    val sliding = value == null && tokens.motion.animate
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(sliding) {
        if (sliding) {
            while (true) {
                withFrameNanos { nanos -> phase = triangle(nanos) }
            }
        }
    }
    val start = if (sliding) phase * (1f - SEGMENT) else 0f

    val reading = Semantics(
        role = Role.ProgressBar,
        label = accessibleLabel,
        valueNow = value?.coerceIn(0f, max)?.toDouble(),
        valueMin = 0.0,
        valueMax = max.toDouble(),
        valueText = fraction?.let { "${(it * 100).roundToInt()} %" },
        busy = true.takeIf { value == null },
    )

    Row(
        modifier = Modifier
            .minWidth(MIN_TRACK_WIDTH)
            .hExpand()
            .height(trackHeight)
            .uFill(tokens.colors.secondary, radius = trackHeight / 2)
            .clipOverflow()
            .semantics(reading)
            .then(themed.base)
            .then(ui.base)
            .then(modifier),
    ) {
        if (fraction != null) {
            Share(before = 0f, filled = fraction, after = 1f - fraction, fill = fillModifier)
        } else {
            Share(before = start, filled = SEGMENT, after = 1f - SEGMENT - start, fill = fillModifier)
        }
    }
}

/** Three neighbouring shares of a track: empty, filled, empty. A share of zero is left out. */
@Composable
private fun Share(before: Float, filled: Float, after: Float, fill: Modifier) {
    if (before > 0f) Box(Modifier.weight(before.toDouble()))
    if (filled > 0f) Box(Modifier.weight(filled.toDouble()).then(fill))
    if (after > 0f) Box(Modifier.weight(after.toDouble()))
}

/** 0 up to 1 and back again, once per [SLIDE_PERIOD_NANOS]. */
private fun triangle(nanos: Long): Float {
    val t = (nanos % SLIDE_PERIOD_NANOS).toFloat() / SLIDE_PERIOD_NANOS
    return if (t < 0.5f) t * 2f else (1f - t) * 2f
}
