package dev.knshell.atlas.runner.ui

import dev.knshell.atlas.Size
import dev.knshell.atlas.dsl.group
import kotlin.test.Test
import kotlin.test.assertEquals

class SidebarOrderTest {

    @Test
    fun `stories come before matrices and both keep their declared order`() {
        val group = group("G") {
            val size by enum(Size.S)
            render { _ -> }
            story("Playground")
            variants("Variants") { axis(size) }
            story("States")
            variants("Sizes") { axis(size) }
            story("Examples")
        }

        assertEquals(
            listOf("Playground", "States", "Examples", "Variants", "Sizes"),
            sidebarOrder(group.entries).map { it.name },
        )
    }
}
