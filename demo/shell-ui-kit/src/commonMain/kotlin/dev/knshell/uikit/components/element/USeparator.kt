package dev.knshell.uikit.components.element

import androidx.compose.runtime.Composable
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Orientation
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.align
import dev.knshell.compose.centerH
import dev.knshell.compose.centerV
import dev.knshell.compose.fillWidth
import dev.knshell.compose.hExpand
import dev.knshell.compose.minHeight
import dev.knshell.compose.minWidth
import dev.knshell.compose.semantics
import dev.knshell.compose.vExpand
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.UOrientation
import dev.knshell.uikit.core.colorSet
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** Slots of [USeparator] an application can restyle. */
data class USeparatorUi(
    /** The separator itself. */
    val base: Modifier = Modifier,
    /** Each line on either side of the label. */
    val line: Modifier = Modifier,
    val label: Modifier = Modifier,
)

data class USeparatorDefaults(
    val color: UColor = UColor.Neutral,
    val ui: USeparatorUi = USeparatorUi(),
) : UComponentDefaults

/** The separator defaults of the current theme. */
@Composable
fun uSeparatorDefaults(): USeparatorDefaults = UTheme.config.find() ?: USeparatorDefaults()

/** Font size of the label in the middle of a separator. */
private const val SEPARATOR_FONT_SIZE = 12

/**
 * A one pixel line dividing blocks of content, horizontal or vertical. A [label] sits in the middle
 * with a line on each side. [color] `Neutral` draws the hairline of the theme; any other colour
 * draws that colour's fill. The separator announces its orientation and takes no input.
 */
@Composable
fun USeparator(
    modifier: Modifier = Modifier,
    orientation: UOrientation = UOrientation.Horizontal,
    label: String? = null,
    color: UColor = uSeparatorDefaults().color,
    ui: USeparatorUi = USeparatorUi(),
) {
    val tokens = UTheme.tokens
    val themed = uSeparatorDefaults().ui
    val horizontal = orientation == UOrientation.Horizontal
    val line = if (color == UColor.Neutral) tokens.colors.border else tokens.colors.colorSet(color).fill
    val root = Modifier
        .semantics(
            Semantics(
                role = Role.Separator,
                orientation = if (horizontal) Orientation.Horizontal else Orientation.Vertical,
            ),
        )
        .then(themed.base)
        .then(ui.base)
        .then(modifier)

    if (label == null) {
        val rule = if (horizontal) Modifier.fillWidth().minHeight(1) else Modifier.vExpand().minWidth(1)
        Box(modifier = rule.uFill(line).then(themed.line).then(ui.line).then(root))
        return
    }

    val lineModifier = if (horizontal) {
        Modifier.hExpand().minHeight(1).centerV()
    } else {
        Modifier.vExpand().minWidth(1).centerH()
    }
    val lineFill = lineModifier.uFill(line).then(themed.line).then(ui.line)
    val text = Modifier
        .uFont(SEPARATOR_FONT_SIZE)
        .uTextColor(tokens.colors.mutedForeground)
        .align(v = Align.Center)
        .then(themed.label)
        .then(ui.label)

    if (horizontal) {
        Row(spacing = tokens.spacing.sm, modifier = Modifier.fillWidth().then(root)) {
            Box(modifier = lineFill)
            Label(label, align = 0.5f, modifier = text)
            Box(modifier = lineFill)
        }
    } else {
        Column(spacing = tokens.spacing.sm, modifier = Modifier.vExpand().then(root)) {
            Box(modifier = lineFill)
            Label(label, align = 0.5f, modifier = text)
            Box(modifier = lineFill)
        }
    }
}
