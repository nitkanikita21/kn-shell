// ---------------------------------------------------------------------------
// Measuring: point at a widget of the story and see its box and what it is.
// ---------------------------------------------------------------------------

package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.atlas.runner.ViewState
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.border
import dev.knshell.compose.expand
import dev.knshell.compose.margins
import dev.knshell.compose.minWidth
import dev.knshell.compose.onHover
import dev.knshell.compose.onPointerMove
import dev.knshell.compose.padding
import dev.knshell.compose.size
import dev.knshell.compose.testTag
import dev.knshell.compose.textColor
import dev.knshell.inspect.InspectException
import dev.knshell.inspect.InspectNode
import dev.knshell.inspect.NodeBounds
import dev.knshell.inspect.windowInspector
import kotlin.math.roundToInt

/** Marks the container of a story in the preview; measuring looks only inside these. */
internal const val STORY_CONTENT = "story-content"

private const val CAPTURE = "measure:capture"

/** A widget under the pointer, placed relative to the preview and to its story. */
internal class Measured(val node: InspectNode, val inPreview: NodeBounds, val inStory: Pair<Int, Int>)

/**
 * The widget of a story at ([x], [y]) in the coordinates of the node tagged [capture]: the smallest
 * one there, so that a part wins over what holds it.
 */
internal fun measureAt(tree: InspectNode, x: Int, y: Int, capture: String = CAPTURE): Measured? {
    fun all(node: InspectNode): List<InspectNode> = listOf(node) + node.children.flatMap(::all)
    val origin = all(tree).firstOrNull { it.testTag == capture }?.bounds ?: return null
    val px = origin.x + x
    val py = origin.y + y
    fun NodeBounds.holds() = px >= this.x && px < this.x + width && py >= this.y && py < this.y + height

    val found = all(tree).filter { it.testTag == STORY_CONTENT }
        .flatMap { story -> story.children.flatMap(::all).map { story to it } }
        .filter { (_, node) -> node.visible && node.bounds.holds() }
        // Of two widgets of one size the later is the inner one.
        .asReversed()
        .minByOrNull { (_, node) -> node.bounds.width * node.bounds.height }
        ?: return null
    val (story, node) = found
    val bounds = node.bounds
    return Measured(
        node = node,
        inPreview = NodeBounds(bounds.x - origin.x, bounds.y - origin.y, bounds.width, bounds.height),
        inStory = (bounds.x - story.bounds.x) to (bounds.y - story.bounds.y),
    )
}

/** What the card says about a widget. */
internal fun measureLines(found: Measured): List<Pair<String, String>> = listOfNotNull(
    "тип" to found.node.type,
    found.node.testTag?.let { "testTag" to it },
    "розмір" to "${found.node.bounds.width} × ${found.node.bounds.height}",
    "позиція" to "${found.inStory.first}, ${found.inStory.second}",
    "роль" to found.node.role,
    found.node.name?.let { "назва" to it },
)

/** Room the card takes below a widget, and its width, to keep it inside the preview. */
private const val CARD_HEIGHT = 130
private const val CARD_WIDTH = 220
private const val CARD_GAP = 6

/**
 * Lies over the preview while measuring: it takes the pointer, outlines the widget under it and
 * shows a card beside it.
 */
@Composable
internal fun MeasureOverlay(view: ViewState) {
    val inspector = remember { windowInspector() }
    var found by remember { mutableStateOf<Measured?>(null) }
    var area by remember { mutableStateOf(0 to 0) }
    val pointer = view.pointer
    LaunchedEffect(pointer) {
        val tree = try {
            inspector.tree()
        } catch (e: InspectException) {
            null
        }
        found = if (tree == null || pointer == null) null else measureAt(tree, pointer.first, pointer.second)
        val capture = tree?.let { root ->
            fun find(node: InspectNode): InspectNode? =
                if (node.testTag == CAPTURE) node else node.children.firstNotNullOfOrNull(::find)
            find(root)
        }
        area = (capture?.bounds?.width ?: 0) to (capture?.bounds?.height ?: 0)
    }

    val corner = Modifier.align(h = Align.Start, v = Align.Start)
    found?.let { widget ->
        val bounds = widget.inPreview
        Box(
            corner.margins(start = bounds.x.coerceAtLeast(0), top = bounds.y.coerceAtLeast(0))
                .size(bounds.width, bounds.height)
                .border(AtlasTheme.changed)
                .testTag("measure:outline"),
        )
        val below = bounds.y + bounds.height + CARD_GAP
        val top = if (below + CARD_HEIGHT <= area.second) below else bounds.y - CARD_HEIGHT - CARD_GAP
        val left = bounds.x.coerceAtMost(area.first - CARD_WIDTH)
        MeasureCard(widget, corner.margins(start = left.coerceAtLeast(0), top = top.coerceAtLeast(0)))
    }
    Box(
        Modifier.expand()
            .onPointerMove { x, y -> view.pointer = x.roundToInt() to y.roundToInt() }
            .onHover(onExit = { view.pointer = null })
            .testTag(CAPTURE),
    )
}

@Composable
private fun MeasureCard(widget: Measured, modifier: Modifier) {
    with(AtlasTheme) {
        Column(
            spacing = 1,
            modifier = modifier.background(panel2, transitionMs = 0).framed().padding(horizontal = 8, vertical = 6)
                .testTag("measure:card"),
        ) {
            measureLines(widget).forEach { (name, value) ->
                Row(spacing = 8) {
                    Label(name, align = 0f, modifier = Modifier.minWidth(56).themedText().textColor(dim, transitionMs = 0))
                    Label(value, align = 0f, maxWidthChars = 28, modifier = Modifier.themedText())
                }
            }
        }
    }
}
