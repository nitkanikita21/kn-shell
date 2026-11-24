// ---------------------------------------------------------------------------
// Text modifiers — colour, font size/family/weight, italic/underline/strike,
// letter spacing and the CSS transition that animates colour changes.
// ---------------------------------------------------------------------------

package dev.knshell.compose

/**
 * Text colour (`color`). [transitionMs] > 0 animates the change over that many milliseconds.
 *
 * ```kotlin
 * Label("72%", modifier = Modifier.textColor(Catppuccin.Green))
 * ```
 */
fun Modifier.textColor(color: Color, transitionMs: Int = 200): Modifier = with(TextColorElement(color, transitionMs))

/** Font size in CSS pixels (`font-size`). */
fun Modifier.fontSize(px: Int): Modifier = with(FontSizeElement(px))

/** Font family name, e.g. `"JetBrainsMono Nerd Font"` — must be installed on the system. */
fun Modifier.fontFamily(family: String): Modifier = with(FontFamilyElement(family))

/** Italic (`font-style: italic`). */
fun Modifier.italic(): Modifier = with(ItalicElement)

/** Underline (`text-decoration-line: underline`). */
fun Modifier.underline(): Modifier = with(UnderlineElement)

/** Strike-through (`text-decoration-line: line-through`). */
fun Modifier.strikethrough(): Modifier = with(StrikeElement)

/** Numeric font weight: `400` normal, `700` bold, `900` heavy. */
fun Modifier.fontWeight(weight: Int): Modifier = with(FontWeightElement(weight))

/** Letter spacing in CSS pixels (fractional allowed). */
fun Modifier.letterSpacing(px: Float): Modifier = with(LetterSpacingElement(px))

/**
 * CSS transition duration in milliseconds for colour and opacity changes (`transition: color …,
 * background-color …, border-color …, box-shadow …, opacity …`). `0` disables the transition, and a
 * background that animates its own colour (`background(transitionMs = …)`) is usually the cheaper
 * choice for a single property.
 */
fun Modifier.transition(ms: Int): Modifier = with(TransitionElement(ms))

/** Bold text: `font-weight: 700` (shorthand for `fontWeight(700)`). */
fun Modifier.bold(): Modifier = with(BoldElement)

/** Thin, 100. */
fun Modifier.thin(): Modifier = fontWeight(100)

/** ExtraLight, 200. */
fun Modifier.extraLight(): Modifier = fontWeight(200)

/** Light, 300. */
fun Modifier.light(): Modifier = fontWeight(300)

/** Medium, 500. */
fun Modifier.medium(): Modifier = fontWeight(500)

/** SemiBold, 600. */
fun Modifier.semiBold(): Modifier = fontWeight(600)

/** ExtraBold, 800 (the font must ship that weight; JetBrainsMono Nerd Font does). */
fun Modifier.extraBold(): Modifier = fontWeight(800)

/** Black/Heavy, 900. */
fun Modifier.black(): Modifier = fontWeight(900)
