// `Text` — the label primitive: font size, optional animated colour and ellipsize limits.
// Thin wrapper over `dev.knshell.compose.Label`.

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui.primitives

import androidx.compose.runtime.Composable
import dev.knshell.compose.Color
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.fontSize
import dev.knshell.compose.textColor
import kotlinx.cinterop.ExperimentalForeignApi

/** A label at [size] pixels; optionally coloured and colour-animated. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 13,
    color: Color? = null,
    transitionMs: Int = 200,
    ellipsize: Boolean = false,
    maxWidthChars: Int = -1,
) {
    var modifier = modifier.fontSize(size)
    if (color != null) modifier = modifier.textColor(color, transitionMs)
    Label(text, modifier, ellipsize = ellipsize, maxWidthChars = maxWidthChars)
}
