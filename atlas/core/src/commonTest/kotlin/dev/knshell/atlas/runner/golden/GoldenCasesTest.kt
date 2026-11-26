package dev.knshell.atlas.runner.golden

import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.runner.parseGoldenOptions
import dev.knshell.atlas.sampleGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoldenCasesTest {

    private val groups = listOf(sampleGroup())

    @Test
    fun `a story is one case and a matrix one per combination`() {
        val cases = goldenCases(groups)

        assertEquals(1 + 3 + 6, cases.size)
        assertEquals("Button/Primary", cases.first().id)
        assertEquals("Button/Primary.png", cases.first().path)
        assertEquals("Button/Matrix/S · true", cases.first { it.story == "Button/Matrix" }.id)
        assertEquals("Button/Matrix/S · true.png", cases.first { it.story == "Button/Matrix" }.path)
    }

    @Test
    fun `a filter takes a whole story or one cell`() {
        assertEquals(3, goldenCases(groups, "button/sizes").size)
        assertEquals(listOf("Button/Sizes/M"), goldenCases(groups, "Button/Sizes/M").map { it.id })
        assertFailsWith<IllegalArgumentException> { goldenCases(groups, "Button/Nope") }
    }

    @Test
    fun `names cannot leave their directory`() {
        assertEquals("a_b", safeName("a/b"))
        assertEquals("_", safeName(".."))
        assertEquals("_", safeName("  "))
    }

    @Test
    fun `two cases cannot share a file`() {
        val clash = group("G") {
            render { _ -> }
            story("a/b")
            story("a_b")
        }

        val error = assertFailsWith<IllegalArgumentException> { goldenCases(listOf(clash)) }
        assertTrue("G/a_b.png" in error.message.orEmpty())
    }

    @Test
    fun `golden flags turn the run on`() {
        assertNull(parseGoldenOptions(arrayOf("--story", "A/B", "--record")))
        assertEquals(
            GoldenOptions("g", "build/atlas-golden"),
            parseGoldenOptions(arrayOf("--golden=g")),
        )
        assertEquals(
            GoldenOptions("g", "o", record = true, filter = "A/B"),
            parseGoldenOptions(arrayOf("--golden", "g", "--golden-out=o", "--record", "--story=A/B")),
        )
    }
}
