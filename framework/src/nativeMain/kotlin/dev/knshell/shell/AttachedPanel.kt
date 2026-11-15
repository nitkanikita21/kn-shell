@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.mutableStateOf
import dev.knshell.compose.GtkApplier
import dev.knshell.compose.GtkBoxNode
import dev.knshell.compose.GtkNode
import dev.knshell.compose.Modifier
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kn.gtk.GdkRectangle
import kn.gtk.graphene_rect_t
import kn.gtk.GtkGesture
import kn.gtk.GtkOrientation
import kn.gtk.GtkWidget
import kn.gtk.g_signal_connect_data
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_new
import kn.gtk.gtk_box_remove
import kn.gtk.gtk_gesture_click_new
import kn.gtk.gtk_widget_add_controller
import kn.gtk.gtk_widget_compute_bounds
import kn.gtk.gtk_widget_get_first_child
import kn.gtk.gtk_widget_get_parent
import kn.gtk.gtk_widget_get_root
import platform.posix.getenv

/**
 * Universal attached panel: wraps **any** widget and opens a layer-shell panel underneath it.
 *
 * Unlike `Popover` (a GTK popup surface) this is a separate layer-shell surface created by the
 * shell runtime: no grab, no autohide, fixed size, shown/hidden instantly. The panel is
 * positioned from the trigger's bounds inside its window, so it works for any module in the
 * bar (bell, network, clock, …), not just the centre island.
 *
 * ```kotlin
 * AttachedPanel(width = 380, height = 360, topOffset = 40, content = { NotificationCenter() }) {
 *     BellIcon()
 * }
 * ```
 *
 * [topOffset] is the extra gap below the reserved area (bars); `0` puts the panel directly
 * under the bar. Compositors place overlay surfaces after exclusive zones, so this is a gap.
 */
@Composable
fun AttachedPanel(
    modifier: Modifier = Modifier,
    width: Int = 360,
    height: Int = 320,
    topOffset: Int = 0,
    alignToParent: Boolean = false,
    onOpenChange: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
    anchor: @Composable () -> Unit,
) {
    ComposeNode<GtkAttachedPanelNode, GtkApplier>(
        factory = {
            GtkAttachedPanelNode(width, height, topOffset, alignToParent, onOpenChange).also {
                it.contentState.value = content
            }
        },
        update = {
            set(modifier) { value -> applyModifier(value) }
            set(content) { value -> contentState.value = value }
            set(onOpenChange) { value -> openChange = value }
        },
    ) {
        anchor()
    }
}

internal class GtkAttachedPanelNode(
    private val requestedWidth: Int,
    private val panelHeight: Int,
    private val topOffset: Int,
    private val alignToParent: Boolean,
    internal var openChange: ((Boolean) -> Unit)?,
) : GtkNode(gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, 0)!!) {

    internal val contentState = mutableStateOf<@Composable () -> Unit>({})

    private var panel: PanelHandle? = null
    private var shownOnce = false

    @Suppress("unused")
    private val selfRef = StableRef.create(this)

    init {
        attachClick(widget)
        // Development aid: KN_PANEL_DEBUG=1 opens every attached panel once at startup.
        if (getenv("KN_PANEL_DEBUG")?.toKString() == "1") {
            gtk_timeout(1500u) { open() }
        }
    }

    override fun attachChild(child: CPointer<GtkWidget>) {
        gtk_box_append(widget.reinterpret<kn.gtk.GtkBox>(), child)
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        gtk_box_remove(widget.reinterpret<kn.gtk.GtkBox>(), child)
    }

    private fun attachClick(target: CPointer<GtkWidget>) {
        val gesture = gtk_gesture_click_new() ?: return
        val ref = selfRef
        val pressed = staticCFunction { _: COpaquePointer?, _: Int, _: Double, _: Double, data: COpaquePointer? ->
            data?.asStableRef<GtkAttachedPanelNode>()?.get()?.onClick()
            Unit
        }
        g_signal_connect_data(
            instance = gesture.reinterpret(),
            detailed_signal = "pressed",
            c_handler = pressed.reinterpret(),
            data = ref.asCPointer(),
            destroy_data = null,
            connect_flags = 0u,
        )
        gtk_widget_add_controller(target, gesture.reinterpret())
    }

    private fun onClick() {
        val current = panel
        if (current != null) {
            val next = !current.isOpen
            current.toggle()
            openChange?.invoke(next)
            return
        }
        open()
    }

    private fun notifyOpen(open: Boolean) {
        openChange?.invoke(open)
    }

    private fun open() {
        val existing = panel
        if (existing != null) {
            existing.show()
            return
        }
        val (x, width) = panelGeometry()
        val handle = ShellRuntime.instance().createPanel(
            PanelWindowSpec(
                x = x,
                y = topOffset,
                width = width,
                height = panelHeight,
            ),
        ) {
            contentState.value()
        }
        panel = handle
        handle.show()
        shownOnce = true
        notifyOpen(true)
    }

    /**
     * Panel horizontal geometry `(x, width)`: centred under the trigger, or (with
     * [alignToParent]) matched to the anchor's parent container — that is what makes an
     * island grow down into a panel of exactly the island's width.
     */
    private fun panelGeometry(): Pair<Int, Int> {
        // The wrapped content is the node's first child; align to it, falling back to the
        // parent when there is no child (e.g. the anchor composable rendered nothing).
        val reference = if (alignToParent) {
            gtk_widget_get_first_child(widget) ?: gtk_widget_get_parent(widget) ?: widget
        } else {
            widget
        }
        val root = gtk_widget_get_root(reference) ?: return 0 to requestedWidth
        val bounds = memScoped {
            val rect = alloc<graphene_rect_t>()
            val ok = gtk_widget_compute_bounds(reference.reinterpret(), root.reinterpret(), rect.ptr) != 0
            if (!ok) return 0 to requestedWidth
            Triple(rect.origin.x.toInt(), rect.size.width.toInt(), rect.origin.y.toInt())
        }
        val parentWidth = bounds.second.coerceAtLeast(1)
        val width = if (requestedWidth > 0) requestedWidth else parentWidth
        val center = bounds.first + parentWidth / 2
        return if (alignToParent) {
            // Align the panel's left edge with the container (island), not its centre.
            bounds.first.coerceAtLeast(0) to width
        } else {
            val screen = displayWidth()
            val x = center - width / 2
            (if (screen > 0) x.coerceIn(0, screen - width) else x.coerceAtLeast(0)) to width
        }
    }
}

private fun gtk_timeout(milliseconds: UInt, block: () -> Unit) {
    kn.gtk.g_timeout_add(
        milliseconds,
        staticCFunction { data: COpaquePointer? ->
            data?.asStableRef<() -> Unit>()?.get()?.invoke()
            0
        },
        StableRef.create(block).asCPointer(),
    )
}
