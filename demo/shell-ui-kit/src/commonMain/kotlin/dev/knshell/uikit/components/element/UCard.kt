package dev.knshell.uikit.components.element

import androidx.compose.runtime.Composable
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Semantics
import dev.knshell.compose.clipOverflow
import dev.knshell.compose.fillWidth
import dev.knshell.compose.minHeight
import dev.knshell.compose.padding
import dev.knshell.compose.semantics
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** How a card is set against the window: raised with a border, or as a quiet well. */
enum class UCardVariant { Outline, Soft, Subtle }

/** Slots of [UCard] an application can restyle. */
data class UCardUi(
    /** The card itself. */
    val base: Modifier = Modifier,
    val header: Modifier = Modifier,
    val content: Modifier = Modifier,
    val footer: Modifier = Modifier,
)

data class UCardDefaults(
    val variant: UCardVariant = UCardVariant.Outline,
    val ui: UCardUi = UCardUi(),
) : UComponentDefaults

/** The card defaults of the current theme. */
@Composable
fun uCardDefaults(): UCardDefaults = UTheme.config.find() ?: UCardDefaults()

/**
 * A raised surface grouping related content, with an optional [header] and [footer]. The parts are
 * separated by a hairline. Nothing in a card takes input: place buttons in it as usual.
 *
 * With [accessibleLabel] the card is a named group for a screen reader; without it, it is only
 * visual grouping.
 */
@Composable
fun UCard(
    modifier: Modifier = Modifier,
    variant: UCardVariant = uCardDefaults().variant,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    accessibleLabel: String? = null,
    ui: UCardUi = UCardUi(),
    content: @Composable () -> Unit,
) {
    val tokens = UTheme.tokens
    val themed = uCardDefaults().ui
    val outline = variant == UCardVariant.Outline
    val surface = if (outline) tokens.colors.card else tokens.colors.secondary
    val text = if (outline) tokens.colors.cardForeground else tokens.colors.secondaryForeground
    // The soft variant has no outline; the other two keep the hairline around the whole card.
    val border = if (variant == UCardVariant.Soft) null else tokens.colors.border

    Column(
        modifier = Modifier
            .uFill(surface, border = border, radius = tokens.radius.lg)
            .uTextColor(text)
            .clipOverflow()
            .semantics(
                Semantics(
                    role = if (accessibleLabel != null) Role.Group else null,
                    label = accessibleLabel,
                ),
            )
            .then(themed.base)
            .then(ui.base)
            .then(modifier),
    ) {
        header?.let {
            CardPart(themed.header.then(ui.header), it)
            CardHairline()
        }
        CardPart(themed.content.then(ui.content), content)
        footer?.let {
            CardHairline()
            CardPart(themed.footer.then(ui.footer), it)
        }
    }
}

/** One part of a card, padded from the card edge. */
@Composable
private fun CardPart(modifier: Modifier, content: @Composable () -> Unit) {
    val spacing = UTheme.tokens.spacing
    Column(modifier = Modifier.fillWidth().padding(horizontal = spacing.lg, vertical = spacing.md).then(modifier)) {
        content()
    }
}

/** The one pixel line between two parts of a card. */
@Composable
private fun CardHairline() {
    Box(modifier = Modifier.fillWidth().minHeight(1).uFill(UTheme.tokens.colors.border))
}
