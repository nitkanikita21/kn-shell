package dev.knshell.uikit.components.element

import androidx.compose.runtime.Composable
import dev.knshell.compose.Box
import dev.knshell.compose.BorderSide
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.border
import dev.knshell.compose.centerBoth
import dev.knshell.compose.minHeight
import dev.knshell.compose.minWidth
import dev.knshell.compose.padding
import dev.knshell.compose.semantics
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.metrics
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** Slots of [UKbd] an application can restyle. */
data class UKbdUi(
    /** The row of key caps. */
    val base: Modifier = Modifier,
    /** Each key cap. */
    val key: Modifier = Modifier,
)

data class UKbdDefaults(
    val size: USize = USize.Md,
    val ui: UKbdUi = UKbdUi(),
) : UComponentDefaults

/** The key defaults of the current theme. */
@Composable
fun uKbdDefaults(): UKbdDefaults = UTheme.config.find() ?: UKbdDefaults()

/** Between two key caps of one shortcut. */
private const val KBD_GAP = 4

/**
 * A key or a shortcut, drawn as key caps in the monospace font: `["Ctrl", "K"]` is two caps side by
 * side. Its accessible name is the keys joined with "плюс", e.g. "Ctrl плюс K". Takes no input.
 */
@Composable
fun UKbd(
    keys: List<String>,
    modifier: Modifier = Modifier,
    size: USize = uKbdDefaults().size,
    ui: UKbdUi = UKbdUi(),
) {
    require(keys.isNotEmpty()) { "a UKbd needs at least one key" }
    val tokens = UTheme.tokens
    val themed = uKbdDefaults().ui
    val metrics = size.metrics
    // The CSS box is a square of height - 10; GTK minimums leave out the borders and the padding.
    val box = metrics.height - 10

    Row(
        spacing = KBD_GAP,
        modifier = Modifier
            .semantics(Semantics(label = keys.joinToString(" плюс ")))
            .then(themed.base)
            .then(ui.base)
            .then(modifier),
    ) {
        keys.forEach { key ->
            Box(
                modifier = Modifier
                    .minHeight(box - 3)
                    .minWidth(box - 14)
                    .padding(horizontal = metrics.paddingX / 2)
                    .uFill(tokens.colors.card, radius = tokens.radius.sm)
                    .border(tokens.colors.input, width = 1, sides = BorderSide.ALL - BorderSide.Bottom)
                    .border(tokens.colors.input, width = 2, sides = setOf(BorderSide.Bottom))
                    .uTextColor(tokens.colors.cardForeground)
                    .uFont(metrics.fontSize - 2, mono = true)
                    .then(themed.key)
                    .then(ui.key),
            ) {
                Label(key, align = 0.5f, modifier = Modifier.centerBoth())
            }
        }
    }
}
