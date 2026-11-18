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
import kn.gtk.GObject
import kn.gtk.GtkOverflow
import kn.gtk.GTK_ALIGN_FILL
import kn.gtk.GTK_ALIGN_START
import kn.gtk.g_object_get_data
import kn.gtk.g_object_set_data
import dev.knshell.animation.applyAnimatedSizeModifier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

        /** OKLCH (l, c, h) → Color. `l` ∈ 0..1 lightness, `c` ∈ 0..~0.4 chroma, `h` in degrees. */
        fun oklch(l: Float, c: Float, h: Float, alpha: Float = 1f): Color {
            val hRad = h * PI.toFloat() / 180f
            val a = c * cos(hRad)
            val b = c * sin(hRad)

            val l_ = l + 0.3963377774f * a + 0.2158037573f * b
            val m_ = l - 0.1055613458f * a - 0.0638541728f * b
            val s_ = l - 0.0894841775f * a - 1.2914855480f * b

            val l3 = l_ * l_ * l_
            val m3 = m_ * m_ * m_
            val s3 = s_ * s_ * s_

            val rLin = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
            val gLin = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
            val bLin = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3

            return Color(
                r = (rLin.linearToSrgb().coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255),
                g = (gLin.linearToSrgb().coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255),
                b = (bLin.linearToSrgb().coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255),
                alpha = alpha,
            )
        }
    }
}

private fun Float.linearToSrgb(): Float =
    if (this <= 0.0031308f) 12.92f * this
    else 1.055f * kotlin.math.exp(kotlin.math.ln(this.toDouble()) / 2.4).toFloat() - 0.055f

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

/** The main-axis weight [modifier] asks for, or `0f` when it has none. */
internal fun Modifier.mainAxisWeight(): Double =
    elements.filterIsInstance<WeightElement>().lastOrNull()?.value ?: 0.0

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

/**
 * The `maxWidth()`/`maxHeight()` ceilings [modifier] asks for, as `width to height`, with `-1`
 * for an axis it does not cap. A chain is rebuilt from scratch every recomposition, so an absent
 * cap has to be pushed as `-1` — it clears the previous one (L9).
 */
/**
 * The `Modifier.aspectRatio()` a chain asks for, `0f` when it asks for none. Like `weight`, a
 * ratio is a property of the CONTAINER's layout manager (`KnBoxLayout`), not of the widget, so
 * `GtkApplier` pushes it to the parent and nothing is written here.
 */
internal fun Modifier.aspectRatioValue(): Float =
    elements.filterIsInstance<AspectRatioElement>().lastOrNull()?.ratio ?: 0f

internal fun Modifier.maxSizeCaps(): Pair<Int, Int> {
    var width = -1
    var height = -1
    for (element in elements) {
        if (element is MaxSizeElement) {
            val w = element.width
            val h = element.height
            if (w != null) width = w
            if (h != null) height = h
        }
    }
    return width to height
}

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
fun Modifier.size(size: Int? = null): Modifier = with(SizeElement(size, size))

fun Modifier.width(width: Int): Modifier = with(SizeElement(width, null))

fun Modifier.height(height: Int): Modifier = with(SizeElement(null, height))

/**
 * An **exact** size: compose's `requiredSize`. `size()` is a GTK size request, which is only a
 * minimum — a `GtkBox` stretching its children on the cross axis happily gives a `size(44)` chip
 * 44×80. This pins the axes it names (request + `Align.Start`, so the parent cannot grow it) and
 * leaves the rest to the chain: a later `align()`/`fillWidth()` still wins.
 */
fun Modifier.requiredSize(width: Int? = null, height: Int? = null): Modifier =
    with(RequiredSizeElement(width, height))

fun Modifier.requiredSize(size: Int? = null): Modifier = with(RequiredSizeElement(size, size))

/**
 * Locks the child's shape: `ratio` = main / cross, where the main axis is the one the container
 * lays children out on. In a `Row`, `aspectRatio(1f)` means "as tall as the row, and exactly that
 * wide" — the mirror image of `requiredSize`, which pins both axes to numbers.
 *
 * The cross axis keeps deciding (a `FILL` child takes the container's whole cross size, an
 * aligned one its own), and the main axis follows from it. A `weight` or `hExpand()` on the same
 * child is ignored, because both would claim the pixels the ratio already owns.
 *
 * Like `weight` it lives in `KnBoxLayout`, so it works inside `Row`/`Column`/`Box`; in a `Stack`,
 * `Overlay` or `CenterBox` region every child gets the same cell and there is no main axis to
 * resize along.
 */
