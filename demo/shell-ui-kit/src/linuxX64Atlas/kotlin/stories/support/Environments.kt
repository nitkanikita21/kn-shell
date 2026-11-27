package stories.support

import dev.knshell.atlas.runner.AtlasEnvironment
import dev.knshell.compose.Modifier
import dev.knshell.compose.padding
import dev.knshell.uikit.theme.DesignTokens
import dev.knshell.uikit.theme.PaletteSwatch
import dev.knshell.uikit.theme.ThemeScale
import dev.knshell.uikit.theme.USurface
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.WallpaperPalette

/** A sample wallpaper, so that the catalog shows the kit tinted as a real shell would be. */
fun samplePalette(hue: Float = 150f): WallpaperPalette = WallpaperPalette(
    hue = hue,
    chroma = 0.05f,
    swatches = listOf(PaletteSwatch(l = 0.5f, c = 0.09f, h = hue, weight = 1f)),
)

fun sampleTokens(isDark: Boolean, hue: Float = 150f): DesignTokens {
    val tokens = DesignTokens.default()
    val palette = samplePalette(hue)
    return tokens.copy(colors = if (isDark) ThemeScale.dark(palette) else ThemeScale.light(palette))
}

private fun environment(name: String, isDark: Boolean) = AtlasEnvironment(name) { story ->
    UTheme(sampleTokens(isDark)) {
        USurface(Modifier.padding(all = 16)) { story() }
    }
}

/** Every story is shown and recorded in both modes. */
val environments: List<AtlasEnvironment> =
    listOf(environment("Dark", isDark = true), environment("Light", isDark = false))
