package dev.knshell.app.ui.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.knshell.app.ui.helpers.collectPalette
import dev.knshell.app.ui.helpers.collectThemeMode
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.bold
import dev.knshell.uikit.components.element.UAvatar
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.WallpaperPalette
import dev.knshell.compose.Color
import kotlin.math.roundToInt

/** What the demo is, and why the theme looks the way it does. */
@Composable
internal fun Header() {
    val mode by collectThemeMode()
    val palette by collectPalette()
    val colors = UTheme.tokens.colors
    val muted = Modifier.uFont(size = 12).uTextColor(colors.mutedForeground)

    Row(spacing = 12) {
        UAvatar("KN Shell", size = USize.Xl, icon = dev.knshell.icons.lucide.Lucide.Home)
        Column(spacing = 2) {
            Label("KN Shell", align = 0f, modifier = Modifier.uFont(size = 20).bold())
            Label(
                "демо UI-кіта · ${if (colors.isDark) "dark" else "light"} (${mode.name.lowercase()}) · ${colors.primary.hex()}",
                align = 0f,
                modifier = muted,
            )
            Label(palette.summaryLine(), align = 0f, modifier = muted)
        }
    }
}

/** `#rrggbb`, without a colour library. */
private fun Color.hex(): String {
    fun part(value: Int): String = value.toString(16).padStart(2, '0')
    return "#${part(r)}${part(g)}${part(b)}"
}

/** `h 126° · h₂ 104° · family 0.075`: the palette behind the current tokens. */
internal fun WallpaperPalette.summaryLine(): String {
    val second = secondHue?.let { "h₂ ${it.roundToInt()}°" } ?: "h₂ —"
    return "h ${hue.roundToInt()}° · $second · family ${decimals(familyChroma, 3)}"
}

/** Kotlin/Native has no `String.format`. */
internal fun decimals(value: Float, places: Int): String {
    var scale = 1
    repeat(places) { scale *= 10 }
    val scaled = (value * scale).roundToInt()
    return "${scaled / scale}.${(scaled % scale).toString().padStart(places, '0')}"
}