fun Modifier.aspectRatio(ratio: Float): Modifier = with(AspectRatioElement(ratio))

/** Exact width, height left to the parent (see [requiredSize]). */
fun Modifier.requiredWidth(width: Int): Modifier = with(RequiredSizeElement(width, null))

/** Exact height, width left to the parent (see [requiredSize]). */
fun Modifier.requiredHeight(height: Int): Modifier = with(RequiredSizeElement(null, height))



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

/** Caps an explicit `width()`/`size()` request. Text: use `Label(maxWidthChars)`. */
fun Modifier.maxWidth(px: Int): Modifier = with(MaxSizeElement(px, null))

/** Caps an explicit `height()`/`size()` request. */
fun Modifier.maxHeight(px: Int): Modifier = with(MaxSizeElement(null, px))

/** Both maximums. */
fun Modifier.maxSize(width: Int, height: Int): Modifier = with(MaxSizeElement(width, height))

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
    var maxWidth: Int? = null
    var maxHeight: Int? = null
    var requiredWidth = false
    var requiredHeight = false
    var opacity: Float? = null
    var hexpand: Boolean? = null
    var vexpand: Boolean? = null
    var halign: UInt? = null
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

            is RequiredSizeElement -> {
                width = element.width
                height = element.height
                requiredWidth = element.width != null
                requiredHeight = element.height != null
            }

            is ExpandElement -> {
                hexpand = element.horizontal
                vexpand = element.vertical
            }

            is AlignElement -> {
                halign = element.horizontal
                valign = element.vertical
            }

            // Weight is a property of the CONTAINER's layout manager, not of the widget: it is
            // pushed by `GtkBoxNode.pushChildLayout()` when this node is attached or recomposed.
            // Nothing to write here — but reading it from `modifier` is exactly what that does.
            //
            // `AspectRatioElement` is the same story: `pushChildLayout()` hands it to the
            // container's manager, and there is no GTK field to write.
            is WeightElement -> Unit

            // Container-owned like `weight`: `pushChildLayout()` (GtkApplier) sends it to
            // `KnBoxLayout`, so the widget itself has nothing to write.
            is AspectRatioElement -> Unit

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

            is MaxSizeElement -> {
                // No CSS is emitted: GTK has no max-width/max-height properties.
                // Collected into locals; the clamp is applied below after merge with
                // the explicit size.
                maxWidth = maxWidth ?: element.width
                maxHeight = maxHeight ?: element.height
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

            is ClickElement -> clickHandler = element.handler

            is HoverElement -> {
                hoverEnter = element.onEnter
                hoverExit = element.onExit
            }

            is CursorElement -> cursorName = element.name

            is AnimatedSizeElement -> applyAnimatedSizeModifier(widget, element)

            is RawStyleElement -> rules += element.selector to element.declarations
        }
    }

    gtk_widget_set_margin_start(widget, marginHorizontal)
    gtk_widget_set_margin_end(widget, marginHorizontal)
    gtk_widget_set_margin_top(widget, marginVertical)
    gtk_widget_set_margin_bottom(widget, marginVertical)

    if (width != null || height != null) {
        // GTK size requests are minimums, so a max can only cap what we ask for
        // explicitly; natural content is constrained elsewhere (see Label.maxWidthChars).
        val resolvedWidth = width?.let { maxWidth?.let { max -> minOf(it, max) } ?: it }
        val resolvedHeight = height?.let { maxHeight?.let { max -> minOf(it, max) } ?: it }
        gtk_widget_set_size_request(widget, resolvedWidth ?: -1, resolvedHeight ?: -1)
        setFieldWritten(widget, SIZE_KEY, true)
    } else if (fieldWritten(widget, SIZE_KEY)) {
        // -1 is GTK's "no request": the widget falls back to its natural size, which for a node
        // that pins itself is the pin it puts in front of the caller's chain (`Modifier.size`).
        gtk_widget_set_size_request(widget, -1, -1)
        setFieldWritten(widget, SIZE_KEY, false)
    }

    // A `Modifier` chain is rebuilt from scratch on every recomposition, so a field the chain no
    // longer mentions must go back to its GTK default (plan L9) — without this, `fillWidth()` stuck
    // to a widget forever. Only fields the chain itself wrote are reset: a writer outside the
    // pipeline (`GtkOverlayNode.attachChild()` centring its children) keeps its value, because
    // `applyModifiers` runs after it.
    if (hexpand != null) {
        gtk_widget_set_hexpand(widget, if (hexpand == true) 1 else 0)
        setFieldWritten(widget, HEXPAND_KEY, true)
    } else if (fieldWritten(widget, HEXPAND_KEY)) {
        gtk_widget_set_hexpand(widget, 0)
        setFieldWritten(widget, HEXPAND_KEY, false)
    }

    if (vexpand != null) {
        gtk_widget_set_vexpand(widget, if (vexpand == true) 1 else 0)
        setFieldWritten(widget, VEXPAND_KEY, true)
    } else if (fieldWritten(widget, VEXPAND_KEY)) {
        gtk_widget_set_vexpand(widget, 0)
        setFieldWritten(widget, VEXPAND_KEY, false)
    }

    // `requiredSize()` turns its request into a ceiling too: GTK honours a size request only as a
    // floor, so "exactly this" means "do not let the container's stretch reach me", and a non-FILL
    // alignment is the only way to say that. A chain that also aligns the axis keeps its own value
    // (written below), which is what compose does with `requiredSize().align()`.
    if (requiredWidth && halign == null) halign = GTK_ALIGN_START
    if (requiredHeight && valign == null) valign = GTK_ALIGN_START

    // GTK 4 leaves a fresh widget at GTK_ALIGN_FILL on *both* axes (measured on a bare GtkLabel
    // under gtk 4.24), so that is what a dropped `align()` restores.
    if (halign != null) {
        gtk_widget_set_halign(widget, halign)
        setFieldWritten(widget, HALIGN_KEY, true)
    } else if (fieldWritten(widget, HALIGN_KEY)) {
        gtk_widget_set_halign(widget, GTK_ALIGN_FILL)
        setFieldWritten(widget, HALIGN_KEY, false)
    }

    if (valign != null) {
        gtk_widget_set_valign(widget, valign)
        setFieldWritten(widget, VALIGN_KEY, true)
    } else if (fieldWritten(widget, VALIGN_KEY)) {
        gtk_widget_set_valign(widget, GTK_ALIGN_FILL)
        setFieldWritten(widget, VALIGN_KEY, false)
    }

    if (opacity != null) {
        gtk_widget_set_opacity(widget, opacity.toDouble())
        setFieldWritten(widget, OPACITY_KEY, true)
    } else if (fieldWritten(widget, OPACITY_KEY)) {
        gtk_widget_set_opacity(widget, 1.0)
        setFieldWritten(widget, OPACITY_KEY, false)
    }

    inlineStyle.setRules(rules)
    inlineStyle.interaction.bind(clickHandler, hoverEnter, hoverExit, cursorName)
}

