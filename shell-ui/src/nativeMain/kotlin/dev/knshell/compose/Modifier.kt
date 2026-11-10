@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kn.gtk.GtkCssProvider
import kn.gtk.GtkWidget
import kn.gtk.gtk_css_provider_load_from_string
import kn.gtk.gtk_css_provider_new
import kn.gtk.gtk_style_context_add_provider
import kn.gtk.gtk_widget_add_css_class
import kn.gtk.gtk_widget_get_style_context
import kn.gtk.gtk_widget_set_halign
import kn.gtk.gtk_widget_set_hexpand
import kn.gtk.gtk_widget_set_margin_bottom
import kn.gtk.gtk_widget_set_margin_end
import kn.gtk.gtk_widget_set_margin_start
import kn.gtk.gtk_widget_set_margin_top
import kn.gtk.gtk_widget_set_opacity
import kn.gtk.gtk_widget_set_overflow
import kn.gtk.gtk_widget_set_size_request
import kn.gtk.gtk_widget_set_valign
import kn.gtk.gtk_widget_set_vexpand
import kn.gtk.GTK_ALIGN_CENTER
import kn.gtk.GtkOverflow
import kn.gtk.GTK_ALIGN_FILL

/** A simple ARGB color with a CSS representation. */
data class Color(val r: Int, val g: Int, val b: Int, val alpha: Float = 1f) {
    fun toCss(): String {
        val red = r.coerceIn(0, 255)
        val green = g.coerceIn(0, 255)
        val blue = b.coerceIn(0, 255)
        return "rgba($red, $green, $blue, ${alpha.coerceIn(0f, 1f)})"
    }

    companion object {
        val White = Color(255, 255, 255)
        val Transparent = Color(0, 0, 0, 0f)

        /** `Color.hex(0x1E1E2E, alpha = 0.9f)` */
        fun hex(value: Long, alpha: Float = 1f): Color = Color(
            r = ((value shr 16) and 0xFF).toInt(),
            g = ((value shr 8) and 0xFF).toInt(),
            b = (value and 0xFF).toInt(),
            alpha = alpha,
        )
    }
}

/** Catppuccin Mocha, used by the reference waybar theme. */
object Catppuccin {
    val Base = Color.hex(0x1E1E2E)
    val Mantle = Color.hex(0x181825)
    val Crust = Color.hex(0x11111B)
    val Text = Color.hex(0xCDD6F4)
    val Subtext = Color.hex(0xA6ADC8)
    val Surface0 = Color.hex(0x161925, 0.9f)
    val Surface1 = Color.hex(0x45475A)
    val Surface3 = Color.hex(0x394161)
    val Overlay = Color.hex(0x6C7086)
    val Accent = Color.hex(0xCBA6F7)
    val Blue = Color.hex(0x89B4FA)
    val Lavender = Color.hex(0xB4BEFE)
    val Sapphire = Color.hex(0x74C7EC)
    val Sky = Color.hex(0x89DCEB)
    val Teal = Color.hex(0x94E2D5)
    val Green = Color.hex(0xA6E3A1)
    val Yellow = Color.hex(0xF9E2AF)
    val Peach = Color.hex(0xFAB387)
    val Maroon = Color.hex(0xEBA0AC)
    val Red = Color.hex(0xF38BA8)
    val Mauve = Color.hex(0xCBA6F7)
    val Pink = Color.hex(0xF5C2E7)
    val Flamingo = Color.hex(0xF2CDCD)
    val Rosewater = Color.hex(0xF5E0DC)
}

// ---------------------------------------------------------------------------
// Modifier model — deliberately shaped like Compose UI's Modifier
// ---------------------------------------------------------------------------

sealed interface ModifierElement

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
internal data class ExpandElement(val horizontal: Boolean, val vertical: Boolean) : ModifierElement
internal data class AlignElement(val horizontal: UInt?, val vertical: UInt?) : ModifierElement
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

