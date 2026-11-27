// ---------------------------------------------------------------------------
// Which colours a (color, variant) pair means in a theme. Pure functions, tested for contrast.
// ---------------------------------------------------------------------------

package dev.knshell.uikit.core

import dev.knshell.compose.Color
import dev.knshell.uikit.theme.ThemeTokens
import kotlin.math.roundToInt

/** The three colours of one meaning. */
data class UColorSet(
    /** A filled area of this colour. */
    val fill: Color,
    /** Text and icons on [fill]. */
    val onFill: Color,
    /** This colour as text on the window, a card or a faint tint of itself. */
    val text: Color,
)

fun ThemeTokens.colorSet(color: UColor): UColorSet = when (color) {
    UColor.Primary -> UColorSet(primary, primaryForeground, primaryText)
    UColor.Secondary -> UColorSet(accent, accentForeground, foreground)
    UColor.Success -> UColorSet(success, successForeground, successText)
    UColor.Info -> UColorSet(info, infoForeground, infoText)
    UColor.Warning -> UColorSet(warning, warningForeground, warningText)
    UColor.Error -> UColorSet(destructive, destructiveForeground, errorText)
    UColor.Neutral -> UColorSet(foreground, background, foreground)
}

/** The paint of a component in one variant, with its hover and pressed fills. */
data class UVariantStyle(
    val background: Color,
    val foreground: Color,
    val border: Color,
    val hoverBackground: Color,
    val pressedBackground: Color,
    val underline: Boolean = false,
)

private const val SOFT = 0.14f
private const val SUBTLE = 0.10f
private const val HOVER = 0.18f
private const val PRESSED = 0.24f
private const val OUTLINE_BORDER = 0.55f
private const val SUBTLE_BORDER = 0.30f
private const val HOVER_BRIGHTNESS = 1.12f
private const val PRESSED_BRIGHTNESS = 0.88f

fun ThemeTokens.variantStyle(color: UColor, variant: UVariant): UVariantStyle {
    val set = colorSet(color)
    fun tint(alpha: Float) = set.text.withAlpha(alpha)
    val clear = set.text.withAlpha(0f)
    fun washed(background: Color, border: Color = clear) =
        UVariantStyle(background, set.text, border, tint(HOVER), tint(PRESSED))
    return when (variant) {
        UVariant.Solid -> UVariantStyle(
            background = set.fill,
            foreground = set.onFill,
            border = set.fill.withAlpha(0f),
            hoverBackground = set.fill.brightness(HOVER_BRIGHTNESS),
            pressedBackground = set.fill.brightness(PRESSED_BRIGHTNESS),
        )
        UVariant.Outline -> washed(clear, border = set.fill.withAlpha(OUTLINE_BORDER))
        UVariant.Soft -> washed(tint(SOFT))
        UVariant.Subtle -> washed(tint(SUBTLE), border = tint(SUBTLE_BORDER))
        UVariant.Ghost -> washed(clear)
        UVariant.Link -> UVariantStyle(clear, set.text, clear, clear, clear, underline = true)
    }
}

fun Color.withAlpha(alpha: Float): Color = copy(alpha = alpha)

/** Every channel multiplied by [factor], as CSS `brightness()` does. */
fun Color.brightness(factor: Float): Color {
    fun scale(channel: Int) = (channel * factor).roundToInt().coerceIn(0, 255)
    return Color(scale(r), scale(g), scale(b), alpha)
}

/** What the eye sees of this colour painted over an opaque [backdrop]. */
fun Color.over(backdrop: Color): Color {
    fun mix(top: Int, bottom: Int) = (top * alpha + bottom * (1f - alpha)).roundToInt()
    return Color(mix(r, backdrop.r), mix(g, backdrop.g), mix(b, backdrop.b))
}
