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

    /** Who picks the dark/light scale: the palette ([ThemeMode.Auto]) or the user. */
    val mode: StateFlow<ThemeMode>

    suspend fun reloadTheme()

    /**
     * Overrides the mode. Re-derives the tokens from the last palette, so switching does not
     * have to re-read the wallpaper (and works before the first palette arrives, too).
     */
    fun setMode(mode: ThemeMode)
}

/** Hue used when the wallpaper has no chromatic clusters (palette-core reports NAN). */
private const val NEUTRAL_HUE = 250f

class ThemeServiceImpl(
    private val json: Json,
    private val wallpaperSource: WallpaperSource
) : ThemeService {
    override val tokens: MutableStateFlow<DesignTokens> = MutableStateFlow<DesignTokens>(DesignTokens.default())

    override val mode: MutableStateFlow<ThemeMode> = MutableStateFlow(ThemeMode.Auto)

    // Last palette the wallpaper produced (`reloadTheme` owns it): a mode switch re-derives the
    // tokens from these instead of shelling out to palette-core again.
    private var paletteHue: Float = NEUTRAL_HUE
    private var paletteChroma: Float = 0f
    private var paletteSuggestsDark: Boolean = true

    override fun setMode(mode: ThemeMode) {
        this.mode.value = mode
        tokens.value = DesignTokens.fromPalette(paletteHue, paletteChroma, resolveDark())
    }

    /** The mode the user asked for, or the palette's guess when they did not ask. */
    private fun resolveDark(): Boolean = when (mode.value) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.Auto -> paletteSuggestsDark
    }

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

        paletteHue = paletteJson.suggestedHue ?: NEUTRAL_HUE
        paletteChroma = paletteJson.suggestedChroma
        paletteSuggestsDark = paletteJson.suggestedMode == "dark"

        tokens.value = DesignTokens.fromPalette(paletteHue, paletteChroma, resolveDark())
    }
}
