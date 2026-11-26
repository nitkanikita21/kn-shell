// ---------------------------------------------------------------------------
// AtlasTheme — the dark green palette and framed-control styling used by the
// Atlas runner chrome (sidebar, matrix, args panel). Story previews are not
// styled with this theme. Every rule is a typed framework modifier; there is
// no raw CSS here.
// ---------------------------------------------------------------------------

package dev.knshell.atlas.runner.ui

import dev.knshell.compose.BorderSide
import dev.knshell.compose.Color
import dev.knshell.compose.Modifier
import dev.knshell.compose.background
import dev.knshell.compose.border
import dev.knshell.compose.checkedBackground
import dev.knshell.compose.dropDownButton
import dev.knshell.compose.dropDownPopover
import dev.knshell.compose.entryStyle
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.hoverBackground
import dev.knshell.compose.labelStyle
import dev.knshell.compose.minSize
import dev.knshell.compose.padding
import dev.knshell.compose.radius
import dev.knshell.compose.slider
import dev.knshell.compose.switchKnob
import dev.knshell.compose.textColor

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
    val changed: Color = Color.hex(0xE5C07B)
    val failed: Color = Color.hex(0xE06C75)

    /** Corner radius in CSS pixels for every framed control. */
    const val RADIUS = 2

    const val FONT = "JetBrainsMono Nerd Font"
    const val FONT_SIZE = 12

    /** The chrome's hairline: a 1px [line] border on the given [sides] (all four by default). */
    fun Modifier.hairline(sides: Set<BorderSide> = BorderSide.ALL): Modifier = border(line, sides = sides)

    /** 1px border in [line2] with [RADIUS] corners. Does not set a background. */
    fun Modifier.framed(): Modifier = border(line2).radius(RADIUS)

    /** Monospace text colour and size for the widget and its text. */
    fun Modifier.themedText(): Modifier = textColor(fg, transitionMs = 0).fontFamily(FONT).fontSize(FONT_SIZE)

    /** Text input: [input] background, [fg] text, framed, small padding. */
    fun Modifier.themedEntry(): Modifier =
        framed()
            .background(input, radius = RADIUS, transitionMs = 0)
            .padding(horizontal = 6, vertical = 3)
            .themedText()
            .entryStyle(textColor = fg, caretColor = accent, selectionBackground = accent2, selectionColor = accent)

    /** Switch: [input] track, [accent2] when checked, [accent] knob when checked, always visible. */
    fun Modifier.themedSwitch(): Modifier =
        framed()
            .background(input, radius = RADIUS, transitionMs = 0)
            .minSize(width = 36, height = 18)
            .checkedBackground(accent2)
            .switchKnob(color = dim, checkedColor = accent, size = 14, radius = RADIUS)

    /** Dropdown: framed button, framed panel2 popover with hovered rows highlighted in [accent2]. */
    fun Modifier.themedDropDown(): Modifier =
        themedText()
            // Popup rows and button text are theme-owned labels: the app stylesheet sizes bare
            // `label` nodes, so the font has to be set on them directly.
            .labelStyle(fg, FONT, FONT_SIZE)
            .dropDownButton(
                background = input,
                color = fg,
                border = line2,
                radius = RADIUS,
                paddingHorizontal = 8,
                paddingVertical = 3,
            )
            .dropDownPopover(
                background = panel2,
                color = fg,
                border = line2,
                rowHover = accent2,
                radius = RADIUS,
                rowPaddingHorizontal = 8,
                rowPaddingVertical = 4,
            )

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
            .labelStyle(fg, FONT, FONT_SIZE)
}
