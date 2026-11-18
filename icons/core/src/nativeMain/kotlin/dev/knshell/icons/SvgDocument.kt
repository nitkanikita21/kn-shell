package dev.knshell.icons

import dev.knshell.compose.Color

/**
 * Pure, display-free part of the renderer: turning pack markup into a complete
 * SVG document string with a tint applied.
 *
 * Kept side-effect-free and public so `:icons:core` tests (and other packs) can
 * assert on the exact markup without a GTK connection.
 */

/** framework [Color] (0..255 ints) → `#RRGGBB`, uppercase, alpha ignored. */
fun colorToHex(color: Color): String {
    fun ch(v: Int) = v.coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()
    return "#" + ch(color.r) + ch(color.g) + ch(color.b)
}

private val attrGlobal = Regex("""\s+[A-Za-z-]+(:[A-Za-z0-9_.-]+)?\s*=\s*"[^"]*"""")

/**
 * Strip every occurrence of [attribute] (`stroke`, `fill`, …) from the markup,
 * including its `:global` prefixed form.
 *
 * Icons that need their fill color for shape definition (`fill="none"` on
 * Lucide paths) keep working because the value is re-applied on the wrapper
 * `<g>` in [buildTintedDocument].
 */
private fun stripAttribute(markup: String, attribute: String): String =
    attribute.split(',').fold(markup) { acc, a ->
        acc.replace(Regex("""(?i)\s+\Q$a\E(?::global)?\s*=\s*"[^"]*""""), "")
    }

/** `2.0` → `2`, `1.5` → `1.5` — the SVG number form used everywhere in documents. */
private fun formatWidth(width: Double): String =
    if (width == width.toLong().toDouble()) width.toLong().toString() else width.toString()

/** Normalize every `stroke-width="…"` to [width]. */
private fun normalizeStrokeWidth(markup: String, width: Double): String =
    markup.replace(Regex("""(?i)\s+stroke-width\s*=\s*"[^"]*""""), " stroke-width=\"${formatWidth(width)}\"")

/**
 * Wrap a pack's raw body markup into a standalone SVG document.
 *
 * When [colorHex] is non-null the tint is applied *centrally*: every existing
 * value of `style.tintAttribute` (comma-separated, e.g. `"fill,stroke"` for
 * solid glyph sets) is stripped from the body and the attribute is set on a
 * single wrapper `<g>` together with `fill="none"`, stroke geometry and
 * [strokeWidth]. The wrapper element itself carries **no** attributes —
 * attribute-preserving regexes in pack adapters rely on that.
 *
 * [colorHex] `null` means "keep the pack's native colors": the body is embedded
 * verbatim inside a bare `<svg>` (solid fonts like Material Symbols rely on
 * `fill="currentColor"` staying in the markup, so nothing is touched there).
 *
 * Returns `null` only for a `null` [inner]; callers map that to an empty slot.
 */
fun buildTintedDocument(
    inner: String?,
    style: IconStyle,
    colorHex: String?,
    strokeWidth: Double? = null,
): String? {
    if (inner == null) return null
    val vb = style.viewBoxSize
    val box = style.viewBoxOverride ?: "0 0 $vb $vb"
    val width = strokeWidth ?: style.strokeWidth
    val body = if (colorHex == null) {
        normalizeStrokeWidth(inner, width)
    } else {
        val stripped = stripAttribute(inner, style.tintAttribute)
        val geometry = if (style.tintAttribute.startsWith("stroke")) {
            " fill=\"none\" stroke-linecap=\"round\" stroke-linejoin=\"round\""
        } else {
            ""
        }
        "<g ${style.tintAttribute}=\"$colorHex\"$geometry stroke-width=\"${formatWidth(width)}\">$stripped</g>"
    }
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$vb\" height=\"$vb\" viewBox=\"$box\">$body</svg>"
}
