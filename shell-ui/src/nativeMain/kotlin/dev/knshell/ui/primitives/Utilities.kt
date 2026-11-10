@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui.primitives

import androidx.compose.runtime.Composable
import dev.knshell.compose.Box
import dev.knshell.compose.Color
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.fontSize
import dev.knshell.compose.size
import dev.knshell.compose.textColor
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Small utility primitives. They carry no visual identity: no colours, radii or layout
 * opinions — only the minimum needed to write UI without repeating boilerplate.
 *
 *  - spacing: [Space], [HSpace], [VSpace], [Spacer]
 *  - text:    [Icon], [Text]
 *
 * Layout comes from `dev.knshell.compose` (`Row`, `Column`, `Box`, `CenterBox`), styling
 * from the Modifier DSL, and data from `Shell.services` (see docs/shell-api.md).
 */

/** An empty box used as spacing. Prefer [Space]. */
@Composable
fun Spacer(modifier: Modifier = Modifier) {
    Box(modifier)
}

/** Fixed-size empty space: `Space(8)`, `Space(width = 6, height = 4)`. */
@Composable
fun Space(width: Int = 0, height: Int = 0) {
    Box(Modifier.size(width = width, height = height))
}

/** Horizontal space: `HSpace(8)`. */
@Composable
fun HSpace(width: Int) = Space(width = width, height = 0)

/** Vertical space: `VSpace(6)`. */
@Composable
fun VSpace(height: Int) = Space(width = 0, height = height)

/** A Nerd Font glyph at [size] pixels; optionally coloured and colour-animated. */
@Composable
fun Icon(glyph: String, size: Int = 13, color: Color? = null, transitionMs: Int = 0) {
    var modifier = Modifier.fontSize(size)
    if (color != null) modifier = modifier.textColor(color, transitionMs)
    Label(glyph, modifier)
}

/** A label at [size] pixels; optionally coloured and colour-animated. */
@Composable
fun Text(text: String, size: Int = 13, color: Color? = null, transitionMs: Int = 0) {
    var modifier = Modifier.fontSize(size)
    if (color != null) modifier = modifier.textColor(color, transitionMs)
    Label(text, modifier)
}
