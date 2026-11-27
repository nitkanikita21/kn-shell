package dev.knshell.uikit.components.form

import androidx.compose.runtime.Composable
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Icon
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.Tristate
import dev.knshell.compose.align
import dev.knshell.compose.weight
import dev.knshell.compose.enabled
import dev.knshell.compose.focusable
import dev.knshell.compose.onClick
import dev.knshell.compose.onKey
import dev.knshell.compose.size
import dev.knshell.icons.lucide.Lucide
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

/** The state of a [UCheckbox]: [Mixed] means some of the things it stands for are chosen. */
enum class UChecked { On, Off, Mixed }

/** Slots of [UCheckbox] an application can restyle. */
data class UCheckboxUi(
    /** The whole row: the box and the text beside it. */
    val base: Modifier = Modifier,
    val box: Modifier = Modifier,
    val label: Modifier = Modifier,
    val description: Modifier = Modifier,
)

data class UCheckboxDefaults(
    val color: UColor = UColor.Primary,
    val size: USize = USize.Md,
    val ui: UCheckboxUi = UCheckboxUi(),
) : UComponentDefaults

/** The checkbox defaults of the current theme. */
@Composable
fun uCheckboxDefaults(): UCheckboxDefaults = UTheme.config.find() ?: UCheckboxDefaults()

/** Corner radius of the box, in pixels. */
private const val BOX_RADIUS = 5

/**
 * An independent yes or no, with a mixed state for a group of things. Space toggles it, and so does
 * a click; Enter does not, it is left to the form. [UChecked.Mixed] and [UChecked.Off] turn it on.
 *
 * It needs a [label] or an [accessibleLabel]. Inside a `UFormField` the field's name and message
 * are attached as well.
 */
@Composable
fun UCheckbox(
    checked: UChecked,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    description: String? = null,
    color: UColor = uCheckboxDefaults().color,
    size: USize = uCheckboxDefaults().size,
    disabled: Boolean = false,
    accessibleLabel: String? = null,
    ui: UCheckboxUi = UCheckboxUi(),
) {
    require(label != null || accessibleLabel != null) { "a UCheckbox needs a label or an accessibleLabel" }
    val tokens = UTheme.tokens
    val themed = uCheckboxDefaults().ui
    val set = tokens.colors.colorSet(color)
    val metrics = size.metrics
    val boxSize = metrics.icon + 2
    val filled = checked != UChecked.Off
    val tristate = when (checked) {
        UChecked.On -> Tristate.True
        UChecked.Off -> Tristate.False
        UChecked.Mixed -> Tristate.Mixed
    }
    val toggle = {
        if (!disabled) onCheckedChange(checked != UChecked.On)
    }

    Row(
        spacing = metrics.gap,
        modifier = Modifier
            .focusable(!disabled)
            .uFieldControl(
                Semantics(
                    role = Role.Checkbox,
                    label = accessibleLabel ?: label,
                    description = description,
                    checked = tristate,
                    disabled = true.takeIf { disabled },
                ),
            )
            .onClick { toggle() }
            .onKey { event ->
                val space = event.key == Key.Space
                if (space) toggle()
                space
            }
            .uFocusRing()
            .enabled(!disabled)
            .uDimmed(disabled)
            .then(themed.base)
            .then(ui.base)
            .then(modifier),
    ) {
        Box(
            modifier = Modifier
                .size(boxSize)
                .uFill(
                    if (filled) set.fill else tokens.colors.card,
                    border = if (filled) set.fill else tokens.colors.input,
                    radius = BOX_RADIUS,
                )
                .align(v = Align.Start)
                .then(themed.box)
                .then(ui.box),
        ) {
            if (filled) {
                Icon(
                    if (checked == UChecked.Mixed) Lucide.Minus else Lucide.Check,
                    modifier = Modifier.weight().align(Align.Center),
                    size = metrics.icon - 3,
                    color = set.onFill,
                )
            }
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
