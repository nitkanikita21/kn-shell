package dev.knshell.atlas.runner.ui

import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.runner.AtlasHostService
import dev.knshell.atlas.runner.AtlasWindow
import dev.knshell.atlas.runner.golden.GoldenController
import dev.knshell.atlas.runner.shortcuts
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.closeComposeWindows
import dev.knshell.compose.launchOnUiThread
import dev.knshell.compose.runComposeWindow
import dev.knshell.inspect.InspectNode
import dev.knshell.inspect.Inspector
import dev.knshell.inspect.VirtualKeyboard
import dev.knshell.inspect.disableUiTransitions
import dev.knshell.inspect.windowInspector
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals

/** A running atlas window driven through the inspector and a real keyboard. */
class AtlasUi(val service: AtlasHostService, val inspector: Inspector, val keyboard: VirtualKeyboard) {

    private fun InspectNode.all(): List<InspectNode> = listOf(this) + children.flatMap { it.all() }

    fun nodes(): List<InspectNode> = inspector.tree().all()

    fun find(tag: String): InspectNode? = nodes().firstOrNull { it.testTag == tag }

    fun node(tag: String): InspectNode =
        find(tag) ?: error("nothing is tagged $tag: ${nodes().mapNotNull { it.testTag }}")

    /** The tagged widgets that have the focus. */
    fun focused(): List<String> = nodes().filter { it.focused }.mapNotNull { it.testTag }

    val selected: String
        get() = service.selection.value!!.let { "${it.group.title}/${it.entry.name}" }

    /** Time for key presses to arrive and the window to follow. */
    suspend fun settle() {
        delay(80)
        inspector.awaitIdle()
    }
}

/** Runs [script] against an atlas showing [catalog] and closes it afterwards. */
fun atlasUiTest(catalog: List<StoryGroup>, script: suspend AtlasUi.() -> Unit) {
    val service = AtlasHostService(catalog)
    service.select(catalog.first(), catalog.first().entries.first())
    val golden = GoldenController(config = { null }, binaryStamp = "", ownBinary = "")
    var failure: Throwable? = null
    disableUiTransitions()

    launchOnUiThread {
        try {
            val keyboard = checkNotNull(VirtualKeyboard.fromEnvironment()) { "run under headless-compositor" }
            val ui = AtlasUi(service, windowInspector(), keyboard)
            ui.settle()
            delay(300)
            ui.script()
        } catch (e: Throwable) {
            failure = e
        } finally {
            closeComposeWindows()
        }
    }
    runComposeWindow("atlas ui test", width = 1200, height = 800, windowModifier = shortcuts(service)) {
        AtlasWindow(service, golden)
    }
    failure?.let { throw it }
}

private val catalog = listOf(
    group("Button", category = "Element") {
        render { _ -> Label("button") }
        story("Playground")
        story("Keyboard")
    },
    group("Input", category = "Form") {
        render { _ -> Label("input") }
        story("Playground")
        story("Keyboard")
    },
)

class AtlasWindowGraphicsTest {

    @Test
    fun `the search is reached and walked with the keyboard`() = atlasUiTest(catalog) {
        keyboard.chord('k', ctrl = true)
        settle()
        assertEquals(listOf("search"), focused())

        keyboard.type("keyb")
        settle()
        assertEquals("знайдено: 2", node("tree:count").text)
        assertEquals(null, find("story:Button/Playground"))

        keyboard.press(Key.Down)
        keyboard.press(Key.Enter)
        settle()
        assertEquals("Input/Keyboard", selected)

        keyboard.press(Key.Escape)
        settle()
        assertEquals(null, find("tree:count"))
        assertEquals(listOf("search"), focused())
        keyboard.press(Key.Escape)
        settle()
        assertEquals(listOf("preview"), focused())

        keyboard.type("/")
        settle()
        assertEquals(listOf("search"), focused())
        // Typed into the field, the same key is text.
        keyboard.type("/")
        settle()
        assertEquals("/", node("search").text)
    }

    @Test
    fun `groups close at once and a search with no match says so`() = atlasUiTest(catalog) {
        inspector.click(node("tree:fold").id)
        settle()
        assertEquals(null, find("story:Button/Playground"))

        keyboard.chord('k', ctrl = true)
        settle()
        keyboard.type("zzz")
        settle()
        assertEquals("Нічого не знайдено за «zzz».", node("search:empty").text)
        inspector.click(node("search:reset").id)
        settle()
        assertEquals(null, find("story:Button/Playground"))
    }
}
