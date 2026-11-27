package dev.knshell.uikit.components.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.knshell.compose.Align
import dev.knshell.compose.Column
import dev.knshell.compose.Icon
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.SemanticsRef
import dev.knshell.compose.align
import dev.knshell.compose.hExpand
import dev.knshell.compose.padding
import dev.knshell.compose.semantics
import dev.knshell.compose.semanticsRef
import dev.knshell.compose.semiBold
import dev.knshell.compose.testTag
import dev.knshell.icons.IconRef
import dev.knshell.icons.lucide.Lucide
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.core.UAction
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.UVariant
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uVariant
import dev.knshell.uikit.core.variantStyle
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** Slots of [UAlert] an application can restyle. */
data class UAlertUi(
    /** The alert itself. */
    val base: Modifier = Modifier,
    val icon: Modifier = Modifier,
    val title: Modifier = Modifier,
    val description: Modifier = Modifier,
    /** The row of actions. */
    val actions: Modifier = Modifier,
)

data class UAlertDefaults(
    val color: UColor = UColor.Info,
    val variant: UVariant = UVariant.Soft,
    /** The accessible name of the close button. */
    val closeLabel: String = "Закрити",
    val ui: UAlertUi = UAlertUi(),
) : UComponentDefaults

/** The alert defaults of the current theme. */
@Composable
fun uAlertDefaults(): UAlertDefaults = UTheme.config.find() ?: UAlertDefaults()

/** Longest line of the description before it wraps. */
private const val TEXT_CHARS = 48

/**
 * A message in place: a [title], an optional [description], an icon in the status [color], actions
 * and an optional close button. Tab goes through the actions, then the close button; Enter or Space
 * runs one. [UColor.Error] and [UColor.Warning] are announced at once, the others as a status.
 *
 * [icon] defaults to the one that says what [color] means; a neutral alert has none.
 */
@Composable
fun UAlert(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    color: UColor = uAlertDefaults().color,
    variant: UVariant = uAlertDefaults().variant,
    icon: IconRef? = null,
    actions: List<UAction> = emptyList(),
    onClose: (() -> Unit)? = null,
    ui: UAlertUi = UAlertUi(),
) {
    val tokens = UTheme.tokens
    val defaults = uAlertDefaults()
    val themed = defaults.ui
    val style = tokens.colors.variantStyle(color, variant)
    val titleRef = remember { SemanticsRef() }
    val descriptionRef = remember { SemanticsRef() }
    val solid = variant == UVariant.Solid
    val urgent = color == UColor.Error || color == UColor.Warning
    val iconRef = icon ?: color.statusIcon()

    Row(
        spacing = tokens.spacing.md,
        modifier = Modifier
            .hExpand()
            .uVariant(style, radius = tokens.radius.md)
            .uFont(size = 13)
            .padding(horizontal = tokens.spacing.md, vertical = tokens.spacing.md)
            .semantics(
                Semantics(
                    role = if (urgent) Role.Alert else Role.Status,
                    labelledBy = titleRef,
                    describedBy = descriptionRef.takeIf { description != null },
                ),
            )
            .then(themed.base)
            .then(ui.base)
            .then(modifier),
    ) {
        if (iconRef != null) {
            Icon(
                iconRef,
                modifier = Modifier.align(v = Align.Start).then(themed.icon).then(ui.icon),
                size = tokens.icon.size,
                color = style.foreground,
            )
        }
        Column(spacing = tokens.spacing.xs, modifier = Modifier.hExpand().align(v = Align.Center)) {
            Label(
                title,
                align = 0f,
                modifier = Modifier.semanticsRef(titleRef).semiBold().then(themed.title).then(ui.title),
            )
            if (description != null) {
                Label(
                    description,
                    align = 0f,
                    wrap = true,
                    maxWidthChars = TEXT_CHARS,
                    modifier = Modifier.semanticsRef(descriptionRef).then(themed.description).then(ui.description),
                )
            }
            if (actions.isNotEmpty()) {
                Row(
                    spacing = tokens.spacing.sm,
                    modifier = Modifier.then(themed.actions).then(ui.actions),
                ) {
                    actions.forEachIndexed { index, action ->
                        UButton(
                            action.label,
                            onClick = action.onClick,
                            modifier = action.testTag?.let { Modifier.testTag(it) } ?: Modifier,
                            // On a solid alert its own colour would vanish: neutral buttons there.
                            color = action.color ?: if (solid) UColor.Neutral else color,
                            variant = when {
                                solid -> UVariant.Solid
                                index == 0 -> UVariant.Soft
                                else -> UVariant.Ghost
                            },
                            size = USize.Sm,
                            leadingIcon = action.icon,
                        )
                    }
                }
            }
        }
        if (onClose != null) {
            UButton(
                onClick = onClose,
                modifier = Modifier.align(v = Align.Start),
                color = UColor.Neutral,
                variant = if (solid) UVariant.Solid else UVariant.Ghost,
                size = USize.Sm,
                leadingIcon = Lucide.X,
                accessibleLabel = defaults.closeLabel,
            )
        }
    }
}

/** The icon that says what this colour means, or `null` for a colour that says nothing. */
internal fun UColor.statusIcon(): IconRef? = when (this) {
    UColor.Success -> Lucide.CircleCheck
    UColor.Info, UColor.Primary, UColor.Secondary -> Lucide.Info
    UColor.Warning -> Lucide.TriangleAlert
    UColor.Error -> Lucide.CircleX
    UColor.Neutral -> null
}
