package dev.knshell.atlas.runner

import dev.knshell.atlas.arg
import dev.knshell.atlas.sampleGroup
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.core.entry.StoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class AtlasHostServiceTest {
    private val group: StoryGroup = sampleGroup()
    private val primary: StoryEntry = group.entries.single { it.name == "Primary" }
    private val sizes: StoryEntry = group.entries.single { it.name == "Sizes" }

    @Test
    fun `selection starts as null`() {
        val service = AtlasHostService(listOf(group))
        assertNull(service.selection.value)
    }

    @Test
    fun `select sets the selection`() {
        val service = AtlasHostService(listOf(group))
        service.select(group, primary)
        assertEquals(Selection(group, primary), service.selection.value)
    }

    @Test
    fun `selection follows later select calls`() {
        val service = AtlasHostService(listOf(group))
        service.select(group, primary)
        service.select(group, sizes)
        assertEquals(Selection(group, sizes), service.selection.value)
    }

    @Test
    fun `argsFor returns the same live args for the same entry`() {
        val service = AtlasHostService(listOf(group))
        val selection = Selection(group, primary)
        assertSame(service.argsFor(selection), service.argsFor(selection))
    }

    @Test
    fun `argsFor returns different live args for different entries`() {
        val service = AtlasHostService(listOf(group))
        assertNotSame(
            service.argsFor(Selection(group, primary)),
            service.argsFor(Selection(group, sizes)),
        )
    }

    @Test
    fun `values set for an entry are kept when switching away and back`() {
        val service = AtlasHostService(listOf(group))
        val text: Arg.Text = group.arg("text")
        service.argsFor(Selection(group, primary)).set(text, "Kept")

        service.select(group, sizes)
        service.select(group, primary)

        assertEquals("Kept", service.argsFor(Selection(group, primary))[text])
    }

    @Test
    fun `values set for one entry do not leak into another entry`() {
        val service = AtlasHostService(listOf(group))
        val text: Arg.Text = group.arg("text")
        service.argsFor(Selection(group, primary)).set(text, "Only primary")

        assertEquals("Save", service.argsFor(Selection(group, sizes))[text])
    }
}
