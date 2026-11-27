package dev.knshell.uikit.components.overlays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.knshell.compose.Column
import dev.knshell.compose.FocusRequester
import dev.knshell.compose.FocusScope
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Semantics
import dev.knshell.compose.focusRequester
import dev.knshell.compose.padding
import dev.knshell.compose.semantics
import dev.knshell.shell.Popup
import dev.knshell.uikit.core.UAlign
import dev.knshell.uikit.core.USide
import dev.knshell.uikit.overlay.UPopupSurface
import dev.knshell.uikit.overlay.UTrigger
import dev.knshell.uikit.overlay.toPopupAlign
import dev.knshell.uikit.overlay.toPopupSide
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** Slots of [UPopover] an application can restyle. */
data class UPopoverUi(
    /** The popup surface around the content. */
    val surface: Modifier = Modifier,
    /** The content, inside the surface. */
    val body: Modifier = Modifier,
)

data class UPopoverDefaults(
    val side: USide = USide.Bottom,
    val align: UAlign = UAlign.Start,
    val ui: UPopoverUi = UPopoverUi(),
) : UComponentDefaults

/** The popover defaults of the current theme. */
@Composable
fun uPopoverDefaults(): UPopoverDefaults = UTheme.config.find() ?: UPopoverDefaults()

/**
 * A panel of any content next to its trigger. A click on the trigger opens or closes it; on opening,
 * the focus goes to the first control of [content]. Escape or a click outside closes it and gives the
 * focus back to the trigger. Tab past the last control closes it and moves the focus on from the
 * trigger.
 *
 * The panel is a non-modal dialog: the rest of the window stays reachable. [accessibleLabel] is its name.
 */
@Composable
fun UPopover(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    accessibleLabel: String,
    modifier: Modifier = Modifier,
    side: USide = uPopoverDefaults().side,
    align: UAlign = uPopoverDefaults().align,
    ui: UPopoverUi = UPopoverUi(),
    content: @Composable () -> Unit,
    trigger: @Composable (UTrigger) -> Unit,
) {
    val tokens = UTheme.tokens
    val themed = uPopoverDefaults().ui
    val triggerRequester = remember { FocusRequester() }

    Popup(
        open = open,
        onDismiss = { onOpenChange(false) },
        modifier = modifier,
        side = side.toPopupSide(),
        align = align.toPopupAlign(),
        autoFocus = true,
        content = {
            UPopupSurface(modifier = themed.surface.then(ui.surface)) {
                FocusScope(
                    autoFocus = false,
                    restoreFocus = false,
                    onTabOut = { backwards ->
                        onOpenChange(false)
                        triggerRequester.moveFocus(backwards)
                    },
                ) {
                    Column(
                        spacing = tokens.spacing.sm,
                        modifier = Modifier
                            .semantics(Semantics(role = Role.Dialog, label = accessibleLabel))
                            .padding(all = tokens.spacing.md)
                            .then(themed.body)
                            .then(ui.body),
                    ) {
                        content()
                    }
                }
            }
        },
    ) {
        trigger(
            UTrigger(
                modifier = Modifier
                    .focusRequester(triggerRequester)
                    .semantics(Semantics(expanded = open, hasPopup = true)),
                onClick = { onOpenChange(!open) },
            ),
        )
    }
}
