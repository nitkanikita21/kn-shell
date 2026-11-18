package dev.knshell.app.theme

import dev.knshell.app.theme.ThemeScale
import dev.knshell.compose.Color

data class DesignTokens(
    val colors: ThemeTokens,
    val radius: RadiusScale,
    val spacing: SpacingScale,
    val typography: TypeScale,
    val motion: MotionTokens,
    val icon: IconTokens,
) {
    companion object {
        /** Default theme before any wallpaper/palette is loaded. */
        fun default(): DesignTokens = DesignTokens(
            colors = ThemeScale.fallback(isDark = true),
            radius = RadiusScale(),
            spacing = SpacingScale(),
            typography = TypeScale(fontFamily = "sans"),
            motion = MotionTokens(),
            icon = IconTokens(),
        )

        /** Theme derived from a palette (hue/chroma/mode). */
        fun fromPalette(hue: Float, chroma: Float, isDark: Boolean): DesignTokens =
            DesignTokens(
                colors = if (isDark) ThemeScale.dark(hue, chroma) else ThemeScale.light(hue, chroma),
                radius = RadiusScale(),
                spacing = SpacingScale(),
                typography = TypeScale(fontFamily = "sans"),
                motion = MotionTokens(),
                icon = IconTokens(),
            )
    }
}

data class ThemeTokens(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val mutedFg: Color,
    val fg: Color,
    val accent: Color,
    val isDark: Boolean,
)

data class RadiusScale(
    val sm: Int = 4,
    val md: Int = 8,
    val lg: Int = 12,
    val xl: Int = 16,
)

data class SpacingScale(
    val xs: Int = 4,
    val sm: Int = 8,
    val md: Int = 12,
    val lg: Int = 16,
    val xl: Int = 24,
)

data class TypeScale(
    val fontFamily: String
)

data class MotionTokens(
    val hoverMs: Int = 140,
    val panelMs: Int = 220
)

data class IconTokens(
    val size: Int = 16,
    val strokeWidth: Float = 1.5f,
)
