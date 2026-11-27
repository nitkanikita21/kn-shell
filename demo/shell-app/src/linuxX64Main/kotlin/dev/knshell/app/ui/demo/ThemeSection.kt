package dev.knshell.app.ui.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import dev.knshell.app.theme.ThemeService
import dev.knshell.app.ui.helpers.collectThemeMode
import dev.knshell.app.ui.helpers.collectThemeTuning
import dev.knshell.app.ui.helpers.injectDi
import dev.knshell.compose.Box
import dev.knshell.compose.Color
import dev.knshell.compose.Column
import dev.knshell.compose.Flow
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.hExpand
import dev.knshell.compose.size
import dev.knshell.compose.width
import dev.knshell.uikit.components.form.URadioGroup
import dev.knshell.uikit.components.form.URadioItem
import dev.knshell.uikit.components.form.USelect
import dev.knshell.uikit.components.form.USlider
import dev.knshell.uikit.core.UOrientation
import dev.knshell.uikit.core.over
import dev.knshell.uikit.theme.contrastRatio
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.theme.ThemeMode
import dev.knshell.uikit.theme.ThemeTuning
import dev.knshell.uikit.theme.UTheme

/** Who picks dark or light, how strongly the wallpaper tints the shell, and the tokens that follow. */
@Composable
internal fun ThemeSection() {
    val service by injectDi<ThemeService>()
    val mode by collectThemeMode()
    val tuning by collectThemeTuning()
    val tokens = UTheme.tokens

    Column(spacing = tokens.spacing.lg, modifier = Modifier.hExpand()) {
        // The wallpaper only suggests dark or light; `Auto` follows it, the others override.
        URadioGroup(
            items = ThemeMode.entries.map { URadioItem(it, it.name) },
            value = mode,
            onValueChange = service::setMode,
            legend = "Режим",
            orientation = UOrientation.Horizontal,
        )
        Column(spacing = tokens.spacing.sm, modifier = Modifier.width(360)) {
            Label("Тонування шпалерами: ${decimals(tuning.tint, 2)}×", align = 0f)
            USelect(
                items = ThemeTuning.presets.map { it.first },
                value = ThemeTuning.presets.firstOrNull { it.second == tuning }?.first,
                onValueChange = { name -> ThemeTuning.preset(name)?.let(service::setTuning) },
                label = { it },
                placeholder = "власне значення",
                accessibleLabel = "Набір тонування",
                modifier = Modifier.hExpand(),
            )
            // The slider changes only the multiplier; the rest of the chosen preset stays.
            USlider(
                value = tuning.tint,
                onValueChange = { service.setTuning(tuning.copy(tint = it)) },
                accessibleLabel = "Сила тонування",
                range = 0f..2f,
                step = 0.05f,
            )
        }
        // The theme's own list, so that a new token cannot be left out of the specimen.
        Flow(spacing = tokens.spacing.sm, maxPerLine = 6, modifier = Modifier.hExpand()) {
            tokens.colors.entries().forEach { (name, color) -> Swatch(name, color) }
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    val colors = UTheme.tokens.colors
    // The name takes whichever window colour reads better on what the eye sees of the swatch.
    val seen = color.over(colors.background)
    val text = listOf(colors.foreground, colors.background).maxBy { contrastRatio(it, seen) }
    Box(Modifier.size(96, 48).uFill(color, border = colors.border, radius = UTheme.tokens.radius.md)) {
        Label(name, modifier = Modifier.uFont(size = 9, mono = true).uTextColor(text))
    }
}
