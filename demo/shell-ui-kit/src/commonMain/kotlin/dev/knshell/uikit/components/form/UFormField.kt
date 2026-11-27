package dev.knshell.uikit.components.form

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.knshell.compose.Column
import dev.knshell.compose.FocusRequester
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.SemanticsRef
import dev.knshell.compose.medium
import dev.knshell.compose.onClick
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.semanticsRef
import dev.knshell.uikit.a11y.LocalUField
import dev.knshell.uikit.a11y.UFieldContext
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** Slots of [UFormField] an application can restyle. */
data class UFormFieldUi(
    /** The whole field. */
    val base: Modifier = Modifier,
    /** The name above the control. */
    val label: Modifier = Modifier,
    /** The help or error text below the control. */
    val message: Modifier = Modifier,
)

data class UFormFieldDefaults(
    val ui: UFormFieldUi = UFormFieldUi(),
) : UComponentDefaults

/** The form field defaults of the current theme. */
@Composable
fun uFormFieldDefaults(): UFormFieldDefaults = UTheme.config.find() ?: UFormFieldDefaults()

private const val LABEL_FONT_SIZE = 13
private const val MESSAGE_FONT_SIZE = 12

/**
 * A name, a help or error text and the control they belong to. The control inside takes the name
 * and the message as its own: a click on [label] moves the focus to it. [required] adds an asterisk
 * to the name. An [error] replaces [description] and marks the control as invalid.
 */
@Composable
fun UFormField(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    error: String? = null,
    required: Boolean = false,
    ui: UFormFieldUi = UFormFieldUi(),
    content: @Composable () -> Unit,
) {
    val tokens = UTheme.tokens
    val themed = uFormFieldDefaults().ui
    val labelRef = remember { SemanticsRef() }
    val messageRef = remember { SemanticsRef() }
    val control = remember { FocusRequester() }
    val message = error ?: description
    val context = UFieldContext(
        label = labelRef,
        message = messageRef,
        control = control,
        hasMessage = message != null,
        invalid = error != null,
        required = required,
    )

    Column(
        spacing = tokens.spacing.xs,
        modifier = Modifier
            .uFont(LABEL_FONT_SIZE)
            .then(themed.base)
            .then(ui.base)
            .then(modifier),
    ) {
        Row(spacing = 2) {
            Label(
                label,
                align = 0f,
                modifier = Modifier
                    .semanticsRef(labelRef)
                    .medium()
                    .onClick { control.requestFocus() }
                    .pointerCursor()
                    .then(themed.label)
                    .then(ui.label),
            )
            if (required) {
                Label("*", modifier = Modifier.uTextColor(tokens.colors.errorText).medium())
            }
        }
        CompositionLocalProvider(LocalUField provides context) { content() }
        message?.let {
            Label(
                it,
                align = 0f,
                wrap = true,
                modifier = Modifier
                    .semanticsRef(messageRef)
                    .uFont(MESSAGE_FONT_SIZE)
                    .uTextColor(if (error != null) tokens.colors.errorText else tokens.colors.mutedForeground)
                    .then(themed.message)
                    .then(ui.message),
            )
        }
    }
}
