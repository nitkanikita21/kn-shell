package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import dev.knshell.compose.Button
import dev.knshell.compose.Modifier
import dev.knshell.compose.align
import dev.knshell.compose.Align
import dev.knshell.compose.background
import dev.knshell.compose.labelStyle
import dev.knshell.compose.testTag

/** A button of the chrome: dimmed and inert while not [enabled], highlighted while [active]. */
@Composable
internal fun ActionButton(
    text: String,
    tag: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val look = with(AtlasTheme) {
        val base = Modifier.themedButton().align(h = Align.Start, v = Align.Center)
        when {
            !enabled -> base.labelStyle(dim, FONT, FONT_SIZE)
            active -> base.background(accent2, radius = RADIUS, transitionMs = 0).labelStyle(accent, FONT, FONT_SIZE)
            else -> base
        }
    }
    Button(text, modifier = look.testTag(tag), onClick = { if (enabled) onClick() })
}
