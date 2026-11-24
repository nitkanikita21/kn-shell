package dev.knshell.app.ui.bar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.knshell.app.ui.helpers.collectTheme
import dev.knshell.compose.Box
import dev.knshell.compose.Modifier
import dev.knshell.compose.background
import dev.knshell.compose.height
import dev.knshell.compose.padding
import dev.knshell.compose.radius

@Composable
fun Island(modifier: Modifier, content: @Composable (() -> Unit) = {}) {
    val theme by collectTheme()
    Box(
        Modifier
            .background(theme.colors.background)
            .height(36)
            .padding(16, 8)
            .radius(theme.radius.xl)
            .then(modifier),
        content
    )
}