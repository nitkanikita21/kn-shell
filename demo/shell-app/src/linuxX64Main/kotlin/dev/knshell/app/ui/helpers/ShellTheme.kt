package dev.knshell.app.ui.helpers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.knshell.uikit.theme.UTheme

/**
 * The theme of the shell for everything inside: the kit's components take their look from the tokens
 * the `ThemeService` derives from the wallpaper. Put it at the root of every window; popovers and
 * dialogs inherit it.
 */
@Composable
fun ShellTheme(content: @Composable () -> Unit) {
    val tokens by collectTheme()
    UTheme(tokens, content = content)
}
