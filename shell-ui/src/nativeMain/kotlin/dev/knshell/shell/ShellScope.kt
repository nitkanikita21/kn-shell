@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.shell

import androidx.compose.runtime.Composable
import dev.knshell.compose.Color
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kn.gtk.GTK_LAYER_SHELL_KEYBOARD_MODE_NONE
import kn.gtk.GTK_LAYER_SHELL_LAYER_TOP
import kn.gtk.GtkWindow

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
    val layer: UInt = GTK_LAYER_SHELL_LAYER_TOP,
    val anchors: Set<BarAnchor> = setOf(BarAnchor.Top, BarAnchor.Left, BarAnchor.Right),
    val margins: Margins = Margins(top = 8, left = 10, right = 10, bottom = 0),
    val exclusiveZone: ExclusiveZone = ExclusiveZone.Auto,
    val keyboardMode: UInt = GTK_LAYER_SHELL_KEYBOARD_MODE_NONE,
    val namespace: String = "kn-shell",
)

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
