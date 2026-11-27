package dev.knshell.ui


import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.FocusRequester
import dev.knshell.compose.FocusScope
import dev.knshell.compose.Key
import dev.knshell.compose.KeyEvent
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.focusRequester
import dev.knshell.compose.focusable
import dev.knshell.compose.onFocusChange
import dev.knshell.compose.onKey
import dev.knshell.compose.testTag
import kotlin.test.Test
import kotlin.test.assertEquals

class FocusGraphicsTest {

    @Test
    fun `keys reach the focused widget and its ancestors and focus moves from code`() {
        val seen = mutableListOf<KeyEvent>()
        val ownKeys = mutableListOf<Key>()
        uiTest({
            // Roving focus: one tab stop, arrows move it.
            var current by remember { mutableStateOf(0) }
            val requesters = remember { List(3) { FocusRequester() } }
            Column(modifier = Modifier.onKey { event -> seen.add(event); false }) {
                for (index in 0..2) {
                    Box(
                        Modifier.testTag("item$index")
                            .focusRequester(requesters[index])
                            .focusable(index == current, autofocus = index == 0)
                            .onKey { event ->
                                ownKeys += event.key
                                val step = when (event.key) {
                                    Key.Down -> 1
                                    Key.Up -> -1
                                    else -> return@onKey false
                                }
                                current = (current + step + 3) % 3
                                requesters[current].requestFocus()
                                true
                            },
                    ) { Label("item $index") }
                }
            }
        }) {
            assertEquals(listOf("item0"), focused())

            keyboard.press(Key.Down)
            settle()
            assertEquals(listOf("item1"), focused())

            keyboard.press(Key.Up)
            settle()
            keyboard.press(Key.Up)
            settle()
            assertEquals(listOf("item2"), focused())

            seen.clear()
            keyboard.press(Key.Home, shift = true)
            keyboard.type("a")
            settle()
            assertEquals(listOf(Key.Home, Key.Character), seen.map { it.key })
            assertEquals(true, seen.first().shift)
            assertEquals('a', seen.last().character)
        }
    }

    @Test
    fun `a trapping scope keeps Tab inside and gives the focus back`() {
        var open by mutableStateOf(false)
        val inside = mutableListOf<Boolean>()
        uiTest({
            Column {
                Box(Modifier.testTag("outside").focusable(autofocus = true)) { Label("outside") }
                if (open) {
                    FocusScope(modifier = Modifier.onFocusChange { inside.add(it) }, trap = true) {
                        Box(Modifier.testTag("first").focusable()) { Label("first") }
                        Box(Modifier.testTag("second").focusable()) { Label("second") }
                    }
                }
            }
        }) {
            assertEquals(listOf("outside"), focused())
            open = true
            settle(300)
            assertEquals(listOf("first"), focused())

            val visited = (1..4).map {
                keyboard.press(Key.Tab)
                settle()
                focused().single()
            }
            assertEquals(listOf("second", "first", "second", "first"), visited)
            keyboard.press(Key.Tab, shift = true)
            settle()
            assertEquals(listOf("second"), focused())

            open = false
            settle(300)
            assertEquals(listOf("outside"), focused())
            assertEquals(true, inside.first())
        }
    }

    @Test
    fun `Tab stops on a focusable container on the way in and on the way back`() = uiTest({
        Column {
            Box(Modifier.testTag("before").focusable(autofocus = true)) { Label("before") }
            Column(modifier = Modifier.testTag("panel").focusable()) {
                Label("text")
                Box(Modifier.testTag("inner").focusable()) { Label("inner") }
            }
            Box(Modifier.testTag("after").focusable()) { Label("after") }
        }
    }) {
        fun tab(shift: Boolean = false): List<String> {
            keyboard.press(Key.Tab, shift = shift)
            settle()
            return focused()
        }

        assertEquals(
            listOf(listOf("panel"), listOf("inner"), listOf("after")),
            listOf(tab(), tab(), tab()),
        )
        assertEquals(
            listOf(listOf("inner"), listOf("panel"), listOf("before")),
            listOf(tab(shift = true), tab(shift = true), tab(shift = true)),
        )
    }

    @Test
    fun `moveFocus goes on from a widget and a scope reports Tab past its ends`() {
        val from = FocusRequester()
        val left = mutableListOf<Boolean>()
        uiTest({
            Column {
                Box(Modifier.testTag("before").focusable(autofocus = true)) { Label("before") }
                Box(Modifier.testTag("trigger").focusRequester(from).focusable()) { Label("trigger") }
                FocusScope(autoFocus = false, restoreFocus = false, onTabOut = { left.add(it) }) {
                    Box(Modifier.testTag("first").focusable()) { Label("first") }
                    Box(Modifier.testTag("last").focusable()) { Label("last") }
                }
            }
        }) {
            from.moveFocus()
            settle()
            assertEquals(listOf("first"), focused())
            keyboard.press(Key.Tab)
            settle()
            assertEquals(listOf("last"), focused())
            assertEquals(emptyList(), left)

            keyboard.press(Key.Tab)
            settle()
            assertEquals(listOf("last"), focused())
            keyboard.press(Key.Tab, shift = true)
            keyboard.press(Key.Tab, shift = true)
            settle()
            assertEquals(listOf(false, true), left)

            from.moveFocus(backwards = true)
            settle()
            assertEquals(listOf("before"), focused())
        }
    }

    @Test
    fun `a key says which physical key it was`() {
        val seen = mutableListOf<KeyEvent>()
        uiTest({
            Box(Modifier.testTag("only").focusable(autofocus = true).onKey { seen.add(it) }) { Label("only") }
        }) {
            keyboard.chord('k', ctrl = true)
            keyboard.type("a.")
            settle()
            assertEquals(listOf('k', 'a', '.'), seen.filter { it.key == Key.Character }.map { it.latin })
            assertEquals(true, seen.first { it.latin == 'k' }.ctrl)
        }
    }
}
