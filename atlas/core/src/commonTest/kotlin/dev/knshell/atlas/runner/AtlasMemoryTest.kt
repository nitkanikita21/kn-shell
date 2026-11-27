package dev.knshell.atlas.runner

import dev.knshell.atlas.core.entry.MatrixView
import dev.knshell.atlas.sampleGroup
import dev.knshell.atlas.variantsNamed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtlasMemoryTest {

    @Test
    fun `memory survives its file format`() {
        val memory = AtlasMemory(
            story = "Button/Primary",
            environment = "Light",
            views = mapOf("Button/Matrix" to "Table"),
            closedGroups = listOf("Button"),
            closedCells = listOf("Button/Sizes/M"),
            sidebarHidden = true,
            width = 320,
        )

        assertEquals(memory, AtlasMemory.parse(memory.toJson()))
        assertEquals(AtlasMemory(), AtlasMemory.parse("not json"))
        assertEquals(AtlasMemory(), AtlasMemory.parse("{}"))
    }

    @Test
    fun `the window comes back as it was left`() {
        val group = sampleGroup()
        val first = AtlasHostService(listOf(group))
        val matrix = group.variantsNamed("Matrix")
        first.setView(matrix, MatrixView.Table)
        first.toggleGroup(group.variantsNamed("Sizes"), "S")
        first.sidebar.toggle(group)
        first.view.argsHidden = true
        first.view.width = 640
        val selected = Selection(group, matrix)

        val memory = first.memory(selected)
        val second = AtlasHostService(listOf(group))
        second.restore(memory)

        assertEquals("Button/Matrix", memory.story)
        assertEquals(MatrixView.Table, second.viewOf(matrix))
        assertTrue(second.isCollapsed(group.variantsNamed("Sizes"), "S"))
        assertEquals(listOf("Button"), second.sidebar.closedGroups)
        assertTrue(second.view.argsHidden)
        assertEquals(640, second.view.width)
        assertEquals(memory, second.memory(selected))
    }

    @Test
    fun `what the catalog no longer has is skipped`() {
        val service = AtlasHostService(listOf(sampleGroup()))

        service.restore(
            AtlasMemory(
                environment = "Gone",
                views = mapOf("Button/Matrix" to "Gone", "Other/Matrix" to "Table"),
                closedGroups = listOf("Other"),
                width = 123,
            ),
        )

        assertEquals(AtlasMemory(), service.memory(null))
    }

    @Test
    fun `the file lives in the state directory and is named after the atlas`() {
        assertEquals("/s/kn-atlas/_app_atlas_kexe.json", memoryFile("/app/atlas.kexe", "/s", "/home/u"))
        assertEquals("/home/u/.local/state/kn-atlas/a.json", memoryFile("a", "", "/home/u"))
        assertNull(memoryFile("a", null, null))
    }
}
