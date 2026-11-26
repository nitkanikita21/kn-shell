package dev.knshell.atlas.core.entry

import dev.knshell.atlas.Size
import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.runner.AtlasHostService
import dev.knshell.atlas.runner.ui.captionFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatrixLayoutTest {

    private val sample = group("Button") {
        val size by enum(Size.S)
        val enabled by boolean(true)
        val label by string("Save")
        render { _ -> }
        variants("Pair") {
            axis(size)
            axis(enabled)
            exclude { it[size] == Size.S && it[enabled] }
        }
        variants("Single") { axis(size) }
        variants("Triple") {
            axis(size)
            axis(enabled)
            axis(label, listOf("a", "b"))
        }
        variants("Tabled", view = MatrixView.Table) {
            axis(size)
            axis(enabled)
        }
    }

    private fun matrix(name: String) = sample.entries.single { it.name == name } as Variants

    @Test
    fun `a matrix opens in the compose view unless the story says otherwise`() {
        assertEquals(MatrixView.Compose, matrix("Pair").defaultView)
        assertEquals(MatrixView.Table, matrix("Tabled").defaultView)
    }

    @Test
    fun `the table view exists only for two axes`() {
        assertEquals(listOf(MatrixView.Compose, MatrixView.Table), matrix("Pair").views)
        assertEquals(listOf(MatrixView.Compose), matrix("Single").views)
        assertEquals(listOf(MatrixView.Compose), matrix("Triple").views)
    }

    @Test
    fun `asking for a table of one axis fails when the story is declared`() {
        assertFailsWith<IllegalArgumentException> {
            group("Bad") {
                val enabled by boolean(true)
                render { _ -> }
                variants("One", view = MatrixView.Table) { axis(enabled) }
            }
        }
    }

    @Test
    fun `compose groups follow the first axis`() {
        val triple = matrix("Triple")
        val groups = triple.groups()

        assertEquals(listOf("S", "M", "L"), groups.map { it.title })
        assertEquals(listOf(4, 4, 4), groups.map { it.combos.size })
        assertEquals("S · true · a", captionFor(triple, groups.first().combos.first()))
    }

    @Test
    fun `a single axis has one untitled group`() {
        val group = matrix("Single").groups().single()

        assertNull(group.title)
        assertEquals(3, group.combos.size)
    }

    @Test
    fun `the table keeps a gap where a combination was excluded`() {
        val pair = matrix("Pair")
        val table = pair.table()

        assertEquals(listOf("S", "M", "L"), table.rows)
        // the first row lacks "true", so its column comes second
        assertEquals(listOf("false", "true"), table.columns)
        assertNull(table.cells[0][1])
        assertEquals("M · true", captionFor(pair, table.cells[1][1]!!))
    }

    @Test
    fun `the chosen view and collapsed groups are remembered per matrix`() {
        val service = AtlasHostService(listOf(sample))
        val pair = matrix("Pair")
        val tabled = matrix("Tabled")

        service.setView(pair, MatrixView.Table)
        service.toggleGroup(tabled, "S")

        assertEquals(MatrixView.Table, service.viewOf(pair))
        assertEquals(MatrixView.Table, service.viewOf(tabled))
        assertTrue(service.isCollapsed(tabled, "S"))
        assertFalse(service.isCollapsed(pair, "S"))
        service.toggleGroup(tabled, "S")
        assertFalse(service.isCollapsed(tabled, "S"))
    }

    @Test
    fun `a view the matrix does not support is refused`() {
        val service = AtlasHostService(listOf(sample))

        assertFailsWith<IllegalArgumentException> { service.setView(matrix("Single"), MatrixView.Table) }
    }
}
