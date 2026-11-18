package dev.knshell.icons.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Naming contract between upstream file names and generated Kotlin properties.
 * The scanner and the codegen share [NameMapping], so these assertions are the
 * reason `Lucide.WifiOff` in app source resolves to `wifi-off.svg`.
 */
class NameMappingTest {

    @Test
    fun toClassNameHandlesDashesUnderscoresAndAcronyms() {
        assertEquals("Home", NameMapping.toClassName("home"))
        assertEquals("WifiOff", NameMapping.toClassName("wifi-off"))
        assertEquals("ArrowDownRight", NameMapping.toClassName("arrow-down-right"))
        assertEquals("Srgb", NameMapping.toClassName("srgb"))
        assertEquals("HouseSiding", NameMapping.toClassName("house_siding"))
        assertEquals("CircleUserRound", NameMapping.toClassName("circle-user-round"))
        assertEquals("Check2", NameMapping.toClassName("check-2"))
        assertEquals("CodeXml", NameMapping.toClassName("code-xml"))
        assertEquals("", NameMapping.toClassName(""))
        assertEquals("A", NameMapping.toClassName("-a-"))
    }

    @Test
    fun toIdIsTheInverseForRealCatalogNames() {
        for (id in listOf("home", "wifi-off", "arrow-down-right", "srgb", "chevrons-up-down", "house-siding")) {
            val cls = NameMapping.toClassName(id)
            assertEquals(id, NameMapping.toId(cls), "round trip of $id via $cls")
        }
    }

    @Test
    fun toIdSplitsAcronymRunsBeforeLowercase() {
        assertEquals("srgb", NameMapping.toId("Srgb"))
        assertEquals("chevrons-up-down", NameMapping.toId("ChevronsUpDown"))
        assertEquals("arrow-down-right", NameMapping.toId("ArrowDownRight"))
        // Consonant runs stay glued: `Http` is one word, not `h-t-t-p`.
        assertEquals("http", NameMapping.toId("Http"))
        assertEquals("eye-off", NameMapping.toId("EyeOff"))
    }

    @Test
    fun catalogEntriesResolvesCollisionsDeterministically() {
        val ids = listOf("a", "arrow-down-0-1", "arrow-down-01", "b")
        val first = NameMapping.catalogEntries(ids)
        assertEquals(first, NameMapping.catalogEntries(ids), "same input, same output")
        assertEquals(
            listOf("A" to "a", "ArrowDown01" to "arrow-down-0-1", "ArrowDown012" to "arrow-down-01", "B" to "b"),
            first,
        )
        // Every generated property name is unique …
        assertEquals(first.size, first.map { it.first }.distinct().size)
        // … and every class name still maps back to a catalog id.
        assertFalse(first.any { it.second.isBlank() })
    }

    @Test
    fun catalogEntriesSkipsNamesThatCannotBeKotlinIdentifiers() {
        val entries = NameMapping.catalogEntries(listOf("10k", "a-b", "-", "home"))
        assertEquals(listOf("AB" to "a-b", "Home" to "home"), entries)
        assertFalse(entries.any { it.first.first().isDigit() }, "no leading digit: $entries")
    }

    @Test
    fun thirdCollisionGetsItsOwnOrdinal() {
        // "x-1", "x1" and "x-1-" all read as X1; each duplicate gets its ordinal.
        val entries = NameMapping.catalogEntries(listOf("x-1", "x1", "x-1-"))
        assertEquals(listOf("X1" to "x-1", "X12" to "x1", "X13" to "x-1-"), entries)
    }
}
