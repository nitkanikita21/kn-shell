package dev.knshell.uikit.theme

import dev.knshell.compose.Color
import kotlin.math.pow

/** The WCAG 2 contrast ratio of two opaque colours, from 1 (equal) to 21 (black on white). */
fun contrastRatio(first: Color, second: Color): Float {
    val a = relativeLuminance(first)
    val b = relativeLuminance(second)
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

private fun relativeLuminance(color: Color): Float {
    fun linear(channel: Int): Float {
        val value = channel / 255f
        return if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
    }
    return 0.2126f * linear(color.r) + 0.7152f * linear(color.g) + 0.0722f * linear(color.b)
}
