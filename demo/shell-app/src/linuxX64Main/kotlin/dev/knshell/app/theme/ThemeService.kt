package dev.knshell.app.theme

import dev.knshell.uikit.theme.DesignTokens
import dev.knshell.uikit.theme.NEUTRAL_HUE
import dev.knshell.uikit.theme.PaletteSwatch
import dev.knshell.uikit.theme.ThemeMode
import dev.knshell.uikit.theme.ThemeTuning
import dev.knshell.uikit.theme.WallpaperPalette
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

    /**
     * The wallpaper palette the tokens were derived from — exposed for the settings UI, which
     * shows *why* the theme looks the way it does (dominant hue, second hue, family chroma).
     */
    val palette: StateFlow<WallpaperPalette>

    /** Who picks the dark/light scale: the palette ([ThemeMode.Auto]) or the user. */
    val mode: StateFlow<ThemeMode>

    /** How strongly the wallpaper is allowed to colour the UI. */
    val tuning: StateFlow<ThemeTuning>

    suspend fun reloadTheme()

    /**
     * Overrides the mode. Re-derives the tokens from the last palette, so switching does not
     * have to re-read the wallpaper (and works before the first palette arrives, too).
     */
    fun setMode(mode: ThemeMode)

    /** Changes how loud the tint is; re-derives from the cached palette, without a re-read. */
    fun setTuning(tuning: ThemeTuning)
}

class ThemeServiceImpl(
    private val json: Json,
    private val wallpaperSource: WallpaperSource,
) : ThemeService {

    override val tokens: MutableStateFlow<DesignTokens> = MutableStateFlow(DesignTokens.default())
    override val palette: MutableStateFlow<WallpaperPalette> = MutableStateFlow(WallpaperPalette.Achromatic)
    override val mode: MutableStateFlow<ThemeMode> = MutableStateFlow(ThemeMode.Auto)
    override val tuning: MutableStateFlow<ThemeTuning> = MutableStateFlow(ThemeTuning.Balanced)

    // The palette is the cache: a mode or tuning switch re-derives the tokens from it instead of
    // shelling out to palette-core again.
    private var paletteSuggestsDark: Boolean = true

    override fun setMode(mode: ThemeMode) {
        this.mode.value = mode
        rederive()
    }

    override fun setTuning(tuning: ThemeTuning) {
        this.tuning.value = tuning
        rederive()
    }

    /** The mode the user asked for, or the palette's guess when they did not ask. */
    private fun resolveDark(): Boolean = when (mode.value) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.Auto -> paletteSuggestsDark
    }

    private fun rederive() {
        tokens.value = DesignTokens.fromPalette(palette.value, resolveDark(), tuning.value)
    }

    override suspend fun reloadTheme() {
        val imagePath = wallpaperSource.query() ?: return
        val rawJson = withContext(Dispatchers.IO) { PaletteClient.extractSync(imagePath) } ?: return
        val paletteJson = json.decodeFromString<PaletteJson>(rawJson)
        palette.value = paletteJson.toWallpaperPalette()
        paletteSuggestsDark = paletteJson.suggestedMode == "dark"
        rederive()
    }
}

/**
 * palette-core's reply → the theme's design input.
 *
 * The clusters are the part that used to be dropped on the floor: `suggested_chroma` is a mean
 * and cannot say *how* saturated the wallpaper's own hue gets, nor whether a second hue family
 * is present. Keeping the clusters costs nothing (they are already in the JSON) and is what lets
 * the surfaces carry a real tint.
 */
internal fun PaletteJson.toWallpaperPalette(): WallpaperPalette = WallpaperPalette(
    hue = suggestedHue?.takeUnless { it.isNaN() } ?: NEUTRAL_HUE,
    chroma = if (suggestedChroma.isNaN()) 0f else suggestedChroma,
    swatches = colors.map { PaletteSwatch(l = it.l, c = it.c, h = it.h, weight = it.weight) },
)
