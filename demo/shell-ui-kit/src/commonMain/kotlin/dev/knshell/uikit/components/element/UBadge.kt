package dev.knshell.uikit.components.element

import androidx.compose.runtime.Composable
import dev.knshell.compose.Align
import dev.knshell.compose.Icon
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.align
import dev.knshell.compose.height
import dev.knshell.compose.padding
import dev.knshell.compose.semantics
import dev.knshell.compose.semiBold
import dev.knshell.compose.Semantics
import dev.knshell.icons.IconRef
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.UVariant
import dev.knshell.uikit.core.metrics
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uVariant
import dev.knshell.uikit.core.variantStyle
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** Slots of [UBadge] an application can restyle. */
data class UBadgeUi(
    /** The badge itself. */
    val base: Modifier = Modifier,
    val label: Modifier = Modifier,
)

data class UBadgeDefaults(
    val color: UColor = UColor.Primary,
    val variant: UVariant = UVariant.Soft,
    val size: USize = USize.Md,
    val ui: UBadgeUi = UBadgeUi(),
) : UComponentDefaults

/** The badge defaults of the current theme. */
@Composable
fun uBadgeDefaults(): UBadgeDefaults = UTheme.config.find() ?: UBadgeDefaults()

/** Between the leading icon and the text. */
private const val BADGE_GAP = 4

/**
 * A short status mark or counter. It takes no input and has no role of its own; its accessible name
 * is [label]. The badge keeps its natural width: it is never stretched by its parent.
 */
@Composable
fun UBadge(
    label: String,
    modifier: Modifier = Modifier,
    color: UColor = uBadgeDefaults().color,
    variant: UVariant = uBadgeDefaults().variant,
    size: USize = uBadgeDefaults().size,
    leadingIcon: IconRef? = null,
    ui: UBadgeUi = UBadgeUi(),
) {
    val style = UTheme.tokens.colors.variantStyle(color, variant)
    val metrics = size.metrics
    val themed = uBadgeDefaults().ui
    Row(
        spacing = BADGE_GAP,
        modifier = Modifier
            .uVariant(style, radius = UTheme.tokens.radius.sm + 2)
            .uFont(metrics.fontSize - 1)
            .semiBold()
            .height(metrics.height - 12)
            .padding(horizontal = metrics.paddingX - 4)
            .align(h = Align.Start)
            .semantics(Semantics(label = label))
            .then(themed.base)
            .then(ui.base)
            .then(modifier),
    ) {
        leadingIcon?.let {
            Icon(it, modifier = Modifier.align(v = Align.Center), size = metrics.icon, color = style.foreground)
        }
        Label(label, align = 0f, modifier = Modifier.align(v = Align.Center).then(themed.label).then(ui.label))
    }
}
