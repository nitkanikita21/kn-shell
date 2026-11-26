package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.knshell.atlas.core.arg.ArgSnapshot
import dev.knshell.atlas.core.arg.ArgValues
import dev.knshell.atlas.core.arg.CellArgs
import dev.knshell.atlas.core.entry.Variants
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Flow
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Scroll
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.expand
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.padding
import dev.knshell.compose.textColor

/**
 * Grid of every combination of [variants]: one framed cell per combo, each showing the story
 * rendered with that combo's axis values (see [CellArgs]) and a caption such as "Small · true".
 */
@Composable
fun MatrixView(variants: Variants, args: ArgValues, modifier: Modifier = Modifier) {
    with(AtlasTheme) {
        Scroll(modifier = modifier.expand().background(bg, transitionMs = 0)) {
            // Start-aligned vertically: a stretched flow box hands the spare height to its rows.
            Flow(spacing = 12, modifier = Modifier.padding(all = 12).align(v = Align.Start)) {
                variants.combos.forEach { combo ->
                    key(combo) {
                        MatrixCell(variants, combo, args)
                    }
                }
            }
        }
    }
}

@Composable
private fun MatrixCell(variants: Variants, combo: ArgSnapshot, args: ArgValues) {
    val cellArgs = remember(combo, args) { CellArgs(combo, args) }

    with(AtlasTheme) {
        Column(
            spacing = 8,
            modifier = Modifier
                .background(panel, radius = RADIUS, transitionMs = 0)
                .framed()
                .padding(all = 12)
                .align(Align.Start),
        ) {
            Box(Modifier.align(Align.Start)) {
                variants.render(cellArgs)
            }
            Label(
                captionFor(variants, combo),
                align = 0f,
                modifier = Modifier
                    .textColor(dim)
                    .fontSize(FONT_SIZE)
                    .fontFamily(FONT),
            )
        }
    }
}

/** Caption for one cell: the axis values of [combo] in axis order, joined by " · ". */
internal fun captionFor(variants: Variants, combo: ArgSnapshot): String =
    variants.axisArgs.joinToString(" · ") { arg -> combo[arg].toString() }
