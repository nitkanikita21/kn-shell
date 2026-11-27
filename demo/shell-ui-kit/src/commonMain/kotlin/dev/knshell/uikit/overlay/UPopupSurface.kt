// ---------------------------------------------------------------------------
// The surface floating parts are drawn on (popover, menu, select list, tooltip), and the kit's
// placement vocabulary translated to the framework's.
// ---------------------------------------------------------------------------

package dev.knshell.uikit.overlay

import androidx.compose.runtime.Composable
import dev.knshell.compose.Column
import dev.knshell.compose.Modifier
import dev.knshell.compose.margin
import dev.knshell.compose.padding
import dev.knshell.shell.PopupAlign
import dev.knshell.shell.PopupSide
import dev.knshell.uikit.core.UAlign
import dev.knshell.uikit.core.USide
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.theme.UTheme

internal fun USide.toPopupSide(): PopupSide = when (this) {
    USide.Top -> PopupSide.Top
    USide.Bottom -> PopupSide.Bottom
    USide.Left -> PopupSide.Left
    USide.Right -> PopupSide.Right
}

internal fun UAlign.toPopupAlign(): PopupAlign = when (this) {
    UAlign.Start -> PopupAlign.Start
    UAlign.Center -> PopupAlign.Center
    UAlign.End -> PopupAlign.End
}

/** The gap between a floating surface and its trigger. */
private const val GAP = 4

/**
 * The body of a floating part: the theme's popover colours, a border and rounded corners. Put it in
 * the content of `Popup`.
 */
@Composable
internal fun UPopupSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tokens = UTheme.tokens
    Column(
        modifier = Modifier
            .uFill(tokens.colors.popover, border = tokens.colors.border, radius = tokens.radius.md)
            .uTextColor(tokens.colors.popoverForeground)
            .uFont(size = 13)
            .padding(all = tokens.spacing.xs)
            .margin(horizontal = GAP, vertical = GAP)
            .then(modifier),
    ) { content() }
}
