// ---------------------------------------------------------------------------
// The paint every component builds on. All colours change with the theme's transition, so a new
// theme fades in instead of jumping.
// ---------------------------------------------------------------------------

package dev.knshell.uikit.core

import androidx.compose.runtime.Composable
import dev.knshell.compose.Color
import dev.knshell.compose.Modifier
import dev.knshell.compose.background
import dev.knshell.compose.focusRing
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.hoverBackground
import dev.knshell.compose.opacity
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.pressedBackground
import dev.knshell.compose.radius
import dev.knshell.compose.textColor
import dev.knshell.uikit.theme.UTheme

/** A fill, with an optional one pixel [border] and rounded corners. */
@Composable
fun Modifier.uFill(color: Color, border: Color? = null, radius: Int = 0, shadow: Boolean = false): Modifier =
    background(
        color,
        radius = radius,
        shadow = shadow,
        borderWidth = if (border == null) 0 else 1,
        borderColor = border,
        transitionMs = UTheme.tokens.motion.themeMs,
    )

/** Text colour of the widget and of what inherits it. */
@Composable
fun Modifier.uTextColor(color: Color): Modifier = textColor(color, transitionMs = UTheme.tokens.motion.themeMs)

/** The theme's font in [size] pixels; [mono] for code and key caps. */
@Composable
fun Modifier.uFont(size: Int, mono: Boolean = false): Modifier {
    val typography = UTheme.tokens.typography
    return fontFamily(if (mono) typography.monoFamily else typography.fontFamily).fontSize(size)
}

/** The ring around the widget that has the keyboard focus. */
@Composable
fun Modifier.uFocusRing(): Modifier = focusRing(UTheme.tokens.colors.ring, width = 2, offset = 1)

/**
 * Fill, text colour and border of a [style]. With [interactive] the widget also answers the pointer:
 * hover and pressed fills and the pointer cursor.
 */
@Composable
fun Modifier.uVariant(style: UVariantStyle, radius: Int, interactive: Boolean = false): Modifier {
    val painted = uFill(style.background, border = style.border, radius = radius).uTextColor(style.foreground)
    return if (interactive) {
        painted.hoverBackground(style.hoverBackground).pressedBackground(style.pressedBackground).pointerCursor()
    } else {
        painted.radius(radius)
    }
}

private const val DISABLED_OPACITY = 0.45f

/** The look of a control that takes no input. The input itself is stopped with `Modifier.enabled`. */
fun Modifier.uDimmed(dimmed: Boolean): Modifier = if (dimmed) opacity(DISABLED_OPACITY) else this
