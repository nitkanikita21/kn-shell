package dev.knshell.uikit.components.form

import androidx.compose.runtime.Composable
import dev.knshell.compose.Key
import dev.knshell.compose.rawStyle
import dev.knshell.compose.align
import dev.knshell.compose.Align
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Semantics
import dev.knshell.compose.Slider
import dev.knshell.compose.enabled
import dev.knshell.compose.hExpand
import dev.knshell.compose.minWidth
import dev.knshell.compose.onKey
import dev.knshell.compose.slider
import dev.knshell.uikit.a11y.uFieldControl
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.colorSet
import dev.knshell.uikit.core.metrics
import dev.knshell.uikit.core.uDimmed
import dev.knshell.uikit.core.uFocusRing
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme
import kotlin.math.roundToInt

/** Slots of [USlider] an application can restyle. */
data class USliderUi(
    /** The slider itself: its track, highlight and knob. */
    val base: Modifier = Modifier,
)

data class USliderDefaults(
    val color: UColor = UColor.Primary,
    val size: USize = USize.Md,
    val ui: USliderUi = USliderUi(),
) : UComponentDefaults

/** The slider defaults of the current theme. */
@Composable
fun uSliderDefaults(): USliderDefaults = UTheme.config.find() ?: USliderDefaults()

private const val MIN_WIDTH = 160
private const val TROUGH_HEIGHT = 6

/** Share of the whole range that Page Up and Page Down move by. */
private const val PAGE_FRACTION = 0.1f

/**
 * A number from [range], in steps of [step]. Arrows (Right and Up add a step, Left and Down take one)
 * move it, Page Up and Page Down by a tenth of the range, Home and End go to the ends. The keys are
 * consumed, so the framework's own handling does not move the slider a second time.
 *
 * [accessibleLabel] names the slider, since it has no visible text. [valueText] describes the value
 * for screen readers, for example "30 %".
 */
@Composable
fun USlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    accessibleLabel: String,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..100f,
    step: Float = 1f,
    color: UColor = uSliderDefaults().color,
    size: USize = uSliderDefaults().size,
    disabled: Boolean = false,
    valueText: ((Float) -> String)? = null,
    ui: USliderUi = USliderUi(),
) {
    val tokens = UTheme.tokens
    val themed = uSliderDefaults().ui
    val metrics = size.metrics
    val set = tokens.colors.colorSet(color)
    val shown = value.coerceIn(range)

    Slider(
        value = shown,
        min = range.start,
        max = range.endInclusive,
        step = step,
        modifier = Modifier
            .uFieldControl(
                Semantics(
                    role = Role.Slider,
                    label = accessibleLabel,
                    valueNow = shown.toDouble(),
                    valueMin = range.start.toDouble(),
                    valueMax = range.endInclusive.toDouble(),
                    valueText = valueText?.invoke(shown),
                    disabled = true.takeIf { disabled },
                ),
            )
            .onKey(capture = true) { event ->
                if (event.ctrl || event.alt || event.meta) return@onKey false
                val next = sliderKeyValue(event.key, shown, range, step) ?: return@onKey false
                if (next != shown) onValueChange(next)
                true
            }
            .slider(
                troughColor = tokens.colors.secondary,
                troughHeight = TROUGH_HEIGHT,
                highlightColor = set.fill,
                knobColor = tokens.colors.foreground,
                knobSize = metrics.icon,
                transitionMs = tokens.motion.themeMs,
            )
            // GTK grows the trough to hold the knob; a negative margin lets the knob overlap it.
            .rawStyle(" trough > slider", "margin: -${(metrics.icon - TROUGH_HEIGHT) / 2}px;")
            .align(v = Align.Center)
            .hExpand()
            .minWidth(MIN_WIDTH)
            .uFocusRing()
            .uDimmed(disabled)
            .enabled(!disabled)
            .then(themed.base)
            .then(ui.base)
            .then(modifier),
        onValueChange = { onValueChange(snapSliderValue(it, range, step)) },
    )
}

/**
 * The value that [key] leads to from [value], clamped to [range] and snapped to [step], or `null`
 * when the key does not move a slider.
 */
internal fun sliderKeyValue(
    key: Key,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
): Float? {
    val span = range.endInclusive - range.start
    val moved = when (key) {
        Key.Right, Key.Up -> value + step
        Key.Left, Key.Down -> value - step
        Key.PageUp -> value + span * PAGE_FRACTION
        Key.PageDown -> value - span * PAGE_FRACTION
        Key.Home -> return range.start
        Key.End -> return range.endInclusive
        else -> return null
    }
    return snapSliderValue(moved, range, step)
}

/** [value] clamped to [range] and rounded to the nearest step counted from the start of the range. */
internal fun snapSliderValue(value: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    val clamped = value.coerceIn(range)
    if (step <= 0f) return clamped
    val steps = ((clamped - range.start) / step).roundToInt()
    return (range.start + steps * step).coerceIn(range)
}
