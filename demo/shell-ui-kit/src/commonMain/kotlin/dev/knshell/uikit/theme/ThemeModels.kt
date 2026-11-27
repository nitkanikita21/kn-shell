package dev.knshell.uikit.theme

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
        /** Default theme before any wallpaper/palette is loaded: neutral, no invented colour. */
        fun default(tuning: ThemeTuning = ThemeTuning.Balanced): DesignTokens = DesignTokens(
            colors = ThemeScale.fallback(isDark = true, tuning = tuning),
            radius = RadiusScale(),
            spacing = SpacingScale(),
            typography = TypeScale(),
            motion = MotionTokens(),
            icon = IconTokens(),
        )

        /**
         * Theme derived from a wallpaper palette: hue and chroma come from the palette, the mode
         * from the caller, and how loud the tint is from [tuning].
         */
        fun fromPalette(
            palette: WallpaperPalette,
            isDark: Boolean,
            tuning: ThemeTuning = ThemeTuning.Balanced,
        ): DesignTokens =
            DesignTokens(
                colors = if (isDark) ThemeScale.dark(palette, tuning) else ThemeScale.light(palette, tuning),
                radius = RadiusScale(),
                spacing = SpacingScale(),
                typography = TypeScale(),
                motion = MotionTokens(),
                icon = IconTokens(),
            )
    }
}

/**
 * The semantic surface of the UI — the shadcn token set, not a bag of colours.
 *
 * palette-core's only job is to read the wallpaper and answer *what hue and how saturated*;
 * everything that decides how the UI looks lives here, in Kotlin. Naming follows shadcn
 * (`background`/`foreground` pairs, `card`, `popover`, `primary`, `secondary`, `muted`,
 * `accent`, `destructive`, `border`, `input`, `ring`) so a component can be reasoned about the
 * same way it would be on the web, and so "what is the hover background" has one answer
 * (`accent`) instead of a per-widget invention.
 *
 * Every `*-foreground` is the colour that is legible **on** its partner, which is why they are
 * separate tokens rather than a computed contrast: the pairing is a design decision.
 *
 * Not included on purpose: `chart-*` and `sidebar-*` from shadcn — the shell has neither
 * charts nor a sidebar, and an unused token is a token nobody keeps correct.
 */
data class ThemeTokens(
    /** The window itself. */
    val background: Color,
    /** Default text on [background]. */
    val foreground: Color,
    /** Raised surface: cards, panels, the popover body. */
    val card: Color,
    val cardForeground: Color,
    /** Floating surface (menus, tooltips) — same tone as [card] unless a design says otherwise. */
    val popover: Color,
    val popoverForeground: Color,
    /** The brand colour derived from the wallpaper: selected tabs, filled buttons, focus. */
    val primary: Color,
    /** Text/icon on [primary]. */
    val primaryForeground: Color,
    /** The second surface step: inactive tabs, wells, progress tracks. */
    val secondary: Color,
    val secondaryForeground: Color,
    /** Recessed/quiet area — same tone as [secondary]. */
    val muted: Color,
    /** Secondary text: helper lines, captions, timestamps. */
    val mutedForeground: Color,
    /** Hover/pressed wash, **not** the brand colour (that is [primary]). */
    val accent: Color,
    val accentForeground: Color,
    /** Errors and destructive actions. Independent of the wallpaper hue on purpose. */
    val destructive: Color,
    val destructiveForeground: Color,
    /** Hairlines between surfaces. */
    val border: Color,
    /** Form-control outlines (slightly stronger than [border]). */
    val input: Color,
    /** Focus ring. */
    val ring: Color,
    /** Something worked. Fill, with [successForeground] on it. */
    val success: Color,
    val successForeground: Color,
    /** Needs attention. */
    val warning: Color,
    val warningForeground: Color,
    /** Neutral information. */
    val info: Color,
    val infoForeground: Color,
    /**
     * The `*Text` tokens are the same families as text on the window or on a faint tint of
     * themselves (outline, soft, ghost and link variants), where the fill colour would not be
     * legible.
     */
    val primaryText: Color,
    val errorText: Color,
    val successText: Color,
    val warningText: Color,
    val infoText: Color,
    val isDark: Boolean,
) {
    /**
     * Every token, shadcn's set first. The demo swatches iterate this, so a new token cannot be
     * added to the theme and silently left out of the specimen.
     */
    fun entries(): List<Pair<String, Color>> = listOf(
        "background" to background,
        "foreground" to foreground,
        "card" to card,
        "cardForeground" to cardForeground,
        "popover" to popover,
        "popoverForeground" to popoverForeground,
        "primary" to primary,
        "primaryForeground" to primaryForeground,
        "secondary" to secondary,
        "secondaryForeground" to secondaryForeground,
        "muted" to muted,
        "mutedForeground" to mutedForeground,
        "accent" to accent,
        "accentForeground" to accentForeground,
        "destructive" to destructive,
        "destructiveForeground" to destructiveForeground,
        "border" to border,
        "input" to input,
        "ring" to ring,
        "success" to success,
        "successForeground" to successForeground,
        "warning" to warning,
        "warningForeground" to warningForeground,
        "info" to info,
        "infoForeground" to infoForeground,
        "primaryText" to primaryText,
        "errorText" to errorText,
        "successText" to successText,
        "warningText" to warningText,
        "infoText" to infoText,
    )
}

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
    /** Family of running text. */
    val fontFamily: String = "Adwaita Sans",
    /** Family of code and key caps. */
    val monoFamily: String = "JetBrainsMono Nerd Font",
)

data class MotionTokens(
    val hoverMs: Int = 140,
    val panelMs: Int = 220,
    /** How long colours take to change when the theme changes. */
    val themeMs: Int = 220,
)

data class IconTokens(
    val size: Int = 16,
    val strokeWidth: Float = 1.5f,
)
