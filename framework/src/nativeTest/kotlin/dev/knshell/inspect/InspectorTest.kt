@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.inspect

import dev.knshell.compose.GtkBoxNode
import dev.knshell.compose.GtkButtonContainerNode
import dev.knshell.compose.GtkButtonNode
import dev.knshell.compose.GtkDropDownNode
import dev.knshell.compose.GtkEntryNode
import dev.knshell.compose.GtkLabelNode
import dev.knshell.compose.GtkNode
import dev.knshell.compose.GtkSliderNode
import dev.knshell.compose.GtkSwitchNode
import dev.knshell.compose.Modifier
import dev.knshell.compose.onClick
import dev.knshell.compose.testTag
import kn.gtk.GTK_STATE_FLAG_ACTIVE
import kn.gtk.GTK_STATE_FLAG_FOCUSED
import kn.gtk.GTK_STATE_FLAG_FOCUS_VISIBLE
import kn.gtk.GTK_STATE_FLAG_PRELIGHT
import kn.gtk.GValue
import kn.gtk.GtkOrientation
import kn.gtk.g_object_get_property
import kn.gtk.g_object_set_property
import kn.gtk.g_type_from_name
import kn.gtk.g_value_get_boolean
import kn.gtk.g_value_init
import kn.gtk.g_value_set_boolean
import kn.gtk.g_value_unset
import kn.gtk.gtk_settings_get_default
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kn.gtk.gtk_box_new
import kn.gtk.gtk_init_check
import kn.gtk.gtk_widget_get_state_flags
import kn.gtk.gtk_widget_set_sensitive
import kn.gtk.gtk_widget_set_visible
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The inspector against a hand-built node tree that is never put into a window: nothing is
 * presented, so the tests run on any desktop. Actions are checked through the Compose callbacks
 * they must fire and through the GTK state they must leave behind.
 *
 * Requires a display connection, like [InputNodesTest].
 */
class InspectorTest {

    private lateinit var root: GtkBoxNode
    private lateinit var inspector: Inspector

    @BeforeTest
    fun setUp() {
        check(gtk_init_check() != 0) { "InspectorTest needs a display connection" }
        root = newBox()
        inspector = Inspector(root = { root }, idle = {})
    }

    // --- tree ---

    @Test
    fun `tree reports the nesting and the type of every node`() {
        root.add(GtkLabelNode())
        val column = root.add(newBox())
        column.add(GtkButtonNode("Save"))

        val tree = inspector.tree()

        assertEquals("Box", tree.type)
        assertEquals(listOf("Label", "Box"), tree.children.map { it.type })
        assertEquals(listOf("Button"), tree.children[1].children.map { it.type })
    }

    @Test
    fun `tree of a container button lists its content`() {
        val button = root.add(GtkButtonContainerNode())
        button.add(GtkLabelNode())

        val tree = inspector.tree()

        assertEquals("ButtonContainer", tree.children.single().type)
        assertEquals(listOf("Label"), tree.children.single().children.map { it.type })
    }

    @Test
    fun `ids are unique across the tree and stable between reads`() {
        root.add(GtkLabelNode())
        val column = root.add(newBox())
        column.add(GtkButtonNode("Save"))
        column.add(GtkSwitchNode())

        val first = inspector.tree().flatten().map { it.id }
        val second = inspector.tree().flatten().map { it.id }

        assertEquals(first.size, first.toSet().size)
        assertEquals(first, second)
        assertEquals(root.id, inspector.tree().id)
    }

    @Test
    fun `testTag is reported for tagged nodes only`() {
        root.add(GtkButtonNode("Send"), Modifier.testTag("send"))
        root.add(GtkLabelNode())

        val tree = inspector.tree()

        assertEquals("send", tree.children[0].testTag)
        assertNull(tree.children[1].testTag)
        assertNull(tree.testTag)
    }

    @Test
    fun `text is reported for labels buttons and entries`() {
        val label = root.add(GtkLabelNode())
        label.setText("Title")
        root.add(GtkButtonNode("Save"))
        val entry = root.add(GtkEntryNode())
        entry.setText("draft")
        root.add(GtkSwitchNode())

        val children = inspector.tree().children

        assertEquals("Title", children[0].text)
        assertEquals("Save", children[1].text)
        assertEquals("draft", children[2].text)
        assertNull(children[3].text)
        assertNull(inspector.tree().text)
    }

