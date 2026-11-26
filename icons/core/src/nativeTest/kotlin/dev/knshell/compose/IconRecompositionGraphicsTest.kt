@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.knshell.icons.IconNode
import dev.knshell.icons.IconPack
import dev.knshell.icons.IconStyle
import dev.knshell.icons.ref
import kn.gtk.GtkDrawingArea
import kn.gtk.GtkOrientation
import kn.gtk.gtk_box_new
import kn.gtk.gtk_drawing_area_get_content_width
import kn.gtk.gtk_init_check
import kn.gtk.gtk_widget_get_opacity
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Icon` after its first composition: every parameter that changes must reach the widget.
 *
 * Requires a display connection, like the framework's widget tests.
 */
class IconRecompositionGraphicsTest {

    @BeforeTest
    fun initGtk() {
        check(gtk_init_check() != 0) { "IconRecompositionGraphicsTest needs a display connection" }
    }

    private object TestPack : IconPack {
        override val name = "Test"
        override val style = IconStyle()
        override fun svgInner(id: String) = "<path d=\"M0 0h24v24\"/>"
    }

    /** The composition root; remembers the nodes the applier hands it. */
    private class Root : GtkNode(gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!) {
        val inserted = mutableListOf<GtkNode>()

        override fun insertChild(index: Int, child: GtkNode) {
            inserted += child
            super.insertChild(index, child)
        }
    }

    @Test
    fun `a changed icon size color and modifier reach the widget`() {
        var id by mutableStateOf("first")
        var size by mutableStateOf(16)
        var color by mutableStateOf(Color(255, 0, 0))
        var opacity by mutableStateOf(1f)

        compose({ Icon(TestPack.ref(id), Modifier.opacity(opacity), size, color, transitionMs = 0) }) { root, settle ->
            id = "second"
            size = 24
            color = Color(0, 255, 0)
            opacity = 0.5f
            settle(0L)

            val node = root.inserted.single() as IconNode
            assertEquals("second", node.ref.id)
            assertEquals("#00FF00", node.colorHex)
            assertEquals(24, gtk_drawing_area_get_content_width(node.widget.reinterpret<GtkDrawingArea>()))
            assertTrue(abs(gtk_widget_get_opacity(node.widget) - 0.5) < 0.01)
        }
    }

    @Test
    fun `a color change is tweened over the transition`() {
        var color by mutableStateOf(Color(0, 0, 0))

        compose({ Icon(TestPack.ref("only"), color = color, transitionMs = 100) }) { root, settle ->
            val node = root.inserted.single() as IconNode
            color = Color(200, 200, 200)
            // frame times are in nanoseconds: the first frame starts the tween, the next is half way
            settle(0L)
            settle(50_000_000L)
            val halfWay = node.colorHex
            settle(200_000_000L)

            assertTrue(halfWay != "#000000" && halfWay != "#C8C8C8", "half way through: $halfWay")
            assertEquals("#C8C8C8", node.colorHex)
        }
    }

    /** Composes [content] under a hand-driven recomposer; `settle` applies changes and sends a frame. */
    private fun compose(
        content: @androidx.compose.runtime.Composable () -> Unit,
        check: suspend CoroutineScope.(Root, suspend (Long) -> Unit) -> Unit,
    ) = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val running = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val root = Root()
        val composition = Composition(GtkApplier(root), recomposer)
        val settle: suspend (Long) -> Unit = { time ->
            repeat(5) {
                Snapshot.sendApplyNotifications()
                yield()
                clock.sendFrame(time)
                yield()
            }
        }
        try {
            composition.setContent(content)
            settle(0L)
            check(root, settle)
        } finally {
            composition.dispose()
            recomposer.cancel()
            running.cancel()
        }
    }
}
