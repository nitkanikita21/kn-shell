package dev.knshell.app.ui.bar

import androidx.compose.runtime.Composable
import dev.knshell.compose.Box
import dev.knshell.compose.Modifier
import dev.knshell.compose.height
import dev.knshell.compose.padding
import dev.knshell.compose.radius
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.theme.UTheme

/** One island of the bar: a surface of the theme that its content sits on. */
@Composable
fun Island(modifier: Modifier, content: @Composable (() -> Unit) = {}) {
    val tokens = UTheme.tokens
    Box(
        Modifier
            .uFill(tokens.colors.background)
            .uTextColor(tokens.colors.foreground)
            .uFont(size = 13)
            .height(36)
            .padding(16, 8)
            .radius(tokens.radius.xl)
            .then(modifier),
        content,
    )
}
