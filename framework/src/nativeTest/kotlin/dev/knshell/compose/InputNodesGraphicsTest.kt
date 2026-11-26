@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.compose

import kn.gtk.gtk_drop_down_get_selected
import kn.gtk.gtk_drop_down_set_selected
import kn.gtk.gtk_editable_get_text
import kn.gtk.gtk_editable_set_text
import kn.gtk.gtk_init_check
import kn.gtk.gtk_switch_get_active
import kn.gtk.gtk_switch_set_active
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The controlled-widget contract of the input nodes: writes coming from the composition never
 * reach the callback, changes made on the GTK widget (what a user does) always do.
 *
 * Requires a display connection, like [LayoutProbeTest].
 */
class InputNodesGraphicsTest {

    @BeforeTest
    fun initGtk() {
        check(gtk_init_check() != 0) { "InputNodesGraphicsTest needs a display connection" }
    }

    @Test
    fun `entry reports user edits and stays silent on programmatic writes`() {
        val node = GtkEntryNode()
        val seen = mutableListOf<String>()
        node.onChange = { seen += it }

        node.setText("Save")
        assertEquals(emptyList<String>(), seen)
        assertEquals("Save", gtk_editable_get_text(node.widget.reinterpret())?.toKString())

        gtk_editable_set_text(node.widget.reinterpret(), "Cancel")
        assertEquals("Cancel", seen.last())
    }

    @Test
    fun `switch reports user toggles and stays silent on programmatic writes`() {
        val node = GtkSwitchNode()
        val seen = mutableListOf<Boolean>()
        node.onChange = { seen += it }

        node.setChecked(true)
        assertEquals(emptyList<Boolean>(), seen)
        assertEquals(1, gtk_switch_get_active(node.widget.reinterpret()))

        gtk_switch_set_active(node.widget.reinterpret(), 0)
        assertEquals(listOf(false), seen)
    }

    @Test
    fun `drop down reports user picks and stays silent on programmatic writes`() {
        val node = GtkDropDownNode()
        val seen = mutableListOf<Int>()
        node.onChange = { seen += it }

        node.setItems(listOf("a", "b", "c"))
        node.setSelected(2)
        assertEquals(emptyList<Int>(), seen)
        assertEquals(2u, gtk_drop_down_get_selected(node.widget.reinterpret()))

        gtk_drop_down_set_selected(node.widget.reinterpret(), 1u)
        assertEquals(listOf(1), seen)
    }

    @Test
    fun `drop down keeps the requested selection when the items change`() {
        val node = GtkDropDownNode()
        val seen = mutableListOf<Int>()
        node.onChange = { seen += it }
        node.setSelected(1)

        node.setItems(listOf("a", "b"))
        assertEquals(1u, gtk_drop_down_get_selected(node.widget.reinterpret()))

        node.setItems(listOf("x", "y", "z"))
        assertEquals(1u, gtk_drop_down_get_selected(node.widget.reinterpret()))
        assertEquals(emptyList<Int>(), seen)
    }
}
