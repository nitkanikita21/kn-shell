package dev.knshell.uikit.core

import dev.knshell.uikit.theme.PaletteSwatch
import dev.knshell.uikit.theme.ThemeScale
import dev.knshell.uikit.theme.ThemeTokens
import dev.knshell.uikit.theme.ThemeTuning
import dev.knshell.uikit.theme.WallpaperPalette
import dev.knshell.uikit.theme.contrastRatio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VariantStyleTest {

    private fun themes(): List<Pair<String, ThemeTokens>> =
        (0 until 360 step 15).flatMap { hue ->
            val palette = WallpaperPalette(
                hue = hue.toFloat(),
                chroma = 0.06f,
                swatches = listOf(PaletteSwatch(l = 0.5f, c = 0.1f, h = hue.toFloat(), weight = 1f)),
            )
            ThemeTuning.presets.flatMap { (preset, tuning) ->
                listOf(
                    "dark hue $hue $preset" to ThemeScale.dark(palette, tuning),
                    "light hue $hue $preset" to ThemeScale.light(palette, tuning),
                )
            }
        }

    @Test
    fun `text of every color and variant is legible on the window and on a card`() {
        val worst = mutableMapOf<String, Float>()
        themes().forEach { (_, tokens) ->
            val mode = if (tokens.isDark) "dark" else "light"
            UColor.entries.forEach { color ->
                UVariant.entries.forEach { variant ->
                    val style = tokens.variantStyle(color, variant)
                    listOf("background" to tokens.background, "card" to tokens.card).forEach { (name, backdrop) ->
                        val ratio = contrastRatio(style.foreground, style.background.over(backdrop))
                        val key = "$mode $color $variant on $name"
                        if (ratio < 4.5f && ratio < (worst[key] ?: 4.5f)) worst[key] = ratio
                    }
                }
            }
        }

        assertTrue(worst.isEmpty(), worst.entries.joinToString("\n", "below 4.5:1:\n") { "${it.key}: ${it.value}" })
    }

    @Test
    fun `text of inactive tabs is legible on the pill list`() {
        val worst = themes().map { (name, tokens) ->
            name to contrastRatio(tokens.mutedForeground, tokens.secondary)
        }.minBy { it.second }

        assertTrue(worst.second >= 4.5f, "${worst.first}: ${worst.second}")
    }

    @Test
    fun `hover and press change every interactive fill`() {
        val tokens = ThemeScale.fallback(isDark = true)
        UVariant.entries.filter { it != UVariant.Link }.forEach { variant ->
            val style = tokens.variantStyle(UColor.Primary, variant)
            assertTrue(style.hoverBackground != style.background, "$variant hover")
            assertTrue(style.pressedBackground != style.hoverBackground, "$variant pressed")
        }
    }

    @Test
    fun `brightness and compositing follow css`() {
        val grey = dev.knshell.compose.Color(100, 200, 250)

        assertEquals(dev.knshell.compose.Color(112, 224, 255), grey.brightness(1.12f))
        assertEquals(
            dev.knshell.compose.Color(50, 100, 125),
            grey.withAlpha(0.5f).over(dev.knshell.compose.Color(0, 0, 0)),
        )
    }
}
