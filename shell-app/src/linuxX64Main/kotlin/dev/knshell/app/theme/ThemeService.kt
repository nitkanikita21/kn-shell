package dev.knshell.app.theme

import dev.knshell.app.theme.AwwwWallpaperSource
import dev.knshell.app.theme.DesignTokens
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

interface ThemeService {
    val tokens: StateFlow<DesignTokens>
    suspend fun reloadTheme()
}

/** Hue used when the wallpaper has no chromatic clusters (palette-core reports NAN). */
private const val NEUTRAL_HUE = 250f

class ThemeServiceImpl(
    private val json: Json,
    private val wallpaperSource: WallpaperSource
) : ThemeService {
    override val tokens: MutableStateFlow<DesignTokens> = MutableStateFlow<DesignTokens>(DesignTokens.default())

    override suspend fun reloadTheme() {
        val imagePath = wallpaperSource.query().also {
            println("img path $it")
        } ?: return

        val rawJson = withContext(Dispatchers.IO) { PaletteClient.extractSync(imagePath) } ?: return
        val paletteJson = json.decodeFromString<PaletteJson>(rawJson)


        println("""
            colors: ${paletteJson.colors.joinToString(", ")}
            mode: ${paletteJson.suggestedMode}
            isFallback: ${paletteJson.isFallback}
            hash: ${paletteJson.sourceHash}
        """.trimIndent())

        tokens.value = DesignTokens.fromPalette(
            paletteJson.suggestedHue ?: NEUTRAL_HUE,
            paletteJson.suggestedChroma,
            paletteJson.suggestedMode == "dark"
        )
    }
}