    @Test
    fun `checked is reported for switches`() {
        val switch = root.add(GtkSwitchNode())
        switch.setChecked(true)
        root.add(GtkLabelNode())

        val children = inspector.tree().children

        assertEquals(true, children[0].checked)
        assertNull(children[1].checked)
    }

    @Test
    fun `value is reported for sliders`() {
        val slider = root.add(GtkSliderNode(0f, 1f, 0.25f))
        slider.setValue(0.75f)

        assertEquals(0.75f, inspector.tree().children.single().value)
    }

    @Test
    fun `selected and options are reported for drop downs`() {
        val dropDown = root.add(GtkDropDownNode())
        dropDown.setItems(listOf("a", "b", "c"))
        dropDown.setSelected(2)

        val node = inspector.tree().children.single()

        assertEquals(2, node.selected)
        assertEquals(listOf("a", "b", "c"), node.options)
    }

    @Test
    fun `sensitive and visible follow the widget state`() {
        val button = root.add(GtkButtonNode("Save"))
        gtk_widget_set_sensitive(button.widget, 0)
        val label = root.add(GtkLabelNode())
        gtk_widget_set_visible(label.widget, 0)

        val children = inspector.tree().children

        assertFalse(children[0].sensitive)
        assertTrue(children[0].visible)
        assertTrue(children[1].sensitive)
        assertFalse(children[1].visible)
    }

    @Test
    fun `tree fails when no window is mounted`() {
        val empty = Inspector(root = { null }, idle = {})

        assertFailsWith<InspectException> { empty.tree() }
    }

    // --- find ---

    @Test
    fun `find returns the node named by its id`() {
        val button = root.add(GtkButtonNode("Save"))

        val found = inspector.find(button.id.toString())

        assertEquals(button.id, found.id)
        assertEquals("Button", found.type)
    }

    @Test
    fun `find returns the node named by its testTag`() {
        root.add(GtkLabelNode())
        root.add(GtkButtonNode("Save"), Modifier.testTag("save"))

        val found = inspector.find("save")

        assertEquals("Button", found.type)
        assertEquals("save", found.testTag)
    }

    @Test
    fun `find fails for a missing target`() {
        root.add(GtkLabelNode())

        assertFailsWith<InspectException> { inspector.find("missing") }
        assertFailsWith<InspectException> { inspector.find("9999") }
    }

    @Test
    fun `find fails for a testTag shared by several nodes`() {
        root.add(GtkLabelNode(), Modifier.testTag("dup"))
        root.add(GtkLabelNode(), Modifier.testTag("dup"))

        assertFailsWith<InspectException> { inspector.find("dup") }
    }

    // --- click ---

    @Test
    fun `click fires the handler of a text button`() {
        var clicks = 0
        val button = root.add(GtkButtonNode("Save"))
        button.onClick = { clicks++ }

        inspector.click(button.id)

        assertEquals(1, clicks)
    }

    @Test
    fun `click fires the handler of a container button`() {
        var clicks = 0
        val button = root.add(GtkButtonContainerNode())
        button.onClick = { clicks++ }
        button.add(GtkLabelNode())

        inspector.click(button.id)

        assertEquals(1, clicks)
    }

    @Test
    fun `click fires the Modifier onClick handler of any widget`() {
        var clicks = 0
        val label = root.add(GtkLabelNode(), Modifier.onClick { clicks++ })

        inspector.click(label.id)

        assertEquals(1, clicks)
    }

    @Test
    fun `click fails on a widget without a click handler`() {
        val label = root.add(GtkLabelNode())

        assertFailsWith<InspectException> { inspector.click(label.id) }
    }

    // --- value setters ---

    @Test
    fun `setText fires onChange of an entry`() {
        val seen = mutableListOf<String>()
        val entry = root.add(GtkEntryNode())
        entry.onChange = { seen += it }

        inspector.setText(entry.id, "hi")

        assertEquals("hi", seen.last())
        assertEquals("hi", inspector.tree().children.single().text)
    }

