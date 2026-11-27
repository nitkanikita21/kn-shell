package dev.knshell.atlas.runner

import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.dsl.group
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun catalog(): List<StoryGroup> = listOf(
    group("UButton", category = "Element") {
        render { _ -> }
        story("Playground")
        story("Keyboard")
    },
    group("UBadge", category = "Element") {
        render { _ -> }
        story("Playground")
    },
    group("UInput", category = "Form") {
        render { _ -> }
        story("Keyboard")
    },
)

private fun names(sections: List<SidebarSection>): List<String> =
    sections.flatMap { it.groups }.flatMap { listed -> listed.entries.map { "${listed.group.title}/${it.name}" } }

class SidebarSearchTest {

    private fun found(query: String) = names(sidebarSections(catalog(), searchWords(query)))

    @Test
    fun `every word must occur in the category group or name`() {
        assertEquals(listOf("UButton/Keyboard"), found("button key"))
        assertEquals(listOf("UButton/Keyboard"), found("UButton/Keyboard"))
        assertEquals(listOf("UInput/Keyboard"), found("form"))
        assertEquals(listOf("UButton/Playground", "UButton/Keyboard"), found("  BUTT "))
        assertEquals(emptyList(), found("button badge"))
        assertEquals(4, found("").size)
    }

    @Test
    fun `categories and groups without a match are left out`() {
        val sections = sidebarSections(catalog(), searchWords("badge"))

        assertEquals(listOf("Element"), sections.map { it.category })
        assertEquals(listOf("UBadge"), sections.single().groups.map { it.group.title })
    }

    @Test
    fun `matches are marked once and the text is escaped`() {
        assertEquals(listOf(1..6), matchedRanges("UButton", listOf("butto", "utton")))
        assertEquals(
            "a &lt; <span underline=\"single\" foreground=\"#fff\">B</span> &amp; c",
            highlighted("a < B & c", listOf("b"), "#fff"),
        )
    }
}

class SidebarStateTest {

    private val state = SidebarState(catalog())

    @Test
    fun `a search puts the cursor on the first match and the arrows go around`() {
        assertNull(state.cursorKey)

        state.search("keyboard")
        assertEquals("UButton/Keyboard", state.cursorKey)
        state.move(1)
        assertEquals("UInput/Keyboard", state.cursorKey)
        state.move(1)
        assertEquals("UButton/Keyboard", state.cursorKey)
        state.move(-1)
        assertEquals("UInput", state.atCursor()?.first?.title)
    }

    @Test
    fun `closed groups are skipped until a search opens them`() {
        val button = catalog().first()
        state.toggle(button)

        state.move(1)
        assertEquals("UBadge/Playground", state.cursorKey)

        state.search("button")
        assertEquals("UButton/Playground", state.cursorKey)
        state.search("")
        assertTrue(state.closedGroups == listOf("UButton"))
    }

    @Test
    fun `one switch closes and opens every group`() {
        state.toggleAll()
        assertTrue(state.allClosed)
        state.move(1)
        assertNull(state.cursorKey)
        state.toggleAll()
        assertTrue(state.closedGroups.isEmpty())
    }
}
