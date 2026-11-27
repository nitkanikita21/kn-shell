package dev.knshell.uikit.theme

import dev.knshell.compose.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every pair of the theme that carries text stays at WCAG AA (4.5:1) for any wallpaper. */
class ThemeContrastTest {

    private val minimum = 4.5f

    /** Grey, muted, saturated and two-tone wallpapers all around the hue circle. */
    private fun palettes(): List<WallpaperPalette> = listOf(WallpaperPalette.Achromatic) +
        (0 until 360 step 5).flatMap { hue ->
            listOf(0.03f to 0.02f, 0.06f to 0.04f, 0.1f to 0.06f, 0.18f to 0.08f, 0.25f to 0.12f)
                .flatMap { (family, mean) ->
                    val main = PaletteSwatch(l = 0.5f, c = family, h = hue.toFloat(), weight = 0.7f)
                    val second = PaletteSwatch(l = 0.8f, c = family, h = ((hue + 140) % 360).toFloat(), weight = 0.3f)
                    listOf(
                        WallpaperPalette(hue.toFloat(), mean, listOf(main)),
                        WallpaperPalette(hue.toFloat(), mean, listOf(main, second)),
                    )
                }
        }

    private fun themes(): Sequence<Pair<String, ThemeTokens>> = sequence {
        palettes().forEach { palette ->
            ThemeTuning.presets.forEach { (preset, tuning) ->
                val what = "hue ${palette.hue}, family ${palette.familyChroma}, ${palette.secondHue}, $preset"
                yield("dark, $what" to ThemeScale.dark(palette, tuning))
                yield("light, $what" to ThemeScale.light(palette, tuning))
            }
        }
    }

    private fun textPairs(t: ThemeTokens): List<Triple<String, Color, Color>> = listOf(
        Triple("foreground on background", t.foreground, t.background),
        Triple("cardForeground on card", t.cardForeground, t.card),
        Triple("mutedForeground on card", t.mutedForeground, t.card),
        Triple("mutedForeground on background", t.mutedForeground, t.background),
        Triple("mutedForeground on secondary", t.mutedForeground, t.secondary),
        Triple("popoverForeground on popover", t.popoverForeground, t.popover),
        Triple("primaryForeground on primary", t.primaryForeground, t.primary),
        Triple("secondaryForeground on secondary", t.secondaryForeground, t.secondary),
        Triple("accentForeground on accent", t.accentForeground, t.accent),
        Triple("destructiveForeground on destructive", t.destructiveForeground, t.destructive),
        Triple("successForeground on success", t.successForeground, t.success),
        Triple("warningForeground on warning", t.warningForeground, t.warning),
        Triple("infoForeground on info", t.infoForeground, t.info),
    ) + listOf(
        "primaryText" to t.primaryText,
        "errorText" to t.errorText,
        "successText" to t.successText,
        "warningText" to t.warningText,
        "infoText" to t.infoText,
    ).flatMap { (name, text) ->
        listOf(
            Triple("$name on background", text, t.background),
            Triple("$name on a faint tint of itself", text, mix(text, t.background, 0.14f)),
        )
    }

    private fun mix(over: Color, under: Color, share: Float): Color {
        fun channel(a: Int, b: Int) = (a * share + b * (1 - share)).toInt()
        return Color(channel(over.r, under.r), channel(over.g, under.g), channel(over.b, under.b))
    }

    @Test
    fun `text is legible in every theme`() {
        val worst = mutableMapOf<String, Pair<Float, String>>()
        themes().forEach { (theme, tokens) ->
            textPairs(tokens).forEach { (pair, text, fill) ->
                val ratio = contrastRatio(text, fill)
                val mode = theme.substringBefore(',')
                val key = "$mode: $pair"
                if (ratio < minimum && ratio < (worst[key]?.first ?: minimum)) worst[key] = ratio to theme
            }
        }

        assertTrue(
            worst.isEmpty(),
            worst.entries.joinToString("\n", "pairs below $minimum:\n") { (pair, v) -> "$pair = ${v.first} ($v)" },
        )
    }

    @Test
    fun `the ratio runs from one to twenty one`() {
        assertEquals(21f, contrastRatio(Color(0, 0, 0), Color(255, 255, 255)), 0.01f)
        assertEquals(1f, contrastRatio(Color(18, 52, 86), Color(18, 52, 86)), 0.001f)
    }

    @Test
    fun `a new token cannot be left out of the specimen list`() {
        val tokens = ThemeScale.fallback()

        assertEquals(30, tokens.entries().size)
        assertEquals(30, tokens.entries().map { it.first }.toSet().size)
    }
}