// Which layout fields a `Modifier` chain has written on a widget, kept as GLib data on the widget
// itself (plan L9). GObject data needs a pointer, and the only thing ever read back is whether the
// key is present, so the value is the widget's own pointer: `g_object_set_data()` takes no destroy
// callback and never frees what it stores, and writing `null` removes the key again. The record
// therefore dies with the widget — no registry, no leak, and a widget that only ever got its
// alignment from outside the modifier pipeline is never touched.

private const val HEXPAND_KEY = "kn-modifier-hexpand"
private const val VEXPAND_KEY = "kn-modifier-vexpand"
private const val HALIGN_KEY = "kn-modifier-halign"
private const val VALIGN_KEY = "kn-modifier-valign"
private const val OPACITY_KEY = "kn-modifier-opacity"
private const val SIZE_KEY = "kn-modifier-size"

/** Whether a [Modifier] chain has written this layout field on [widget] before. */
private fun fieldWritten(widget: CPointer<GtkWidget>, key: String): Boolean =
    g_object_get_data(widget.reinterpret<GObject>(), key) != null

/** Claims [key] for the modifier chain, or hands the field back when [written] is false. */
private fun setFieldWritten(widget: CPointer<GtkWidget>, key: String, written: Boolean) {
    val value = if (written) widget.reinterpret<GObject>() else null
    g_object_set_data(widget.reinterpret<GObject>(), key, value)
}

