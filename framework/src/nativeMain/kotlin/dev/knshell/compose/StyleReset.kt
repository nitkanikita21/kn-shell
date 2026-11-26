// ---------------------------------------------------------------------------
// Style reset — a boundary that styles of the surroundings do not cross. For previews: what is
// inside looks the way it does in a bare window, wherever the container is placed.
// ---------------------------------------------------------------------------

package dev.knshell.compose

import androidx.compose.runtime.Composable

/**
 * Stops inherited styles at this widget: colour, font family, size, weight and every other
 * property children would take from its ancestors start over, with the text colour of a bare
 * window. The other modifiers of the chain still apply, whatever their order.
 *
 * Rules that name descendants (`labelStyle`, `rawStyle(" label", …)` on an ancestor) are not
 * inheritance and still reach inside.
 */
fun Modifier.resetStyles(): Modifier = with(StyleResetElement)

/** A [Box] with [resetStyles]: its content is not styled by anything around it. */
@Composable
fun StyleReset(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(Modifier.resetStyles().then(modifier), content)
}
