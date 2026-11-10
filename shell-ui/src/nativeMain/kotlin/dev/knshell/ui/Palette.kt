package dev.knshell.ui

import dev.knshell.compose.Color

/**
 * Grayscale theme (neutral ramp, no hue).
 *
 * ```kotlin
 * Row(Modifier.background(Grayscale.Surface, radius = 16)) {
 *     Text("workspace", color = Grayscale.Foreground)
 *     Icon("\uF017", color = Grayscale.Accent)
 * }
 * ```
 *
 * For text on [Accent], use [Background] (the accent is light).
 */
object Grayscale {
    /** `#141414` — window / bar background. */
    val Background = Color.hex(0x141414)

    /** `#262626` — pills, cards, subtle elevation. */
    val Surface = Color.hex(0x262626)

    /** `#cfcfcf` — accent: active state, highlights (light, so pair with Background text). */
    val Accent = Color.hex(0xCFCFCF)

    /** `#eeeeee` — primary text and icons. */
    val Foreground = Color.hex(0xEEEEEE)

    // Short aliases for code written against Catppuccin-ish names.
    val Base get() = Background
    val Text get() = Foreground
    val Surface0 get() = Surface
}

/** Back-compat alias for the previous Olive palette name. */
typealias Olive = Grayscale
