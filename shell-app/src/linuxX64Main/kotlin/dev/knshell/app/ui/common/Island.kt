package dev.knshell.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.knshell.app.ui.helpers.collectTheme
import dev.knshell.compose.Box
import dev.knshell.compose.Modifier
import dev.knshell.compose.background
import dev.knshell.compose.radius

@Composable
fun Island(modifier: Modifier, content: @Composable (() -> Unit) = {}) {
    val theme by collectTheme()
    Box(
        Modifier
            .background(theme.colors.background)
            .radius(theme.radius.md)
            .then(modifier),
        content
    )
}