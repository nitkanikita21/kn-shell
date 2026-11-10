package dev.knshell.ui

import dev.knshell.compose.Color

/**
 * Custom palette built from `#1a1714 #38403a #617851 #b3b282`.
 *
 * Warm, low-contrast olive theme: a near-black base, a grey-green surface, a muted green
 * accent and a pale khaki foreground. Swap it in wherever a `Catppuccin.*` colour is used.
 *
 * ```kotlin
 * Row(Modifier.background(Olive.Surface, radius = 16)) {
 *     Text("workspace", color = Olive.Text)
 *     Icon("\uF017", color = Olive.Accent)
 * }
 * ```
 */
object Olive {
    /** `#1a1714` — window / bar background. */
    val Background = Color.hex(0x1A1714)

    /** `#38403a` — pills, cards, subtle elevation. */
    val Surface = Color.hex(0x38403A)

    /** `#617851` — accent: active state, progress fill, highlights. */
    val Accent = Color.hex(0x617851)

    /** `#b3b282` — primary text and icons. */
    val Foreground = Color.hex(0xB3B282)

    // Short aliases, handy when porting code written against Catppuccin names.
    val Base get() = Background
    val Text get() = Foreground
    val Green get() = Accent
    val Surface0 get() = Surface
}
