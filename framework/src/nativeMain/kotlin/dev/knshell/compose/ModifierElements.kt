// ---------------------------------------------------------------------------
// `ModifierElement` records — one immutable data class per styling concern.
// They carry data only: the DSL constructors are in ModifierLayout.kt /
// ModifierText.kt / ModifierPaint.kt and the CSS they emit is in ModifierCss.kt.
// ---------------------------------------------------------------------------

package dev.knshell.compose

internal data class PaddingElement(val horizontal: Int, val vertical: Int) : ModifierElement
internal data class MarginElement(val horizontal: Int, val vertical: Int) : ModifierElement
internal data class BackgroundElement(
    val color: Color,
    val radius: Int,
    val shadow: Boolean,
    val borderWidth: Int,
    val borderColor: Color?,
    val transitionMs: Int,
) : ModifierElement

internal data class HoverBackgroundElement(val color: Color) : ModifierElement
internal data class TextColorElement(val color: Color, val transitionMs: Int) : ModifierElement
internal data class FontSizeElement(val px: Int) : ModifierElement
internal data class FontFamilyElement(val family: String) : ModifierElement
internal data object BoldElement : ModifierElement
internal data class SizeElement(val width: Int?, val height: Int?) : ModifierElement

/**
 * `size()` semantics plus "do not let the parent stretch me": the request is a floor *and* a
 * ceiling, because the axes it names get a non-`FILL` alignment (plan L13).
 */
internal data class RequiredSizeElement(val width: Int?, val height: Int?) : ModifierElement

/**
 * Keeps a child's shape: its MAIN axis (the one its container packs along) becomes
 * `cross * ratio` instead of the content's own size, so `aspectRatio(1f)` in a `Row` fills the
 * row's height and lets the width follow — a chip that is always square.
 */
internal data class AspectRatioElement(val ratio: Float) : ModifierElement
internal data class ExpandElement(val horizontal: Boolean, val vertical: Boolean) : ModifierElement
internal data class AlignElement(val horizontal: UInt?, val vertical: UInt?) : ModifierElement

/**
 * Android-style main-axis weight of a child inside a `Row`/`Column` (plan K3).
 *
 * It is a **per-child** property of the *container's* layout manager, so unlike every other
 * element it is not written on the widget by [applyModifiers] — `GtkBoxNode.attachChildAt`
 * pushes it to the manager (the parent widget is not reachable from here, and GTK's own
 * `child` properties do not exist for a managed box).
 *
 * `weight <= 0f` means "no weight" and must be pushed as well: a [Modifier] chain is rebuilt
 * from scratch every recomposition, so an absent weight has to clear the previous one (L9).
 */
internal data class WeightElement(val value: Double) : ModifierElement
internal data class OpacityElement(val value: Float) : ModifierElement

/** Escape hatch for CSS that cannot be expressed with the typed modifiers. */
internal data class RawStyleElement(val selector: String, val declarations: String) : ModifierElement

/** Background colour for a widget state (":hover", ":active", ":checked", ":disabled", ...). */
internal data class StateBackgroundElement(val selector: String, val color: Color) : ModifierElement

/** Text colour for a widget state. */
internal data class StateTextColorElement(val selector: String, val color: Color) : ModifierElement

/** Keyboard focus outline (":focus-visible"). */
internal data class FocusRingElement(val color: Color, val width: Int, val offset: Int) : ModifierElement

/** CSS minimum size, applied after the reset removed the theme's defaults. */
internal data class MinSizeElement(val width: Int?, val height: Int?) : ModifierElement

/**
 * Upper bound for the size this widget may be given, on either axis.
 *
 * GTK has no `max-width`/`max-height` — `gtk_widget_set_size_request` is a minimum — so the
 * ceiling is applied in two places:
 * 1. [applyModifiers] clamps the request written for `width()`/`height()`/`size()`, so
 *    `Modifier.size(1000).maxWidth(220)` asks for 220 px;
 * 2. `GtkNode.pushChildLayout` pushes the pair to the container's `KnBoxLayout`, which caps
 *   what the child measures and what it is allocated (rust-core/src/kn_box_layout.c). That is
 *   what makes `Modifier.maxWidth(220)` on its own mean something: the manager owns a child's
 *   size, so it can take it below the natural size a GTK widget would otherwise insist on.
 *
 * The ceiling never overrides the child's own minimum — same rule as CSS, where `min-width`
 * beats `max-width`. That is not politeness: GTK widgets do not clip, so an allocation below
 * the minimum makes the child paint over its neighbours, and the height-for-width negotiation
 * answers it with `Gtk-WARNING **: Trying to measure GtkLabel … for width of 203, but it needs
 * at least 249`. A cap therefore bites when the child CAN give width up
 * (`Label(ellipsize = true)` / `wrap = true`, whose minimum is a word or an ellipsis) and is
 * ignored by a child that cannot (a plain one-line label: minimum == natural).
 *
 * The second half is an **emulation**: a widget measured outside a managed box (a plain
 * `GtkBox`, a `CenterBox` slot, an overlay) keeps the old behaviour — only an explicit request
 * is capped. For text that must ellipsize rather than clip, use `Label(maxWidthChars = N)`:
 * it lowers the label's *minimum*, so Pango draws "…" instead of cutting glyphs off.
 */
