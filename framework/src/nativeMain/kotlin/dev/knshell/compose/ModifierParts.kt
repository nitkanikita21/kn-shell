// ---------------------------------------------------------------------------
// Typed styling of the parts of the stock widgets: borders, the label nodes inside a widget, the
// text field of `Entry`, the knob of `Switch`, and the button and popover of `DropDown`. Each one
// replaces a `rawStyle` string with parameters; the CSS they emit is in ModifierCss.kt.
// ---------------------------------------------------------------------------

package dev.knshell.compose

/** One side of a box, for [border]. */
enum class BorderSide {
    Top,
    Right,
    Bottom,
    Left;

    companion object {
        /** All four sides: a border around the whole box. */
        val ALL: Set<BorderSide> = entries.toSet()
    }
}

/**
 * A solid border of [width] CSS pixels on [sides] (all four by default). Unlike the `borderWidth`
 * of [background] it paints no fill, so it works alone:
 * `Modifier.border(color, sides = setOf(BorderSide.Bottom))` is a hairline under a row.
 */
fun Modifier.border(
    color: Color,
    width: Int = 1,
    sides: Set<BorderSide> = BorderSide.ALL,
    transitionMs: Int = 0,
): Modifier = with(BorderElement(color, width, sides, transitionMs))

/**
 * Colour and font of the `label` nodes inside the widget. The application stylesheet sizes bare
 * labels, so these are set on the nodes themselves. [fontFamily] is a family name without quotes.
 */
fun Modifier.labelStyle(
    color: Color,
    fontFamily: String? = null,
    fontSize: Int? = null,
    transitionMs: Int = 0,
): Modifier = with(LabelStyleElement(color, fontFamily, fontSize, transitionMs))

/**
 * The text field of an `Entry`: text colour, the caret, and the selection (background and text).
 * The field itself keeps its own [background] and [border].
 */
fun Modifier.entryStyle(
    textColor: Color,
    caretColor: Color,
    selectionBackground: Color,
    selectionColor: Color,
    transitionMs: Int = 0,
): Modifier = with(EntryStyleElement(textColor, caretColor, selectionBackground, selectionColor, transitionMs))

/**
 * The knob of a `Switch`: a square of [size] CSS pixels with [radius] corners, in [color] when off
 * and [checkedColor] when on. The track is styled with [background] and [checkedBackground].
 */
fun Modifier.switchKnob(color: Color, checkedColor: Color, size: Int, radius: Int = size / 2): Modifier =
    with(SwitchKnobElement(color, checkedColor, size, radius))

/** The closed button of a `DropDown`: fill, text colour, border and padding. */
fun Modifier.dropDownButton(
    background: Color,
    color: Color,
    border: Color,
    radius: Int = 0,
    paddingHorizontal: Int = 8,
    paddingVertical: Int = 3,
    borderWidth: Int = 1,
): Modifier = with(
    DropDownButtonElement(background, color, border, borderWidth, radius, paddingHorizontal, paddingVertical),
)

/**
 * The open list of a `DropDown`: its fill, text colour and border, the padding of each row, and
 * the fill of the row under the pointer ([rowHover]).
 */
fun Modifier.dropDownPopover(
    background: Color,
    color: Color,
    border: Color,
    rowHover: Color,
    radius: Int = 0,
    borderWidth: Int = 1,
    rowPaddingHorizontal: Int = 8,
    rowPaddingVertical: Int = 4,
): Modifier = with(
    DropDownPopoverElement(
        background, color, border, borderWidth, radius, rowPaddingHorizontal, rowPaddingVertical, rowHover,
    ),
)
