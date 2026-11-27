package dev.knshell.atlas.play

import dev.knshell.compose.Key
import dev.knshell.inspect.InspectException
import dev.knshell.inspect.InspectNode
import dev.knshell.inspect.InteractionState
import dev.knshell.inspect.NodeBounds
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayScopeTest {

    /** A tree of two tabs; pressing Right moves the selection to the second. */
    private class FakeDriver : PlayDriver {
        val log = mutableListOf<String>()
        private var selected = 1

        override fun tree(): InspectNode = node(1, null, children = listOf(tab(2, "a", 1), tab(3, "b", 2)))

        private fun tab(id: Int, tag: String, index: Int) = node(
            id, tag, role = "tab", states = mapOf("selected" to (selected == index).toString()),
        )

        override suspend fun settle() { log += "settle" }
        override fun click(id: Int) { log += "click $id" }
        override fun focus(id: Int) = throw InspectException("node #$id takes no focus")
        override fun setState(id: Int, state: InteractionState, on: Boolean) { log += "$state $id $on" }
        override fun setText(id: Int, text: String) { log += "text $id $text" }
        override fun press(key: Key, shift: Boolean, ctrl: Boolean, alt: Boolean, meta: Boolean) {
            log += "press $key"
            if (key == Key.Right) selected = 2
        }
        override fun type(text: String) { log += "type $text" }
        override suspend fun snapshot(name: String, id: Int?) { log += "snapshot $name ${id ?: "stage"}" }
    }

    private val driver = FakeDriver()

    private fun play(script: Play) = runBlocking { with(script) { PlayScope(driver).run() } }

    private fun failure(script: Play): String = assertFailsWith<PlayFailure> { play(script) }.message!!

    @Test
    fun `every action settles before the script goes on`() {
        play {
            click("a")
            press(Key.Right)
            expect("b").role("tab").selected()
            expect("a").selected(false)
            hover("b")
            snapshot("second")
            expectAbsent("c")
        }

        assertEquals(
            listOf("click 2", "settle", "press Right", "settle", "Hover 3 true", "settle", "snapshot second stage"),
            driver.log,
        )
    }

    @Test
    fun `a failed expectation names the step before it`() {
        val message = failure {
            press(Key.Left)
            expect("b").selected()
        }

        assertEquals("after step 1 (press Left): b: expected selected true, got false", message)
    }

    @Test
    fun `an unknown tag and a refused action fail the script`() {
        assertContains(failure { click("nope") }, "nothing is tagged nope; tags: a, b")
        assertContains(failure { focus("a") }, "takes no focus")
        assertContains(failure { expectAbsent("a") }, "expected no a")
    }
}

private fun node(
    id: Int,
    tag: String?,
    role: String = "group",
    states: Map<String, String> = emptyMap(),
    children: List<InspectNode> = emptyList(),
): InspectNode = InspectNode(
    id = id,
    type = "Box",
    testTag = tag,
    bounds = NodeBounds(0, 0, 1, 1),
    cssClasses = emptyList(),
    text = null,
    checked = null,
    value = null,
    selected = null,
    options = null,
    visible = true,
    sensitive = true,
    children = children,
    role = role,
    states = states,
)
