@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Semantics
import dev.knshell.compose.SemanticsRef
import dev.knshell.compose.Tristate
import dev.knshell.compose.semantics
import dev.knshell.compose.semanticsRef
import dev.knshell.compose.testTag
import dev.knshell.inspect.InspectNode
import dev.knshell.shell.ShellRuntime
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kn.gtk.GtkAccessibleProperty
import kn.gtk.GtkAccessibleRelation
import kn.gtk.GtkAccessibleState
import kn.gtk.GtkAccessibleTristate
import kn.gtk.gtk_test_accessible_check_state
import kotlinx.cinterop.toKString
import kn.gtk.gtk_test_accessible_has_property
import kn.gtk.gtk_test_accessible_has_relation
import kn.gtk.gtk_test_accessible_has_state
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What GTK is told about widgets, read both from GTK and from the inspector. */
class AccessibilityGraphicsTest {

    private var selected by mutableStateOf(true)
    private var described by mutableStateOf(true)
    private val title = SemanticsRef()
    private val subject = SemanticsRef()

    private fun content(): @androidx.compose.runtime.Composable () -> Unit = {
        Column {
            Label("Sound", modifier = Modifier.semanticsRef(title))
            Box(
                Modifier.testTag("tab").semanticsRef(subject).semantics(
                    Semantics(
                        role = Role.Tab,
                        selected = selected,
                        checked = Tristate.Mixed,
                        expanded = false,
                        valueNow = 4.0,
                        valueText = "four",
                        labelledBy = title,
                        description = "Volume and devices".takeIf { described },
                    ),
                ),
            )
            Box(Modifier.testTag("button").semantics(Semantics(role = Role.Button))) { Label("Save") }
        }
    }

    private fun gtk() = ShellRuntime.instance().firstWindowRoot()!!.children.first().children[1].widget.reinterpret<kn.gtk.GtkAccessible>()

    private fun hasState(state: GtkAccessibleState) = gtk_test_accessible_has_state(gtk(), state) != 0

    private fun hasProperty(property: GtkAccessibleProperty) = gtk_test_accessible_has_property(gtk(), property) != 0

    private val InspectNode.summary get() = Triple(role, name, states["selected"])

    @Test
    fun `role name states and relations reach gtk and the inspector`() = uiTest(content()) {
        assertEquals(Triple("tab", "Sound", "true"), node("tab").summary)
        assertEquals("mixed", node("tab").states["checked"])
        assertEquals("Volume and devices", node("tab").description)
        assertEquals("button" to "Save", node("button").role to node("button").name)
        assertTrue(hasState(GtkAccessibleState.GTK_ACCESSIBLE_STATE_SELECTED))
        assertTrue(hasState(GtkAccessibleState.GTK_ACCESSIBLE_STATE_CHECKED))
        assertTrue(hasProperty(GtkAccessibleProperty.GTK_ACCESSIBLE_PROPERTY_VALUE_NOW))
        assertTrue(hasProperty(GtkAccessibleProperty.GTK_ACCESSIBLE_PROPERTY_DESCRIPTION))
        assertTrue(
            gtk_test_accessible_has_relation(gtk(), GtkAccessibleRelation.GTK_ACCESSIBLE_RELATION_LABELLED_BY) != 0,
        )
        assertEquals(
            null,
            mismatch(GtkAccessibleState.GTK_ACCESSIBLE_STATE_CHECKED, GtkAccessibleTristate.GTK_ACCESSIBLE_TRISTATE_MIXED.value.toInt()),
        )
        assertEquals(null, mismatch(GtkAccessibleState.GTK_ACCESSIBLE_STATE_SELECTED, 1))
    }

    /** `null` when GTK holds [expected] for [state], else GTK's description of what it holds. */
    private fun mismatch(state: GtkAccessibleState, expected: Int): String? =
        gtk_test_accessible_check_state(gtk(), state, expected)?.toKString()

    @Test
    fun `a change updates the state and a dropped field is taken back`() = uiTest(content()) {
        selected = false
        described = false
        settle()

        assertEquals("false", node("tab").states["selected"])
        assertFalse(hasProperty(GtkAccessibleProperty.GTK_ACCESSIBLE_PROPERTY_DESCRIPTION))
        selected = true
        described = true
    }
}
