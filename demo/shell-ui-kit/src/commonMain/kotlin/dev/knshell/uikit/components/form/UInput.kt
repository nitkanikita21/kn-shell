package dev.knshell.uikit.components.form

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.Align
import dev.knshell.compose.Entry
import dev.knshell.compose.Icon
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.align
import dev.knshell.compose.enabled
import dev.knshell.compose.entryStyle
import dev.knshell.compose.hExpand
import dev.knshell.compose.minHeight
import dev.knshell.compose.minWidth
import dev.knshell.compose.onFocusChange
import dev.knshell.compose.padding
import dev.knshell.icons.IconRef
import dev.knshell.uikit.a11y.LocalUField
import dev.knshell.uikit.a11y.uFieldControl
import dev.knshell.uikit.a11y.uInvalid
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.colorSet
import dev.knshell.uikit.core.metrics
import dev.knshell.uikit.core.uDimmed
import dev.knshell.uikit.core.uFill
import dev.knshell.compose.glow
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.withAlpha
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** Whether the text is shown, or hidden as a password. */
enum class UInputType { Text, Password }

/** Slots of [UInput] an application can restyle. */
data class UInputUi(
    /** The field: the bordered row around the text. */
    val base: Modifier = Modifier,
    /** Each of the optional icons. */
    val icon: Modifier = Modifier,
    /** The text entry, applied before the [UInput] modifier. */
    val input: Modifier = Modifier,
)

data class UInputDefaults(
    val color: UColor = UColor.Primary,
    val size: USize = USize.Md,
    val ui: UInputUi = UInputUi(),
) : UComponentDefaults

/** The input defaults of the current theme. */
@Composable
fun uInputDefaults(): UInputDefaults = UTheme.config.find() ?: UInputDefaults()

/** Width of the text area, before the padding and the border of the field. */
private const val MIN_TEXT_WIDTH = 240

/**
 * A single-line text field. Its text takes the keyboard focus: Tab moves in and out, and the keys
 * the text uses (letters, Backspace, arrows, Enter) stay with it. Escape is not caught.
 *
 * The field is coloured by [color] while the focus is inside it, and red when [invalid]. Inside a
 * `UFormField` the field takes its name, help and error from there; elsewhere [accessibleLabel] is
 * required. [modifier] goes to the text entry, where the test tag belongs.
 */
@Composable
fun UInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    type: UInputType = UInputType.Text,
    color: UColor = uInputDefaults().color,
    size: USize = uInputDefaults().size,
    leadingIcon: IconRef? = null,
    trailingIcon: IconRef? = null,
    disabled: Boolean = false,
    invalid: Boolean = false,
    accessibleLabel: String? = null,
    ui: UInputUi = UInputUi(),
) {
    require(accessibleLabel != null || LocalUField.current != null) {
        "a UInput outside a UFormField needs an accessibleLabel"
    }
    val tokens = UTheme.tokens
    val themed = uInputDefaults().ui
    val set = tokens.colors.colorSet(color)
    val metrics = size.metrics
    val invalidLook = uInvalid(invalid)
    var focused by remember { mutableStateOf(false) }
    val border = when {
        invalidLook -> tokens.colors.destructive
        focused -> set.fill
        else -> tokens.colors.input
    }
    val iconColor = tokens.colors.mutedForeground
    val clear = tokens.colors.card.withAlpha(0f)

    Row(
        spacing = tokens.spacing.sm,
        modifier = Modifier
            .uFill(tokens.colors.card, border = border, radius = tokens.radius.md)
            .minHeight(metrics.height - 2)
            .minWidth(MIN_TEXT_WIDTH)
            .padding(horizontal = metrics.paddingX)
            .onFocusChange { focused = it }
            // A second line inside the border marks the field the keyboard is in.
            .then(if (focused) Modifier.glow(border, blur = 0, spread = 1) else Modifier)
            .uDimmed(disabled)
            .then(themed.base)
            .then(ui.base),
    ) {
        leadingIcon?.let {
            Icon(
                it,
                modifier = Modifier.align(v = Align.Center).then(themed.icon).then(ui.icon),
                size = metrics.icon,
                color = iconColor,
            )
        }
        Entry(
            value = value,
            placeholder = placeholder,
            password = type == UInputType.Password,
            onValueChange = onValueChange,
            modifier = Modifier
                .uFieldControl(
                    Semantics(
                        role = Role.TextBox,
                        label = accessibleLabel,
                        disabled = true.takeIf { disabled },
                        invalid = true.takeIf { invalid },
                    ),
                )
                .hExpand()
                .enabled(!disabled)
                .uFont(metrics.fontSize)
                .entryStyle(
                    textColor = tokens.colors.foreground,
                    caretColor = set.fill,
                    selectionBackground = set.fill,
                    selectionColor = set.onFill,
                    transitionMs = tokens.motion.themeMs,
                    placeholderColor = tokens.colors.mutedForeground,
                )
                .uFill(clear)
                .then(themed.input)
                .then(ui.input)
                .then(modifier),
        )
        trailingIcon?.let {
            Icon(
                it,
                modifier = Modifier.align(v = Align.Center).then(themed.icon).then(ui.icon),
                size = metrics.icon,
                color = iconColor,
            )
        }
    }
}
