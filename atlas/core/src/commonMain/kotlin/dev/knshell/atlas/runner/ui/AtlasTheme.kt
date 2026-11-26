// ---------------------------------------------------------------------------
// AtlasTheme — the dark green palette and framed-control styling used by the
// Atlas runner chrome (sidebar, matrix, args panel). Story previews are not
// styled with this theme.
// ---------------------------------------------------------------------------

package dev.knshell.atlas.runner.ui

import dev.knshell.compose.Color
import dev.knshell.compose.Modifier
import dev.knshell.compose.background
import dev.knshell.compose.hoverBackground
import dev.knshell.compose.padding
import dev.knshell.compose.rawStyle
import dev.knshell.compose.slider

object AtlasTheme {
    val bg: Color = Color.hex(0x0D0F10)
    val panel: Color = Color.hex(0x121516)
    val panel2: Color = Color.hex(0x181C1D)
    val line: Color = Color.hex(0x242A2B)
    val line2: Color = Color.hex(0x333B3C)
    val fg: Color = Color.hex(0xC8D2CE)
    val dim: Color = Color.hex(0x6D7A76)
    val input: Color = Color.hex(0x0B0E0F)
    val stage: Color = Color.hex(0x090B0C)
    val accent: Color = Color.hex(0x7FD6A2)
    val accent2: Color = Color.hex(0x233029)

    /** Corner radius in CSS pixels for every framed control. */
    const val RADIUS = 2

    const val FONT = "JetBrainsMono Nerd Font"
    const val FONT_SIZE = 12

    private val labelCss: String
        get() = "color: ${fg.toCss()}; font-family: \"$FONT\"; font-size: ${FONT_SIZE}px;"

    /** 1px solid border in [line2] with [RADIUS] corners. Does not set a background. */
    fun Modifier.framed(): Modifier =
        rawStyle("", "border: 1px solid ${line2.toCss()}; border-radius: ${RADIUS}px;")

    /** Monospace text colour and size for the widget and its descendants. */
    fun Modifier.themedText(): Modifier =
        rawStyle("", "color: ${fg.toCss()}; font-family: \"$FONT\"; font-size: ${FONT_SIZE}px;")

    /** Text input: [input] background, [fg] text, framed, small padding. */
    fun Modifier.themedEntry(): Modifier =
        framed()
            .background(input, radius = RADIUS, transitionMs = 0)
            .padding(horizontal = 6, vertical = 3)
            .themedText()
            .rawStyle(" > text", "color: ${fg.toCss()}; caret-color: ${accent.toCss()}; background-color: transparent;")
            .rawStyle(" > text > selection", "background-color: ${accent2.toCss()}; color: ${accent.toCss()};")

    /** Switch: [input] track, [accent2] when checked, [accent] knob when checked, always visible. */
    fun Modifier.themedSwitch(): Modifier =
        framed()
            .background(input, radius = RADIUS, transitionMs = 0)
            .rawStyle("", "min-width: 36px; min-height: 18px;")
            .rawStyle(":checked", "background-color: ${accent2.toCss()};")
            .rawStyle(" > slider", "background-color: ${dim.toCss()}; min-width: 14px; min-height: 14px; border-radius: ${RADIUS}px;")
            .rawStyle(":checked > slider", "background-color: ${accent.toCss()};")

    /** Dropdown: framed button, framed panel2 popover with hovered rows highlighted in [accent2]. */
    fun Modifier.themedDropDown(): Modifier =
        themedText()
            .rawStyle(
                " > button",
                "border: 1px solid ${line2.toCss()}; border-radius: ${RADIUS}px; " +
                    "background-color: ${input.toCss()}; color: ${fg.toCss()}; " +
                    "padding: 3px 8px; min-height: 0;",
            )
            .rawStyle(
                " popover > contents",
                "background-color: ${panel2.toCss()}; border: 1px solid ${line2.toCss()}; " +
                    "border-radius: ${RADIUS}px; color: ${fg.toCss()};",
            )
            // Popup rows and button text are theme-owned labels: the app stylesheet sizes bare
            // `label` nodes, so the font has to be set on them directly.
            .rawStyle(" label", labelCss)
            .rawStyle(" popover row", "padding: 4px 8px;")
            .rawStyle(" popover row:hover", "background-color: ${accent2.toCss()};")

    /** Slider: thin trough in [line2], [accent] highlight, [fg] knob. */
    fun Modifier.themedSlider(): Modifier =
        slider(
            troughColor = line2,
            troughHeight = 4,
            highlightColor = accent,
            knobColor = fg,
            knobSize = 12,
        )

    /** Button: framed panel2 fill, padding, [accent2] on hover. */
    fun Modifier.themedButton(): Modifier =
        framed()
            .background(panel2, radius = RADIUS, transitionMs = 0)
            .hoverBackground(accent2)
            .padding(horizontal = 10, vertical = 4)
            .themedText()
            .rawStyle(" label", labelCss)
}
