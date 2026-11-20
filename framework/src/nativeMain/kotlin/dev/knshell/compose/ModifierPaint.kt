// ---------------------------------------------------------------------------
// Paint modifiers — backgrounds (flat, state-dependent, gradients), opacity,
// glow, corner radii, selection colours, overflow clipping, raw CSS escape
// hatch and the `GtkScale` theme.
// ---------------------------------------------------------------------------

package dev.knshell.compose

/**
 * Flat background fill, optionally rounded, shadowed and bordered — all lengths in CSS pixels.
 *
 * ```kotlin
 * Box(Modifier.padding(8).background(Catppuccin.Surface1, radius = 12, borderWidth = 1, borderColor = Catppuccin.Surface3))
 * ```
 *
 * [transitionMs] > 0 animates `background-color` and `color` changes over that many milliseconds;
 * [shadow] adds GTK's fixed soft drop shadow; a border is drawn only when both [borderWidth] > 0
 * and [borderColor] are given.
 */
fun Modifier.background(
    color: Color,
    radius: Int = 0,
    shadow: Boolean = false,
    borderWidth: Int = 0,
    borderColor: Color? = null,
    transitionMs: Int = 0,
): Modifier = with(BackgroundElement(color, radius, shadow, borderWidth, borderColor, transitionMs))

/** Background while the pointer is over the widget (`:hover`), replacing the base fill. */
fun Modifier.hoverBackground(color: Color): Modifier = with(HoverBackgroundElement(color))

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

/** Widget opacity, `0f` invisible to `1f` opaque (GTK `gtk_widget_set_opacity`, whole subtree). */
fun Modifier.opacity(value: Float): Modifier = with(OpacityElement(value))

/**
 * Escape hatch for CSS the typed modifiers cannot express: [declarations] are emitted verbatim
 * under [selector] (empty = the widget itself, `":hover"` = a state, `" trough"` = a sub-node).
 */
fun Modifier.rawStyle(selector: String, declarations: String): Modifier = with(RawStyleElement(selector, declarations))

/** Background while the widget is pressed (`:active`). */
fun Modifier.pressedBackground(color: Color): Modifier = with(StateBackgroundElement(":active", color))

/** Background while the widget is checked/toggled on (`:checked`). */
fun Modifier.checkedBackground(color: Color): Modifier = with(StateBackgroundElement(":checked", color))

/** Background while the widget is disabled (`:disabled`). */
fun Modifier.disabledBackground(color: Color): Modifier = with(StateBackgroundElement(":disabled", color))

/** Text colour while the pointer is over the widget (`:hover`). */
fun Modifier.hoverTextColor(color: Color): Modifier = with(StateTextColorElement(":hover", color))

/** Text colour while the widget is checked/toggled on (`:checked`). */
fun Modifier.checkedTextColor(color: Color): Modifier = with(StateTextColorElement(":checked", color))

/** Keyboard focus outline. GTK draws it for `:focus-visible` only (keyboard, not mouse). */
fun Modifier.focusRing(color: Color, width: Int = 2, offset: Int = 0): Modifier =
    with(FocusRingElement(color, width, offset))


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