    @Test
    fun `setChecked fires onChange of a switch`() {
        val seen = mutableListOf<Boolean>()
        val switch = root.add(GtkSwitchNode())
        switch.onChange = { seen += it }

        inspector.setChecked(switch.id, true)
        assertEquals(true, seen.last())
        assertEquals(true, inspector.tree().children.single().checked)

        inspector.setChecked(switch.id, false)
        assertEquals(false, seen.last())
    }

    @Test
    fun `setValue fires onChange of a slider`() {
        val seen = mutableListOf<Float>()
        val slider = root.add(GtkSliderNode(0f, 1f, 0.25f))
        slider.onChange = { seen += it }

        inspector.setValue(slider.id, 0.5f)

        assertEquals(0.5f, seen.last())
        assertEquals(0.5f, inspector.tree().children.single().value)
    }

    @Test
    fun `select fires onChange of a drop down`() {
        val seen = mutableListOf<Int>()
        val dropDown = root.add(GtkDropDownNode())
        dropDown.setItems(listOf("a", "b", "c"))
        dropDown.onChange = { seen += it }

        inspector.select(dropDown.id, 2)

        assertEquals(2, seen.last())
        assertEquals(2, inspector.tree().children.single().selected)
    }

    // --- rejected actions ---

    @Test
    fun `setters reject a node of the wrong type`() {
        val label = root.add(GtkLabelNode())
        val button = root.add(GtkButtonNode("Save"))
        val entry = root.add(GtkEntryNode())
        val slider = root.add(GtkSliderNode(0f, 1f, 0.25f))

        assertFailsWith<InspectException> { inspector.setText(label.id, "x") }
        assertFailsWith<InspectException> { inspector.setChecked(button.id, true) }
        assertFailsWith<InspectException> { inspector.setValue(entry.id, 0.5f) }
        assertFailsWith<InspectException> { inspector.select(slider.id, 0) }
    }

    @Test
    fun `setValue rejects values outside the slider range`() {
        val slider = root.add(GtkSliderNode(0f, 1f, 0.25f))
        slider.setValue(0.25f)

        assertFailsWith<InspectException> { inspector.setValue(slider.id, 2f) }
        assertFailsWith<InspectException> { inspector.setValue(slider.id, -0.5f) }
        assertFailsWith<InspectException> { inspector.setValue(slider.id, Float.NaN) }
        assertEquals(0.25f, inspector.tree().children.single().value)
    }

    @Test
    fun `select rejects indices outside the options`() {
        val dropDown = root.add(GtkDropDownNode())
        dropDown.setItems(listOf("a", "b", "c"))
        dropDown.setSelected(1)

        assertFailsWith<InspectException> { inspector.select(dropDown.id, 3) }
        assertFailsWith<InspectException> { inspector.select(dropDown.id, -1) }
        assertEquals(1, inspector.tree().children.single().selected)
    }

    @Test
    fun `actions fail on an insensitive node`() {
        val entry = root.add(GtkEntryNode())
        entry.setText("draft")
        val button = root.add(GtkButtonNode("Save"))
        button.onClick = { error("an insensitive button must not fire") }
        gtk_widget_set_sensitive(entry.widget, 0)
        gtk_widget_set_sensitive(button.widget, 0)

        assertFailsWith<InspectException> { inspector.setText(entry.id, "x") }
        assertFailsWith<InspectException> { inspector.click(button.id) }
        assertFailsWith<InspectException> { inspector.setState(button.id, InteractionState.Hover, true) }
        assertEquals("draft", inspector.tree().children[0].text)
    }

    @Test
    fun `actions fail on an invisible node`() {
        val button = root.add(GtkButtonNode("Save"))
        button.onClick = { error("a hidden button must not fire") }
        gtk_widget_set_visible(button.widget, 0)

        assertFailsWith<InspectException> { inspector.click(button.id) }
        assertFailsWith<InspectException> { inspector.setState(button.id, InteractionState.Focus, true) }
    }

