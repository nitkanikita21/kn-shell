@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.compose

import kn.gtk.GtkButton
import kn.gtk.GtkFlowBoxChild
import kn.gtk.GtkGridLayoutChild
import kn.gtk.gtk_grid_layout_child_get_column
import kn.gtk.gtk_grid_layout_child_get_row
import kn.gtk.gtk_layout_manager_get_layout_child
import kn.gtk.gtk_widget_get_layout_manager
import kn.gtk.GtkOrientation
import kn.gtk.gtk_box_new
import kn.gtk.GtkWidget
import kn.gtk.gtk_button_get_child
import kn.gtk.gtk_flow_box_child_get_child
import kn.gtk.gtk_init_check
import kn.gtk.gtk_widget_get_first_child
import kn.gtk.gtk_widget_get_next_sibling
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A child composed late but placed earlier (`if (icon != null) Icon(icon)` before a label) must
 * land where Compose inserts it, not at the end of the GTK container.
 *
 * Requires a display connection, like [LayoutProbeTest].
 */
class ChildOrderTest {

    @BeforeTest
    fun initGtk() {
        check(gtk_init_check() != 0) { "ChildOrderTest needs a display connection" }
    }

    @Test
    fun `a button keeps its children in composition order`() {
        val button = GtkButtonContainerNode()
        fun shown() = siblings(gtk_button_get_child(button.widget.reinterpret<GtkButton>())!!)

        assertInsertionOrder(button, ::shown)
    }

    @Test
    fun `a row keeps its children in composition order`() {
        val row = GtkBoxNode(gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, 0)!!)
        fun shown() = siblings(row.widget)

        assertInsertionOrder(row, ::shown)
    }

    @Test
    fun `a flow keeps its children in composition order`() {
        val flow = GtkFlowNode()
        fun shown() = siblings(flow.widget).map { gtk_flow_box_child_get_child(it.reinterpret<GtkFlowBoxChild>())!! }

        assertInsertionOrder(flow, ::shown)
    }

    @Test
    fun `a grid places children row by row in composition order`() {
        val grid = GtkGridNode()
        grid.configure(columns = 2, spacing = 0)
        val labels = List(4) { GtkLabelNode() }
        fun cells() = labels.map { cellOf(grid, it) }

        grid.insertChild(0, labels[1])
        grid.insertChild(0, labels[0])
        grid.insertChild(2, labels[3])
        grid.insertChild(2, labels[2])
        assertEquals(listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), cells(), "inserted")

        grid.moveChild(0, 4, 1)
        assertEquals(listOf(1 to 1, 0 to 0, 1 to 0, 0 to 1), cells(), "first moved to the end")

        grid.configure(columns = 4, spacing = 0)
        assertEquals(listOf(3 to 0, 0 to 0, 1 to 0, 2 to 0), cells(), "wider")

        grid.removeChild(0, 1)
        assertEquals(listOf(2 to 0, 0 to 0, 1 to 0), listOf(0, 2, 3).map { cellOf(grid, labels[it]) }, "removed")
    }

    /** `column to row` of [child] in [grid]. */
    private fun cellOf(grid: GtkGridNode, child: GtkNode): Pair<Int, Int> {
        val manager = gtk_widget_get_layout_manager(grid.widget)!!
        val cell = gtk_layout_manager_get_layout_child(manager, child.widget)!!.reinterpret<GtkGridLayoutChild>()
        return gtk_grid_layout_child_get_column(cell) to gtk_grid_layout_child_get_row(cell)
    }

    /** Appends the last child, then inserts before it and between, and moves children around. */
    private fun assertInsertionOrder(parent: GtkNode, shown: () -> List<CPointer<GtkWidget>>) {
        val first = GtkLabelNode()
        val middle = GtkLabelNode()
        val last = GtkLabelNode()

        parent.insertChild(0, last)
        parent.insertChild(0, first)
        assertEquals(listOf(first.widget, last.widget), shown(), "insert before")

        parent.insertChild(1, middle)
        assertEquals(listOf(first.widget, middle.widget, last.widget), shown(), "insert between")

        // `Applier.move`: the target index counts positions before the move.
        parent.moveChild(0, 3, 1)
        assertEquals(listOf(middle.widget, last.widget, first.widget), shown(), "move to the end")

        parent.moveChild(2, 0, 1)
        assertEquals(listOf(first.widget, middle.widget, last.widget), shown(), "move to the front")

        parent.moveChild(0, 2, 1)
        assertEquals(listOf(middle.widget, first.widget, last.widget), shown(), "move one step forward")
    }

    private fun siblings(container: CPointer<GtkWidget>): List<CPointer<GtkWidget>> =
        generateSequence(gtk_widget_get_first_child(container)) { gtk_widget_get_next_sibling(it) }.toList()
}
