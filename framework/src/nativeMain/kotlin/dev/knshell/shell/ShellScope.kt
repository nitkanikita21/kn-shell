@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.shell

import androidx.compose.runtime.Composable
import dev.knshell.compose.Color
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kn.gtk.GTK_LAYER_SHELL_KEYBOARD_MODE_NONE
import kn.gtk.GTK_LAYER_SHELL_LAYER_BACKGROUND
import kn.gtk.GTK_LAYER_SHELL_LAYER_BOTTOM
import kn.gtk.GTK_LAYER_SHELL_LAYER_OVERLAY
import kn.gtk.GTK_LAYER_SHELL_LAYER_TOP
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kn.gtk.GdkMonitor
import kn.gtk.GdkRectangle
import kn.gtk.GtkWindow
import kn.gtk.g_list_model_get_item
import kn.gtk.gdk_display_get_default
import kn.gtk.gdk_display_get_monitors
import kn.gtk.gdk_monitor_get_geometry

/** Edge of the output a bar window can be anchored to. */
enum class BarAnchor { Top, Bottom, Left, Right }

/**
 * How much output space a layer-shell bar claims.
 *
 * [Auto] lets the compositor derive the zone from the surface size, [None] reserves
 * nothing (floating bar) and [Fixed] reserves an explicit number of pixels.
 */
sealed class ExclusiveZone {
    data object Auto : ExclusiveZone()
    data object None : ExclusiveZone()
    data class Fixed(val pixels: Int) : ExclusiveZone()
}

/** Per-edge margins of a layer-shell bar, in pixels. */
data class Margins(
    val top: Int,
    val left: Int,
    val right: Int,
    val bottom: Int = 0,
)

/** A normal toplevel window (not layer-shell). */
data class WindowSpec(
    val title: String,
    val width: Int,
    val height: Int,
    val background: Color? = null,
    val decorations: Boolean = true,
    val cssClass: String? = null,
)

/** A wlr-layer-shell bar surface (top bar, bottom bar, side panel, …). */
data class BarWindowSpec(
    val title: String = "KN Shell",
    val height: Int = 40,
    val width: Int = 1280,
    val layer: UInt = BarLayer.Top,
    val anchors: Set<BarAnchor> = setOf(BarAnchor.Top, BarAnchor.Left, BarAnchor.Right),
    val margins: Margins = Margins(top = 8, left = 10, right = 10, bottom = 0),
    val exclusiveZone: ExclusiveZone = ExclusiveZone.Auto,
    val keyboardMode: UInt = GTK_LAYER_SHELL_KEYBOARD_MODE_NONE,
    val namespace: String = "kn-shell",
)

/**
 * wlr-layer-shell layers by name, so call sites do not need to import the cinterop
 * constants (`kn.gtk.GTK_LAYER_SHELL_LAYER_*`).
 *
 * `Overlay` renders above `Top` (and therefore above waybar); combine it with
 * [ExclusiveZone.None] and a negative top margin to place a development bar over waybar.
 */
object BarLayer {
    val Background: UInt = GTK_LAYER_SHELL_LAYER_BACKGROUND
    val Bottom: UInt = GTK_LAYER_SHELL_LAYER_BOTTOM
    val Top: UInt = GTK_LAYER_SHELL_LAYER_TOP
    val Overlay: UInt = GTK_LAYER_SHELL_LAYER_OVERLAY
}

/**
 * A fixed-size layer-shell panel attached under the bar (e.g. a notification centre).
 *
 * It is an independent overlay surface with `exclusiveZone = None`, so it reserves no space
 * and — unlike a `GtkPopover` — has no grab/autohide state machine: it is simply shown or
 * hidden. The surface keeps the size given at creation (no animated resize).
 */
data class PanelWindowSpec(
    val title: String = "KN Panel",
    /** Horizontal offset from the left edge of the output. */
    val x: Int = 0,
    /**
     * Extra gap below the reserved area (the area taken by exclusive zones, i.e. bars).
     * `0` places the panel directly under the bar; compositors position overlay surfaces
     * after the reserved area, so this is a gap, not an absolute offset.
     */
    val y: Int = 0,
    val width: Int = 360,
    val height: Int = 320,
    val layer: UInt = BarLayer.Overlay,
    val keyboardMode: UInt = GTK_LAYER_SHELL_KEYBOARD_MODE_NONE,
    val namespace: String = "kn-panel",
)

/** Handle to a mounted [PanelWindowSpec] surface. */
class PanelHandle internal constructor(
    val window: CPointer<GtkWindow>,
    private val setOpenAction: (Boolean) -> Unit,
) {
    var isOpen: Boolean = false
        private set

    fun show() {
        if (isOpen) return
        isOpen = true
        setOpenAction(true)
    }

    fun hide() {
        if (!isOpen) return
        isOpen = false
        setOpenAction(false)
    }

    fun toggle() {
        if (isOpen) hide() else show()
    }
}

/** Width of the primary output, in pixels (0 when no GDK display is available). */
fun displayWidth(): Int = monitorSize().first

/** Height of the primary output, in pixels. */
fun displayHeight(): Int = monitorSize().second

private fun monitorSize(): Pair<Int, Int> {
    val display = gdk_display_get_default() ?: return 0 to 0
    val monitors = gdk_display_get_monitors(display) ?: return 0 to 0
    val monitor = g_list_model_get_item(monitors, 0u)?.reinterpret<GdkMonitor>() ?: return 0 to 0
    return memScoped {
        val rectangle = alloc<GdkRectangle>()
        gdk_monitor_get_geometry(monitor, rectangle.ptr)
        rectangle.width to rectangle.height
    }
}

/** Handle to a mounted window; [close] closes it through GTK's close-request path. */
class WindowHandle internal constructor(
    val window: CPointer<GtkWindow>,
    private val closeAction: () -> Unit,
) {
    fun close() = closeAction()
}

/**
 * Declarative window list of [Shell.run].
 *
 * Every call mounts a window on the shared [ShellRuntime], so all windows open at once
 * and recompose together. Call order is creation order; the returned handles allow later
 * programmatic closing (e.g. toggling a panel).
 */
class ShellScope internal constructor(private val runtime: ShellRuntime) {

    private val handles = mutableListOf<WindowHandle>()

    /** Declares a layer-shell bar (top bar by default). */
    fun barWindow(spec: BarWindowSpec = BarWindowSpec(), content: @Composable () -> Unit): WindowHandle {
        val handle = runtime.createWindow(
            title = spec.title,
            width = spec.width,
            height = spec.height,
            background = null,
            decorations = false,
            cssClass = null,
            bar = spec,
            content = content,
        )
        handles += handle
        return handle
    }

    /**
     * Declares a layer-shell panel under the bar; the returned [PanelHandle] shows/hides it.
     *
     * ```kotlin
     * val panel = panelWindow(PanelWindowSpec(x = (displayWidth() - 360) / 2, y = 40)) {
     *     NotificationPanel()
     * }
     * // from the bar: panel.toggle()
     * ```
     */
    fun panelWindow(
        spec: PanelWindowSpec = PanelWindowSpec(),
        content: @Composable () -> Unit,
    ): PanelHandle = runtime.createPanel(spec, content)

    /** Declares a normal toplevel window. */
    fun window(spec: WindowSpec, content: @Composable () -> Unit): WindowHandle {
        val handle = runtime.createWindow(
            title = spec.title,
            width = spec.width,
            height = spec.height,
            background = spec.background,
            decorations = spec.decorations,
            cssClass = spec.cssClass,
            bar = null,
            content = content,
        )
        handles += handle
        return handle
    }
}