    @Test
    fun `actions fail on an unknown id`() {
        root.add(GtkButtonNode("Save"))

        assertFailsWith<InspectException> { inspector.click(9999) }
        assertFailsWith<InspectException> { inspector.setText(9999, "x") }
        assertFailsWith<InspectException> { inspector.setState(9999, InteractionState.Hover, true) }
    }

    // --- setState ---

    @Test
    fun `setState sets and clears the hover flag`() {
        val button = root.add(GtkButtonNode("Save"))

        inspector.setState(button.id, InteractionState.Hover, true)
        assertTrue(hasFlag(button, GTK_STATE_FLAG_PRELIGHT.toInt()))

        inspector.setState(button.id, InteractionState.Hover, false)
        assertFalse(hasFlag(button, GTK_STATE_FLAG_PRELIGHT.toInt()))
    }

    @Test
    fun `setState sets and clears the pressed flag`() {
        val button = root.add(GtkButtonNode("Save"))

        inspector.setState(button.id, InteractionState.Pressed, true)
        assertTrue(hasFlag(button, GTK_STATE_FLAG_ACTIVE.toInt()))

        inspector.setState(button.id, InteractionState.Pressed, false)
        assertFalse(hasFlag(button, GTK_STATE_FLAG_ACTIVE.toInt()))
    }

    @Test
    fun `setState sets and clears the focus flags`() {
        val button = root.add(GtkButtonNode("Save"))

        inspector.setState(button.id, InteractionState.Focus, true)
        assertTrue(hasFlag(button, GTK_STATE_FLAG_FOCUSED.toInt()))
        assertTrue(hasFlag(button, GTK_STATE_FLAG_FOCUS_VISIBLE.toInt()))

        inspector.setState(button.id, InteractionState.Focus, false)
        assertFalse(hasFlag(button, GTK_STATE_FLAG_FOCUSED.toInt()))
        assertFalse(hasFlag(button, GTK_STATE_FLAG_FOCUS_VISIBLE.toInt()))
    }

    @Test
    fun `clearing one state leaves the others set`() {
        val button = root.add(GtkButtonNode("Save"))
        inspector.setState(button.id, InteractionState.Hover, true)
        inspector.setState(button.id, InteractionState.Pressed, true)

        inspector.setState(button.id, InteractionState.Pressed, false)

        assertTrue(hasFlag(button, GTK_STATE_FLAG_PRELIGHT.toInt()))
        assertFalse(hasFlag(button, GTK_STATE_FLAG_ACTIVE.toInt()))
    }

    // --- transitions ---

    @Test
    fun `disableUiTransitions turns GTK animations off`() {
        val before = animationsEnabled()

        disableUiTransitions()

        assertFalse(animationsEnabled())
        setAnimationsEnabled(before)
    }

    // --- helpers ---

    private fun animationsEnabled(): Boolean = memScoped {
        val value = alloc<GValue>()
        g_value_init(value.ptr, g_type_from_name("gboolean"))
        g_object_get_property(gtk_settings_get_default()!!.reinterpret(), "gtk-enable-animations", value.ptr)
        val enabled = g_value_get_boolean(value.ptr) != 0
        g_value_unset(value.ptr)
        enabled
    }

    private fun setAnimationsEnabled(enabled: Boolean) = memScoped {
        val value = alloc<GValue>()
        g_value_init(value.ptr, g_type_from_name("gboolean"))
        g_value_set_boolean(value.ptr, if (enabled) 1 else 0)
        g_object_set_property(gtk_settings_get_default()!!.reinterpret(), "gtk-enable-animations", value.ptr)
        g_value_unset(value.ptr)
    }

    private fun newBox(): GtkBoxNode =
        GtkBoxNode(gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!)

    /** Appends [child] to this node with [modifier] applied, as the Compose applier would. */
    private fun <T : GtkNode> GtkNode.add(child: T, modifier: Modifier = Modifier): T {
        child.applyModifier(modifier)
        insertChild(children.size, child)
        return child
    }

    private fun hasFlag(node: GtkNode, flag: Int): Boolean =
        (gtk_widget_get_state_flags(node.widget).toInt() and flag) != 0

    private fun InspectNode.flatten(): List<InspectNode> = listOf(this) + children.flatMap { it.flatten() }
}
