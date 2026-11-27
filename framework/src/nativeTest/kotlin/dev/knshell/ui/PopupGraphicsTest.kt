@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.OverlayHost
import dev.knshell.compose.Portal
import dev.knshell.compose.Stack
import dev.knshell.compose.focusable
import dev.knshell.compose.testTag
import dev.knshell.shell.Popup
import dev.knshell.shell.ShellRuntime
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kn.gtk.GtkLabel
import kn.gtk.GtkWidget
import kn.gtk.gtk_label_get_text
import kn.gtk.gtk_widget_get_first_child
import kn.gtk.gtk_widget_get_next_sibling
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val LocalWord = compositionLocalOf { "default" }

class PopupGraphicsTest {

    @Test
    fun `a popup shows the locals of its place is inspectable and closes with Escape`() {
        var open by mutableStateOf(false)
        var word by mutableStateOf("provided")
        uiTest({
            CompositionLocalProvider(LocalWord provides word) {
                Popup(
                    open = open,
                    onDismiss = { open = false },
                    content = {
                        Box(Modifier.testTag("inside").focusable()) {
                            Label(LocalWord.current, modifier = Modifier.testTag("word"))
                        }
                    },
                ) {
                    Box(Modifier.testTag("trigger").focusable(autofocus = true)) { Label("open") }
                }
            }
        }) {
            assertFalse(exists("word"))
            open = true
            settle(400)

            assertEquals("provided", node("word").text)
            assertEquals(listOf("inside"), focused())
            assertTrue(inspector.screenshot(node("inside").id).isNotEmpty())

            word = "changed"
            settle()
            assertEquals("changed", node("word").text)

            keyboard.press(Key.Escape)
            settle(300)
            assertFalse(open)
            assertFalse(exists("word"))
            assertEquals(listOf("trigger"), focused())
        }
    }

    @Test
    fun `a portal is drawn in the overlay host with the locals of its place`() {
        var shown by mutableStateOf(false)
        uiTest({
            OverlayHost {
                Column {
                    CompositionLocalProvider(LocalWord provides "from inside") {
                        Label("base")
                        if (shown) Portal { Label(LocalWord.current, modifier = Modifier.testTag("layer")) }
                    }
                }
            }
        }) {
            shown = true
            settle()
            assertEquals("from inside", node("layer").text)
            shown = false
            settle()
            assertFalse(exists("layer"))
        }
    }

    @Test
    fun `a child composed later still paints at its place in a Stack`() {
        var first by mutableStateOf(false)
        uiTest({
            Stack {
                if (first) Label("below")
                Label("above")
            }
        }) {
            first = true
            settle()

            val stack = ShellRuntime.instance().firstWindowRoot()!!.children.single().widget
            assertEquals(listOf("below", "above"), labels(stack))
        }
    }

    private fun labels(parent: CPointer<GtkWidget>): List<String> =
        generateSequence(gtk_widget_get_first_child(parent)) { gtk_widget_get_next_sibling(it) }
            .map { gtk_label_get_text(it.reinterpret<GtkLabel>())!!.toKString() }
            .toList()
}
