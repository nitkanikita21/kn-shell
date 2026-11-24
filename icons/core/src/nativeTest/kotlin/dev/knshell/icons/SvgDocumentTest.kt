package dev.knshell.icons

import dev.knshell.compose.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tint-wrapper tests — pure string work, no GTK connection needed.
 *
 * The exact markup shape matters: it is what librsvg parses on the render path,
 * and a regression here is an invisible blank icon, not a crash.
 */
class SvgDocumentTest {

    private val stroke = IconStyle(tintAttribute = "stroke", strokeWidth = 2.0, viewBoxSize = 24.0)
    private val fill = IconStyle(tintAttribute = "fill", strokeWidth = 0.0, viewBoxSize = 48.0)

    // Real-world body: attributes on the body element must lose their own color.
    private val body = """<path d="M3 9l9 7 9-7" stroke="currentColor" fill="none"/>"""

    @Test
    fun strokeTintOverridesBodyColorAndAddsGeometry() {
        val doc = buildTintedDocument(body, stroke, "#09090C")!!
        assertTrue(doc.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"24.0\" height=\"24.0\" viewBox=\"0 0 24.0 24.0\">"), doc)
        assertTrue(doc.contains("<g stroke=\"#09090C\" fill=\"none\" stroke-linecap=\"round\" stroke-linejoin=\"round\" stroke-width=\"2\">"), doc)
        // the body's own stroke value is gone …
        assertEquals(1, doc.split("stroke=\"").size - 1, "exactly one stroke color in $doc")
        // … but its fill="none" is preserved as geometry.
        assertTrue(doc.contains("fill=\"none\""), doc)
        assertTrue(doc.endsWith("</g></svg>"), doc)
    }

    @Test
    fun fillTintUsesFillAttributeWithoutStrokeGeometry() {
        val doc = buildTintedDocument("""<path d="M0 0h48v48H0z" fill="currentColor"/>""", fill, "#FF00AA")!!
        assertTrue(doc.contains("<g fill=\"#FF00AA\" stroke-width=\"0\">"), doc)
        assertTrue(!doc.contains("stroke-linecap"), doc)
        assertTrue(!doc.contains("currentColor"), doc)
    }

    @Test
    fun globalAttributeFormIsStrippedToo() {
        val doc = buildTintedDocument("""<path stroke:global="currentColor" d="M1 1"/>""", stroke, "#FFFFFF")!!
        assertTrue(!doc.contains("currentColor"), doc)
        assertEquals(1, doc.split("stroke=\"").size - 1, doc)
    }

    @Test
    fun nullColorKeepsNativeMarkupAndOnlyNormalizesWidth() {
        val native = """<path d="M1 1" stroke="blue" stroke-width="3"/>"""
        val doc = buildTintedDocument(native, stroke, null)!!
        assertTrue(doc.contains("stroke=\"blue\""), "native colors must survive: $doc")
        assertTrue(doc.contains("stroke-width=\"2\""), "width normalized to the pack default: $doc")
    }

    @Test
    fun nullColorRestoresStrokeGeometryLostWithTheRootTag() {
        // Generated Lucide bodies contain no root attributes, so the pack's
        // fill="none" / stroke="currentColor" must be re-applied by the renderer.
        val generatedLucideBody = """<rect width="18" height="18" x="3" y="3" rx="2"/>"""
        val doc = buildTintedDocument(generatedLucideBody, stroke, null)!!
        assertTrue(doc.contains("fill=\"none\""), "closed paths must stay unfilled: $doc")
        assertTrue(doc.contains("stroke=\"currentColor\""), doc)
        assertTrue(doc.contains("stroke-width=\"2\""), "pack default survives without an explicit color: $doc")
    }

    @Test
    fun strokeWidthOverrideWinsOverPackDefault() {
        val doc = buildTintedDocument(body, stroke, "#000000", strokeWidth = 1.5)!!
        assertTrue(doc.contains("stroke-width=\"1.5\""), doc)
        assertTrue(buildTintedDocument(body, stroke, "#000000")!!.contains("stroke-width=\"2\""), "pack default is 2")
    }

    @Test
    fun viewBoxOverrideReplacesDerivedBox() {
        val offset = IconStyle("fill", 0.0, 48.0, "0 -960 960 960")
        val doc = buildTintedDocument("<path d=\"M1 1\"/>", offset, "#123456")!!
        assertTrue(doc.contains("width=\"48.0\" height=\"48.0\" viewBox=\"0 -960 960 960\""), doc)
    }

    @Test
    fun missingMarkupYieldsNoDocument() {
        assertNull(buildTintedDocument(null, stroke, "#000000"))
    }

    @Test
    fun colorToHexIsUppercaseAndClamped() {
        assertEquals("#09090C", colorToHex(Color(9, 9, 12)))
        assertEquals("#FF00FF", colorToHex(Color(300, -5, 255, 0f)))
        assertEquals("#00FF80", colorToHex(Color(0, 255, 128, 0.5f)))
    }
}
