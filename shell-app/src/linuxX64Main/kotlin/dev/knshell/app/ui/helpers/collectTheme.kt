package dev.knshell.app.ui.helpers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import dev.knshell.app.theme.DesignTokens
import dev.knshell.app.theme.ThemeMode
import dev.knshell.app.theme.ThemeService
import dev.knshell.app.theme.ThemeTuning
import dev.knshell.app.theme.WallpaperPalette

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

/** How strongly the wallpaper is allowed to colour the UI (see [ThemeTuning]). */
@Composable
fun collectThemeTuning(): State<ThemeTuning> {
    val service by injectDi<ThemeService>()
    return service.tuning.collectAsState()
}

/** The palette the current tokens were derived from — hue, second hue, family chroma. */
@Composable
fun collectPalette(): State<WallpaperPalette> {
    val service by injectDi<ThemeService>()
    return service.palette.collectAsState()
}
