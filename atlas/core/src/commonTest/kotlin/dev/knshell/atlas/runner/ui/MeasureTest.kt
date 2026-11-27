package dev.knshell.atlas.runner.ui

import dev.knshell.inspect.InspectNode
import dev.knshell.inspect.NodeBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MeasureTest {

    private var ids = 0

    private fun node(tag: String?, x: Int, y: Int, width: Int, height: Int, vararg children: InspectNode) =
        InspectNode(
            id = ++ids,
            type = "Box",
            testTag = tag,
            bounds = NodeBounds(x, y, width, height),
            cssClasses = emptyList(),
            text = null,
            checked = null,
            value = null,
            selected = null,
            options = null,
            visible = true,
            sensitive = true,
            children = children.toList(),
        )

    // The preview starts at (100, 50); a story at (200, 100) holds a button with a label in it.
    private val tree = node(
        null, 0, 0, 800, 600,
        node("sidebar", 0, 0, 100, 600),
        node("measure:capture", 100, 50, 700, 550),
        node(
            STORY_CONTENT, 200, 100, 300, 200,
            node("button", 210, 110, 80, 30, node("label", 220, 115, 60, 20)),
        ),
    )

    private fun at(x: Int, y: Int) = measureAt(tree, x, y)

    @Test
    fun `the smallest widget under the pointer is measured`() {
        val label = at(130, 70)!!
        assertEquals("label", label.node.testTag)
        assertEquals(NodeBounds(120, 65, 60, 20), label.inPreview)
        assertEquals(20 to 15, label.inStory)

        assertEquals("button", at(115, 62)!!.node.testTag)
    }

    @Test
    fun `nothing outside the stories is measured`() {
        assertNull(at(5, 5))
        assertNull(at(600, 500))
    }

    @Test
    fun `the card lists the box and what the widget is`() {
        val lines = measureLines(at(115, 62)!!).toMap()

        assertEquals("80 × 30", lines["розмір"])
        assertEquals("10, 10", lines["позиція"])
        assertEquals("button", lines["testTag"])
    }
}
