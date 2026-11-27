// ---------------------------------------------------------------------------
// What a form field tells the control inside it: its name, its help or error text, and whether the
// value is wrong. `UFormField` provides it, every input applies it with `uFieldControl`.
// ---------------------------------------------------------------------------

package dev.knshell.uikit.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import dev.knshell.compose.FocusRequester
import dev.knshell.compose.Modifier
import dev.knshell.compose.Semantics
import dev.knshell.compose.SemanticsRef
import dev.knshell.compose.focusRequester
import dev.knshell.compose.semantics

/** The surroundings of one control inside a form field. */
class UFieldContext(
    /** The label of the field. */
    val label: SemanticsRef,
    /** The help or error text under the control. */
    val message: SemanticsRef,
    /** Moves the focus to the control, for a click on the label. */
    val control: FocusRequester,
    val hasMessage: Boolean,
    val invalid: Boolean,
    val required: Boolean,
)

/** The field around the current place, or `null` outside one. */
val LocalUField = compositionLocalOf<UFieldContext?> { null }

/** Whether the control here shows itself as wrong: it says so, or its field does. */
@Composable
fun uInvalid(own: Boolean = false): Boolean = own || LocalUField.current?.invalid == true

/**
 * The semantics of a form control: [semantics] completed by the surrounding field. A control with a
 * name of its own (`label` set) keeps it; otherwise the field's label names it.
 */
@Composable
fun Modifier.uFieldControl(semantics: Semantics): Modifier {
    val field = LocalUField.current ?: return semantics(semantics)
    return focusRequester(field.control).semantics(
        semantics.copy(
            labelledBy = semantics.labelledBy ?: field.label.takeIf { semantics.label == null },
            describedBy = semantics.describedBy ?: field.message.takeIf { field.hasMessage },
            invalid = if (field.invalid) true else semantics.invalid,
            required = if (field.required) true else semantics.required,
        ),
    )
}