internal data class MaxSizeElement(val width: Int?, val height: Int?) : ModifierElement
/** Typed styling of the GtkScale sub-nodes: trough, highlight and slider (knob). */
internal data class SliderThemeElement(
    val troughColor: Color?,
    val troughHeight: Int?,
    val highlightColor: Color?,
    val knobColor: Color?,
    val knobSize: Int?,
    val transitionMs: Int,
) : ModifierElement

/** Italic text (`font-style: italic`). */
internal data object ItalicElement : ModifierElement

/** Underlined text (`text-decoration-line: underline`). */
internal data object UnderlineElement : ModifierElement

/** Struck-through text (`text-decoration-line: line-through`). */
internal data object StrikeElement : ModifierElement

/** Numeric font weight (400 normal, 700 bold, …). */
internal data class FontWeightElement(val weight: Int) : ModifierElement

/** Letter spacing in CSS pixels. */
internal data class LetterSpacingElement(val px: Float) : ModifierElement

internal data class TransitionElement(val ms: Int) : ModifierElement

/** CSS linear-gradient background. */
internal data class LinearGradientElement(val stops: List<Color>, val angleDeg: Float) : ModifierElement

/** CSS linear-gradient with explicit stop positions (0..1) — lets the sheen move. */
internal data class GradientStopsElement(val stops: List<Pair<Color, Float>>, val angleDeg: Float) : ModifierElement

/** CSS radial-gradient background. */
internal data class RadialGradientElement(val stops: List<Color>) : ModifierElement

/** Coloured glow around the widget (box-shadow). */
internal data class GlowElement(val color: Color, val blur: Int, val spread: Int) : ModifierElement

/** Text selection colours inside a label. */
internal data class SelectionElement(val background: Color, val color: Color) : ModifierElement

/** GTK overflow: clip children to the widget allocation. */
internal data object ClipOverflowElement : ModifierElement

/** Per-corner radius, with independent horizontal/vertical radii (elliptical corners). */
data class Corner(val horizontal: Int, val vertical: Int = horizontal)

/** Standalone corner radius declarations (independent of a background). */
internal data class RadiusElement(val declarations: String) : ModifierElement

/** Name the widget is addressed by from outside (`Modifier.testTag`); changes nothing visually. */
internal data class TestTagElement(val name: String) : ModifierElement

/** Inherited styles start over here (`Modifier.resetStyles`). */
internal data object StyleResetElement : ModifierElement

/** Border on some or all sides (`Modifier.border`). */
internal data class BorderElement(val color: Color, val width: Int, val sides: Set<BorderSide>, val transitionMs: Int) :
    ModifierElement

/** Colour and font of the label nodes inside the widget (`Modifier.labelStyle`). */
internal data class LabelStyleElement(
    val color: Color,
    val fontFamily: String?,
    val fontSize: Int?,
    val transitionMs: Int,
) : ModifierElement

/** Text field of an `Entry` (`Modifier.entryStyle`). */
internal data class EntryStyleElement(
    val textColor: Color,
    val caretColor: Color,
    val selectionBackground: Color,
    val selectionColor: Color,
    val transitionMs: Int,
) : ModifierElement

/** Knob of a `Switch` (`Modifier.switchKnob`). */
internal data class SwitchKnobElement(val color: Color, val checkedColor: Color, val size: Int, val radius: Int) :
    ModifierElement

/** Closed button of a `DropDown` (`Modifier.dropDownButton`). */
internal data class DropDownButtonElement(
    val background: Color,
    val color: Color,
    val border: Color,
    val borderWidth: Int,
    val radius: Int,
    val paddingHorizontal: Int,
    val paddingVertical: Int,
) : ModifierElement

/** Open list of a `DropDown` (`Modifier.dropDownPopover`). */
internal data class DropDownPopoverElement(
    val background: Color,
    val color: Color,
    val border: Color,
    val borderWidth: Int,
    val radius: Int,
    val rowPaddingHorizontal: Int,
    val rowPaddingVertical: Int,
    val rowHover: Color,
) : ModifierElement
