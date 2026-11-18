package dev.knshell.app.ui.helpers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import dev.knshell.app.theme.DesignTokens
import dev.knshell.app.theme.ThemeMode
import dev.knshell.app.theme.ThemeService

@Composable
fun collectTheme(): State<DesignTokens> {
    val service by injectDi<ThemeService>()
    return service.tokens.collectAsState()
}

/** The user's dark/light choice — [ThemeMode.Auto] follows the wallpaper's suggestion. */
@Composable
fun collectThemeMode(): State<ThemeMode> {
    val service by injectDi<ThemeService>()
    return service.mode.collectAsState()
}
