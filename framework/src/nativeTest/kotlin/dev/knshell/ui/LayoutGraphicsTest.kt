package dev.knshell.ui

import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Button
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.align
import dev.knshell.compose.height
import dev.knshell.compose.hExpand
import dev.knshell.compose.size
import dev.knshell.compose.testTag
import dev.knshell.compose.vExpand
import dev.knshell.compose.width
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayoutGraphicsTest {

    @Test
    fun `layout modifiers of a chain add up`() = uiTest({
        Column(modifier = Modifier.size(300, 200).align(h = Align.Start, v = Align.Start)) {
            Box(Modifier.testTag("sized").width(40).height(12).align(h = Align.End).align(v = Align.Center))
            Box(Modifier.testTag("grown").hExpand().vExpand())
        }
    }) {
        val sized = node("sized").bounds
        assertEquals(40 to 12, sized.width to sized.height)
        assertEquals(260, sized.x - node("grown").bounds.x)
        assertEquals(300, node("grown").bounds.width)
        assertTrue(node("grown").bounds.height > 100)
    }

    @Test
    fun `the only child of a button gets its width and can centre itself in it`() = uiTest({
        Column(modifier = Modifier.size(300, 200).align(h = Align.Start, v = Align.Start)) {
            Button(Modifier.testTag("button").hExpand(), onClick = {}) {
                Label("ok", modifier = Modifier.testTag("label").align(h = Align.Center))
            }
        }
    }) {
        val button = node("button").bounds
        val label = node("label").bounds
        val left = label.x - button.x
        val right = button.x + button.width - label.x - label.width
        assertTrue(button.width == 300 && kotlin.math.abs(left - right) <= 1, "left $left, right $right in $button")
    }
}
