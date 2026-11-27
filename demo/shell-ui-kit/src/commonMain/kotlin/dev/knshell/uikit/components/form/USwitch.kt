package dev.knshell.uikit.components.form

import androidx.compose.runtime.Composable
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.Tristate
import dev.knshell.compose.align
import dev.knshell.compose.enabled
import dev.knshell.compose.focusable
import dev.knshell.compose.margin
import dev.knshell.compose.onClick
import dev.knshell.compose.onKey
import dev.knshell.compose.size
import dev.knshell.compose.weight
import dev.knshell.uikit.a11y.uFieldControl
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.colorSet
import dev.knshell.uikit.core.metrics
import dev.knshell.uikit.core.uDimmed
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uFocusRing
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** Slots of [USwitch] an application can restyle. */
data class USwitchUi(
    /** The whole row: the track and the text beside it. */
    val base: Modifier = Modifier,
    val track: Modifier = Modifier,
    val knob: Modifier = Modifier,
    val label: Modifier = Modifier,
    val description: Modifier = Modifier,
)

data class USwitchDefaults(
    val color: UColor = UColor.Primary,
    val size: USize = USize.Md,
    val ui: USwitchUi = USwitchUi(),
) : UComponentDefaults

/** The switch defaults of the current theme. */
@Composable
fun uSwitchDefaults(): USwitchDefaults = UTheme.config.find() ?: USwitchDefaults()

/** Space between the track and its knob, in pixels. */
private const val TRACK_PADDING = 2

/** Height of the track for a [USize]; its width is nine fifths of it, 36 by 20 for Md. */
private fun trackHeight(size: USize): Int = when (size) {
    USize.Xs -> 14
    USize.Sm -> 16
    USize.Md -> 20
    USize.Lg -> 24
    USize.Xl -> 28
}

/**
 * A setting that takes effect at once, on or off. Space, Enter and a click toggle it. While
 * [loading] it says it is busy and ignores input.
 *
 * It needs a [label] or an [accessibleLabel]. The track is drawn by the kit, so [color] paints the
 * track when it is on.
 */
@Composable
fun USwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    description: String? = null,
    color: UColor = uSwitchDefaults().color,
    size: USize = uSwitchDefaults().size,
    loading: Boolean = false,
    disabled: Boolean = false,
    accessibleLabel: String? = null,
    ui: USwitchUi = USwitchUi(),
) {
    require(label != null || accessibleLabel != null) { "a USwitch needs a label or an accessibleLabel" }
    val tokens = UTheme.tokens
    val themed = uSwitchDefaults().ui
    val set = tokens.colors.colorSet(color)
    val metrics = size.metrics
    val height = trackHeight(size)
    val width = height * 9 / 5
    val knob = height - 2 * TRACK_PADDING
    val interactive = !disabled && !loading
    val toggle = {
        if (interactive) onCheckedChange(!checked)
    }

    Row(
        spacing = metrics.gap,
        modifier = Modifier
            .focusable(!disabled)
            .uFieldControl(
                Semantics(
                    role = Role.Switch,
                    label = accessibleLabel ?: label,
                    description = description,
                    checked = if (checked) Tristate.True else Tristate.False,
                    disabled = true.takeIf { disabled },
                    busy = true.takeIf { loading },
                ),
            )
            .onClick { toggle() }
            .onKey { event ->
                val handled = event.key == Key.Space || event.key == Key.Enter
                if (handled) toggle()
                handled
            }
            .uFocusRing()
            .enabled(!disabled)
            .uDimmed(disabled)
            .then(themed.base)
            .then(ui.base)
            .then(modifier),
    ) {
        Row(
            modifier = Modifier
                .size(width, height)
                .uFill(
                    if (checked) set.fill else tokens.colors.accent,
                    radius = height / 2,
                )
                .align(v = Align.Start)
                .then(themed.track)
                .then(ui.track),
        ) {
            if (checked) Box(Modifier.weight())
            Box(
                modifier = Modifier
                    .margin(horizontal = TRACK_PADDING, vertical = TRACK_PADDING)
                    .size(knob)
                    .uFill(
                        if (checked) set.onFill else tokens.colors.foreground,
                        radius = knob / 2,
                    )
                    .align(v = Align.Center)
                    .then(themed.knob)
                    .then(ui.knob),
            )
        }
        if (label != null || description != null) {
            Column(spacing = 2, modifier = Modifier.align(v = Align.Start)) {
                label?.let {
                    Label(
                        it,
                        align = 0f,
                        modifier = Modifier
                            .uFont(metrics.fontSize)
                            .then(themed.label)
                            .then(ui.label),
                    )
                }
                description?.let {
                    Label(
                        it,
                        align = 0f,
                        wrap = true,
                        modifier = Modifier
                            .uFont(metrics.fontSize - 1)
                            .uTextColor(tokens.colors.mutedForeground)
                            .then(themed.description)
                            .then(ui.description),
                    )
                }
            }
        }
    }
}