/** Typed styling of the GtkScale sub-nodes: trough, highlight and slider (knob). */
internal data class SliderThemeElement(
    val troughColor: Color?,
    val troughHeight: Int?,
    val highlightColor: Color?,
    val knobColor: Color?,
    val knobSize: Int?,
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

/**
 * Immutable chain of widget decorations, applied when the node is updated.
 *
 * Usage mirrors Compose UI:
 * ```
 * Label("hi", modifier = Modifier.padding(horizontal = 12, vertical = 6)
 *     .background(Color.hex(0x161925), radius = 16)
 *     .textColor(Catppuccin.Text))
 * ```
 */
open class Modifier internal constructor(internal val elements: List<ModifierElement> = emptyList()) {

    fun then(other: Modifier): Modifier = when {
        other.elements.isEmpty() -> this
        elements.isEmpty() -> other
        else -> Modifier(elements + other.elements)
    }

    override fun equals(other: Any?): Boolean = other is Modifier && elements == other.elements

    override fun hashCode(): Int = elements.hashCode()

    companion object : Modifier()
}

internal fun Modifier.with(element: ModifierElement): Modifier = Modifier(elements + element)

fun Modifier.padding(horizontal: Int = 0, vertical: Int = 0): Modifier =
    with(PaddingElement(horizontal, vertical))

fun Modifier.padding(all: Int): Modifier = with(PaddingElement(all, all))

fun Modifier.margin(horizontal: Int = 0, vertical: Int = 0): Modifier =
    with(MarginElement(horizontal, vertical))

fun Modifier.background(
    color: Color,
    radius: Int = 0,
    shadow: Boolean = false,
    borderWidth: Int = 0,
    borderColor: Color? = null,
    transitionMs: Int = 0,
): Modifier = with(BackgroundElement(color, radius, shadow, borderWidth, borderColor, transitionMs))

fun Modifier.hoverBackground(color: Color): Modifier = with(HoverBackgroundElement(color))

fun Modifier.textColor(color: Color, transitionMs: Int = 0): Modifier = with(TextColorElement(color, transitionMs))

fun Modifier.fontSize(px: Int): Modifier = with(FontSizeElement(px))

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

fun Modifier.transition(ms: Int): Modifier = with(TransitionElement(ms))

/**
 * Linear gradient background (native GTK CSS, no frame loop).
 *
 * ```kotlin
 * Box(Modifier.size(120, 32).gradient(Grayscale.Background, Grayscale.Accent, angleDeg = 90f))
 * ```
 */
fun Modifier.gradient(vararg stops: Color, angleDeg: Float = 90f): Modifier =
    with(LinearGradientElement(stops.toList(), angleDeg))

/**
 * Linear gradient with explicit stop positions: `color to position` where position is 0..1.
 * Recompute the stops from an animated phase to get a moving highlight band.
 */
fun Modifier.gradientStops(
    vararg stops: Pair<Color, Float>,
    angleDeg: Float = 90f,
): Modifier = with(GradientStopsElement(stops.toList(), angleDeg))

/** Radial gradient background (native GTK CSS). */
fun Modifier.radialGradient(vararg stops: Color): Modifier =
    with(RadialGradientElement(stops.toList()))

/**
 * Coloured glow around the widget (`box-shadow: 0 0 blur spread color`).
 * Animate its colour with [transition] for a pulsing effect.
 */
fun Modifier.glow(color: Color, blur: Int = 12, spread: Int = 0): Modifier =
    with(GlowElement(color, blur, spread))

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

fun Modifier.size(width: Int? = null, height: Int? = null): Modifier = with(SizeElement(width, height))

fun Modifier.width(width: Int): Modifier = with(SizeElement(width, null))

fun Modifier.height(height: Int): Modifier = with(SizeElement(null, height))



fun Modifier.opacity(value: Float): Modifier = with(OpacityElement(value))

fun Modifier.rawStyle(selector: String, declarations: String): Modifier = with(RawStyleElement(selector, declarations))

fun Modifier.pressedBackground(color: Color): Modifier = with(StateBackgroundElement(":active", color))

fun Modifier.checkedBackground(color: Color): Modifier = with(StateBackgroundElement(":checked", color))

fun Modifier.disabledBackground(color: Color): Modifier = with(StateBackgroundElement(":disabled", color))

fun Modifier.hoverTextColor(color: Color): Modifier = with(StateTextColorElement(":hover", color))

fun Modifier.checkedTextColor(color: Color): Modifier = with(StateTextColorElement(":checked", color))

/** Keyboard focus outline. GTK draws it for `:focus-visible` only (keyboard, not mouse). */
fun Modifier.focusRing(color: Color, width: Int = 2, offset: Int = 0): Modifier =
    with(FocusRingElement(color, width, offset))

/** Minimum width in CSS pixels (the plain reset removes the theme minimums). */
fun Modifier.minWidth(px: Int): Modifier = with(MinSizeElement(px, null))

/** Minimum height in CSS pixels. */
fun Modifier.minHeight(px: Int): Modifier = with(MinSizeElement(null, px))

/** Both minimums. */
fun Modifier.minSize(width: Int, height: Int): Modifier = with(MinSizeElement(width, height))

/** Corner radius in CSS pixels, independent of any background. */
fun Modifier.radius(px: Int): Modifier = with(RadiusElement("border-radius: ${px}px;"))

/**
 * Elliptical corners: [horizontal] radius on the x axis, [vertical] on the y axis
 * (`border-radius: h v`). A pill of height 30 is `radius(horizontal = 999, vertical = 15)`;
 * a perfect circle is `radius(size / 2)`.
 */
fun Modifier.radius(horizontal: Int, vertical: Int): Modifier =
    with(RadiusElement("border-radius: ${horizontal}px ${vertical}px;"))

/** Per-corner radii, clockwise: top-left, top-right, bottom-right, bottom-left. */
fun Modifier.radius(topLeft: Int, topRight: Int, bottomRight: Int, bottomLeft: Int): Modifier =
    with(
        RadiusElement(
            "border-top-left-radius: ${topLeft}px; " +
                "border-top-right-radius: ${topRight}px; " +
                "border-bottom-right-radius: ${bottomRight}px; " +
                "border-bottom-left-radius: ${bottomLeft}px;",
        ),
    )

/** Per-corner radii with independent horizontal/vertical radii (elliptical corners). */
fun Modifier.radius(topLeft: Corner, topRight: Corner, bottomRight: Corner, bottomLeft: Corner): Modifier =
    with(
        RadiusElement(
            "border-top-left-radius: ${topLeft.horizontal}px ${topLeft.vertical}px; " +
                "border-top-right-radius: ${topRight.horizontal}px ${topRight.vertical}px; " +
                "border-bottom-right-radius: ${bottomRight.horizontal}px ${bottomRight.vertical}px; " +
                "border-bottom-left-radius: ${bottomLeft.horizontal}px ${bottomLeft.vertical}px;",
        ),
    )

/**
 * Styles the GtkScale sub-nodes with typed parameters instead of rawStyle strings.
 *
 * ```kotlin
 * Slider(value, modifier = Modifier.width(80).height(16).slider(
 *     troughColor = Catppuccin.Surface1, troughHeight = 6,
 *     highlightColor = Catppuccin.Green,
 *     knobColor = Catppuccin.Text, knobSize = 12,
 * ))
 * ```
 */
fun Modifier.slider(
    troughColor: Color? = null,
    troughHeight: Int? = null,
    highlightColor: Color? = null,
    knobColor: Color? = null,
    knobSize: Int? = null,
): Modifier = with(SliderThemeElement(troughColor, troughHeight, highlightColor, knobColor, knobSize))

/** Text selection colours inside a label: `Modifier.selection(Catppuccin.Base, Catppuccin.Mauve)`. */
fun Modifier.selection(color: Color, background: Color): Modifier = with(SelectionElement(background, color))

/** Clips children to the widget's allocation (GTK `GTK_OVERFLOW_HIDDEN`). */
fun Modifier.clipOverflow(): Modifier = with(ClipOverflowElement)

// ---------------------------------------------------------------------------
// GTK application
// ---------------------------------------------------------------------------

private object InlineStyleId {
    var next = 0
}

/**
 * Applies CSS declarations to a single widget through a private style provider and a
 * generated class. This is what makes modifier-based styling possible: each widget gets
 * exactly the CSS its modifiers describe, without touching a global stylesheet.
 */
internal class InlineStyle(private val widget: CPointer<GtkWidget>) {
    /** Opt-in size tween used by [Modifier.behavior]; see [SizeAnimator]. */
    internal val sizeAnimator = SizeAnimator()

    /** Click/hover/cursor wiring (see Interaction.kt). */
    internal val interaction = InteractionState(widget)

    private var provider: CPointer<GtkCssProvider>? = null
    private var className: String? = null
    private var lastCss: String? = null

    fun setRules(rules: List<Pair<String, String>>) {
        if (rules.isEmpty()) return

        val base = rules.filter { it.first.isEmpty() }.joinToString(separator = " ") { it.second }
        val scoped = rules.filter { it.first.isNotEmpty() }

        val cls = ensureClass()
        val css = buildString {
            if (base.isNotBlank()) append(".$cls { $base }\n")
            scoped.forEach { (selector, declarations) -> append(".$cls$selector { $declarations }\n") }
        }
        if (css == lastCss) return
        lastCss = css

        val cssProvider = ensureProvider() ?: return
        gtk_css_provider_load_from_string(cssProvider, css)
    }

    private fun ensureClass(): String {
        className?.let { return it }
        val cls = "knm-${InlineStyleId.next++}"
        gtk_widget_add_css_class(widget, cls)
        className = cls
        return cls
    }

    private fun ensureProvider(): CPointer<GtkCssProvider>? {
        provider?.let { return it }
        val cssProvider = gtk_css_provider_new() ?: return null
        val context = gtk_widget_get_style_context(widget)
        if (context != null) {
            // 700 = GTK_STYLE_PROVIDER_PRIORITY_APPLICATION + 100 (above the app stylesheet)
            gtk_style_context_add_provider(context, cssProvider.reinterpret(), 700u)
        }
        provider = cssProvider
        return cssProvider
    }
}

/** Applies all modifiers of [modifier] to [widget]. */
internal fun applyModifiers(widget: CPointer<GtkWidget>, modifier: Modifier, inlineStyle: InlineStyle) {
    var marginHorizontal = 0
    var marginVertical = 0
    var width: Int? = null
    var height: Int? = null
    var opacity: Float? = null
    var hexpand: Boolean? = null
    var vexpand: Boolean? = null
    var halign: UInt? = null
    var behavior: BehaviorElement? = null
    var clickHandler: (() -> Unit)? = null
    var hoverEnter: (() -> Unit)? = null
    var hoverExit: (() -> Unit)? = null
    var cursorName: String? = null
    var valign: UInt? = null
    val rules = mutableListOf<Pair<String, String>>()

    for (element in modifier.elements) {
        when (element) {
            is PaddingElement ->
                rules += "" to "padding: ${element.vertical}px ${element.horizontal}px;"

            is MarginElement -> {
                marginHorizontal = element.horizontal
                marginVertical = element.vertical
            }

            is BackgroundElement -> {
                val declarations = buildString {
                    append("background-color: ${element.color.toCss()};")
                    if (element.radius > 0) append("border-radius: ${element.radius}px;")
                    if (element.shadow) append("box-shadow: 2px 2px 5px 2px rgba(0, 0, 0, 0.12);")
                    if (element.borderWidth > 0 && element.borderColor != null) {
                        append("border: ${element.borderWidth}px solid ${element.borderColor.toCss()};")
                    }
                    if (element.transitionMs > 0) {
                        append(
                            "transition: background-color ${element.transitionMs}ms ease, color ${element.transitionMs}ms ease;",
                        )
                    }
                }
                rules += "" to declarations
            }

            is HoverBackgroundElement -> rules += ":hover" to "background-color: ${element.color.toCss()};"

            is TextColorElement -> {
                val d = buildString {
                    append("color: ${element.color.toCss()};")
                    if (element.transitionMs > 0) append(" transition: color ${element.transitionMs}ms ease;")
                }
                rules += "" to d
            }

            is FontSizeElement -> rules += "" to "font-size: ${element.px}px;"

            is FontFamilyElement -> rules += "" to "font-family: ${element.family};"

            BoldElement -> rules += "" to "font-weight: 700;"

            is SizeElement -> {
                width = element.width
                height = element.height
            }

            is ExpandElement -> {
                hexpand = element.horizontal
                vexpand = element.vertical
            }

            is AlignElement -> {
                halign = element.horizontal
                valign = element.vertical
            }

            is OpacityElement -> opacity = element.value

            is RadiusElement -> rules += "" to element.declarations

            is StateBackgroundElement ->
                rules += element.selector to "background-color: ${element.color.toCss()}; background-image: none;"

            is StateTextColorElement -> rules += element.selector to "color: ${element.color.toCss()};"

            is FocusRingElement -> rules += ":focus-visible" to buildString {
                append("outline-style: solid;")
                append(" outline-width: ${element.width}px;")
                append(" outline-color: ${element.color.toCss()};")
                append(" outline-offset: ${element.offset}px;")
            }

            is MinSizeElement -> {
                val declarations = buildString {
                    element.width?.let { append("min-width: ${it}px; ") }
                    element.height?.let { append("min-height: ${it}px;") }
                }
                if (declarations.isNotBlank()) rules += "" to declarations
            }

            is SliderThemeElement -> {
                element.troughColor?.let { color ->
                    val height = element.troughHeight
                    val radius = if (height != null) height / 2 else 3
                    rules += " trough" to buildString {
                        append("background-color: ${color.toCss()}; ")
                        append("background-image: none; border-radius: ${radius}px;")
                        if (height != null) append(" min-height: ${height}px;")
                    }
                } ?: element.troughHeight?.let { height ->
                    rules += " trough" to "min-height: ${height}px; border-radius: ${height / 2}px;"
                }

                element.highlightColor?.let { color ->
                    val radius = element.troughHeight?.let { it / 2 } ?: 3
                    rules += " highlight" to
                        "background-color: ${color.toCss()}; background-image: none; border-radius: ${radius}px;"
                }

                val knob = element.knobSize
                if (element.knobColor != null || knob != null) {
                    rules += " slider" to buildString {
                        element.knobColor?.let { append("background-color: ${it.toCss()}; background-image: none; ") }
                        if (knob != null) {
                            append("min-width: ${knob}px; min-height: ${knob}px; ")
                            append("border-radius: ${knob / 2}px; ")
                        }
                        append("border: none; box-shadow: none;")
                    }
                }
            }

            ClipOverflowElement -> gtk_widget_set_overflow(widget, GtkOverflow.GTK_OVERFLOW_HIDDEN)

            ItalicElement -> rules += "" to "font-style: italic;"

            UnderlineElement -> rules += "" to "text-decoration-line: underline;"

            StrikeElement -> rules += "" to "text-decoration-line: line-through;"

            is FontWeightElement -> rules += "" to "font-weight: ${element.weight};"

            is LetterSpacingElement -> rules += "" to "letter-spacing: ${element.px}px;"

            is GradientStopsElement -> rules += "" to
                "background-image: linear-gradient(${element.angleDeg}deg, " +
                    element.stops.joinToString(", ") { (color, position) ->
                        "${color.toCss()} ${position * 100f}%"
                    } + ");"

            is LinearGradientElement -> rules += "" to
                "background-image: linear-gradient(${element.angleDeg}deg, ${element.stops.joinToString(", ") { it.toCss() }});"

            is RadialGradientElement -> rules += "" to
                "background-image: radial-gradient(circle, ${element.stops.joinToString(", ") { it.toCss() }});"

            is GlowElement -> rules += "" to
                "box-shadow: 0 0 ${element.blur}px ${element.spread}px ${element.color.toCss()};"

            is TransitionElement -> rules += "" to "transition: color ${element.ms}ms ease, background-color ${element.ms}ms ease, border-color ${element.ms}ms ease, box-shadow ${element.ms}ms ease, opacity ${element.ms}ms ease;"

            is SelectionElement ->
                rules += " selection" to "background-color: ${element.background.toCss()}; color: ${element.color.toCss()};"

            is BehaviorElement -> behavior = element

            is ClickElement -> clickHandler = element.handler

            is HoverElement -> {
                hoverEnter = element.onEnter
                hoverExit = element.onExit
            }

            is CursorElement -> cursorName = element.name

            is RawStyleElement -> rules += element.selector to element.declarations
        }
    }

    gtk_widget_set_margin_start(widget, marginHorizontal)
    gtk_widget_set_margin_end(widget, marginHorizontal)
    gtk_widget_set_margin_top(widget, marginVertical)
    gtk_widget_set_margin_bottom(widget, marginVertical)

    if (width != null || height != null) {
        val behavior = behavior
        if (behavior != null) {
            inlineStyle.sizeAnimator.animate(widget, width, height, behavior.durationMs, behavior.easing, behavior.axis)
        } else {
            inlineStyle.sizeAnimator.cancel()
            gtk_widget_set_size_request(widget, width ?: -1, height ?: -1)
        }
    }
    opacity?.let { gtk_widget_set_opacity(widget, it.toDouble()) }
    hexpand?.let { gtk_widget_set_hexpand(widget, if (it) 1 else 0) }
    vexpand?.let { gtk_widget_set_vexpand(widget, if (it) 1 else 0) }
    halign?.let { gtk_widget_set_halign(widget, it) }
    valign?.let { gtk_widget_set_valign(widget, it) }

    inlineStyle.setRules(rules)
    inlineStyle.interaction.bind(clickHandler, hoverEnter, hoverExit, cursorName)
}
