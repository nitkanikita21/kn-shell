// ---------------------------------------------------------------------------
// `applyModifiers` — turns a `Modifier` chain into (a) CSS rules on the widget's
// `InlineStyle` and (b) GTK layout calls (margin, size request, expand, align,
// opacity, overflow). Nothing is animated or measured here: the fields the chain
// no longer mentions are reset to their GTK default (plan L9) — the per-widget
// record of what was written lives in InlineStyle.kt.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kn.gtk.GtkOverflow
import kn.gtk.GtkWidget
import kn.gtk.GTK_ALIGN_FILL
import kn.gtk.GTK_ALIGN_START
import kn.gtk.gtk_widget_add_css_class
import kn.gtk.gtk_widget_set_halign
import kn.gtk.gtk_widget_remove_css_class
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
import dev.knshell.animation.applyAnimatedSizeModifier

/** `border` on all four sides, or one `border-<side>` declaration per listed side. */
internal fun borderDeclarations(width: Int, color: Color, sides: Set<BorderSide>): String {
    val value = "${width}px solid ${color.toCss()}"
    return if (sides == BorderSide.ALL) {
        "border: $value;"
    } else {
        sides.joinToString(" ") { side -> "border-${side.name.lowercase()}: $value;" }
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
    var scrollHandler: ((Double, Double) -> Unit)? = null
    var dragHandlers: DragElement? = null
    val keyHandlers = mutableListOf<KeyElement>()
    var focusable: FocusableElement? = null
    var focusRequester: FocusRequester? = null
    var focusChange: ((Boolean) -> Unit)? = null
    var semanticsRef: SemanticsRef? = null
    // Colour transitions per selector: several modifiers may ask, and CSS keeps only one
    // `transition` declaration, so they are merged into one at the end.
    val transitions = mutableMapOf<String, MutableMap<String, Int>>()
    fun transition(selector: String, ms: Int, vararg properties: String) {
        if (ms <= 0) return
        val declared = transitions.getOrPut(selector) { linkedMapOf() }
        properties.forEach { declared[it] = ms }
    }
    var valign: UInt? = null
    var inputRegion = false
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
                }
                rules += "" to declarations
                transition("", element.transitionMs, "background-color", "color")
                if (element.borderWidth > 0) transition("", element.transitionMs, "border-color")
            }

            is HoverBackgroundElement -> rules += ":hover" to "background-color: ${element.color.toCss()};"

            is TextColorElement -> {
                rules += "" to "color: ${element.color.toCss()};"
                transition("", element.transitionMs, "color")
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

                listOf(" trough", " highlight", " slider").forEach {
                    transition(it, element.transitionMs, "background-color")
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

            is TransitionElement -> transition(
                "", element.ms, "color", "background-color", "border-color", "box-shadow", "opacity",
            )

            is SelectionElement ->
                rules += " selection" to "background-color: ${element.background.toCss()}; color: ${element.color.toCss()};"

            is ClickElement -> clickHandler = element.handler

            is HoverElement -> {
                hoverEnter = element.onEnter
                hoverExit = element.onExit
            }

            is CursorElement -> cursorName = element.name
            is ScrollElement -> scrollHandler = element.handler
            is KeyElement -> keyHandlers += element
            is FocusableElement -> focusable = element
            is FocusRequesterElement -> focusRequester = element.requester
            is FocusChangeElement -> focusChange = element.handler
            // Read from the chain as a whole (mergedSemantics), below.
            is SemanticsElement -> Unit
            is SemanticsRefElement -> semanticsRef = element.ref
            is DragElement -> dragHandlers = element
            is InputRegionElement -> inputRegion = true

            is AnimatedSizeElement -> applyAnimatedSizeModifier(widget, element)

            is RawStyleElement -> rules += element.selector to element.declarations

            // First, whatever its place in the chain: `all` would undo the declarations before it.
            StyleResetElement -> rules.add(0, "" to "all: initial; color: $DEFAULT_FOREGROUND;")

            is BorderElement -> if (element.sides.isNotEmpty()) {
                rules += "" to borderDeclarations(element.width, element.color, element.sides)
                transition("", element.transitionMs, "border-color")
            }

            is LabelStyleElement -> {
                rules += " label" to buildString {
                    append("color: ${element.color.toCss()};")
                    element.fontFamily?.let { append(" font-family: \"$it\";") }
                    element.fontSize?.let { append(" font-size: ${it}px;") }
                }
                transition(" label", element.transitionMs, "color")
            }

            is EntryStyleElement -> {
                rules += " > text" to
                    "color: ${element.textColor.toCss()}; caret-color: ${element.caretColor.toCss()}; " +
                    "background-color: transparent;"
                rules += " > text > selection" to
                    "background-color: ${element.selectionBackground.toCss()}; " +
                    "color: ${element.selectionColor.toCss()};"
                transition(" > text", element.transitionMs, "color", "caret-color")
            }

            is SwitchKnobElement -> {
                rules += " > slider" to
                    "background-color: ${element.color.toCss()}; min-width: ${element.size}px; " +
                    "min-height: ${element.size}px; border-radius: ${element.radius}px;"
                rules += ":checked > slider" to "background-color: ${element.checkedColor.toCss()};"
            }

            is DropDownButtonElement -> rules += " > button" to
                "border: ${element.borderWidth}px solid ${element.border.toCss()}; " +
                "border-radius: ${element.radius}px; " +
                "background-color: ${element.background.toCss()}; " +
                "color: ${element.color.toCss()}; " +
                "padding: ${element.paddingVertical}px ${element.paddingHorizontal}px; min-height: 0;"

            is DropDownPopoverElement -> {
                rules += " popover > contents" to
                    "background-color: ${element.background.toCss()}; " +
                    "border: ${element.borderWidth}px solid ${element.border.toCss()}; " +
                    "border-radius: ${element.radius}px; color: ${element.color.toCss()};"
                rules += " popover row" to
                    "padding: ${element.rowPaddingVertical}px ${element.rowPaddingHorizontal}px;"
                rules += " popover row:hover" to "background-color: ${element.rowHover.toCss()};"
            }

            // Read by the inspector from the chain (`Modifier.testTagName`); nothing to paint.
            is TestTagElement -> Unit
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

    transitions.forEach { (selector, properties) ->
        rules += selector to properties.entries.joinToString(", ", "transition: ", ";") { (property, ms) ->
            "$property ${ms}ms ease"
        }
    }
    inlineStyle.setRules(rules)
    inlineStyle.input.bind(keyHandlers, focusable, focusRequester, focusChange)
    inlineStyle.semantics.bind(modifier.mergedSemantics(), semanticsRef)
    inlineStyle.interaction.bind(clickHandler, hoverEnter, hoverExit, cursorName, scrollHandler, dragHandlers)

    // Marks the widget for the window's input-region shaping (`dev.knshell.shell.InputRegion`).
    // Like every other field here, a chain that no longer mentions it has to clear it.
    if (inputRegion) {
        gtk_widget_add_css_class(widget, INPUT_REGION_CLASS)
    } else {
        gtk_widget_remove_css_class(widget, INPUT_REGION_CLASS)
    }
}
