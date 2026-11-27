package dev.knshell.uikit.components.element

import androidx.compose.runtime.Composable
import dev.knshell.compose.Align
import dev.knshell.compose.Button
import dev.knshell.compose.Icon
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.align
import dev.knshell.compose.enabled
import dev.knshell.compose.medium
import dev.knshell.compose.minHeight
import dev.knshell.compose.padding
import dev.knshell.compose.semantics
import dev.knshell.compose.underline
import dev.knshell.icons.IconRef
import dev.knshell.icons.lucide.Lucide
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.UVariant
import dev.knshell.uikit.core.metrics
import dev.knshell.uikit.core.uDimmed
import dev.knshell.uikit.core.uFocusRing
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uVariant
import dev.knshell.uikit.core.variantStyle
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** Slots of [UButton] an application can restyle. */
data class UButtonUi(
    /** The button itself. */
    val base: Modifier = Modifier,
    val label: Modifier = Modifier,
    val icon: Modifier = Modifier,
)

data class UButtonDefaults(
    val color: UColor = UColor.Primary,
    val variant: UVariant = UVariant.Solid,
    val size: USize = USize.Md,
    val ui: UButtonUi = UButtonUi(),
) : UComponentDefaults

/** The button defaults of the current theme. */
@Composable
fun uButtonDefaults(): UButtonDefaults = UTheme.config.find() ?: UButtonDefaults()

/**
 * An action. Enter, Space or a click run [onClick].
 *
 * A button without visible text (only icons) needs [accessibleLabel]. While [loading] it stays
 * focusable, says it is busy and ignores clicks; a [disabled] one takes no input at all.
 * [content] adds widgets after the label.
 */
@Composable
fun UButton(
    label: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: UColor = uButtonDefaults().color,
    variant: UVariant = uButtonDefaults().variant,
    size: USize = uButtonDefaults().size,
    leadingIcon: IconRef? = null,
    trailingIcon: IconRef? = null,
    loading: Boolean = false,
    disabled: Boolean = false,
    accessibleLabel: String? = null,
    ui: UButtonUi = UButtonUi(),
    content: (@Composable () -> Unit)? = null,
) {
    val iconOnly = label == null && content == null
    require(!iconOnly || accessibleLabel != null) { "a UButton without text needs an accessibleLabel" }
    val tokens = UTheme.tokens
    val themed = uButtonDefaults().ui
    val style = tokens.colors.variantStyle(color, variant)
    val metrics = size.metrics
    val interactive = !disabled && !loading

    Button(
        modifier = Modifier
            .uVariant(style, radius = tokens.radius.md, interactive = interactive)
            .uFont(metrics.fontSize)
            .medium()
            // CSS minimums leave out the one pixel border on each side.
            .minHeight(metrics.height - 2)
            // The padding makes an icon alone the middle of a square.
            .padding(horizontal = if (iconOnly) (metrics.height - 2 - metrics.icon) / 2 else metrics.paddingX)
            // Its natural width, also where a column would stretch it; `Modifier.hExpand()` fills.
            .align(h = Align.Start)
            .uFocusRing()
            .uDimmed(disabled)
            .enabled(!disabled)
            .semantics(
                Semantics(
                    label = accessibleLabel ?: label,
                    disabled = true.takeIf { disabled },
                    busy = true.takeIf { loading },
                ),
            )
            .then(themed.base)
            .then(ui.base)
            .then(modifier),
        onClick = { if (interactive) onClick() },
    ) {
        Row(spacing = metrics.gap, modifier = Modifier.align(Align.Center)) {
            val iconModifier = Modifier.align(v = Align.Center).then(themed.icon).then(ui.icon)
            val leading = if (loading) Lucide.LoaderCircle else leadingIcon
            leading?.let { Icon(it, modifier = iconModifier, size = metrics.icon, color = style.foreground) }
            label?.let {
                val underlined = if (style.underline) Modifier.underline() else Modifier
                Label(it, modifier = underlined.then(themed.label).then(ui.label))
            }
            content?.invoke()
            trailingIcon?.let { Icon(it, modifier = iconModifier, size = metrics.icon, color = style.foreground) }
        }
    }
}
