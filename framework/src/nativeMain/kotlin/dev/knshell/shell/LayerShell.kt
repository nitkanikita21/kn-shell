// wlr-layer-shell interop: turns a `GtkWindow` into an anchored bar surface, plus the runtime's
// default window stylesheet.

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.shell

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kn.gtk.GtkWindow
import kn.gtk.GTK_LAYER_SHELL_EDGE_BOTTOM
import kn.gtk.GTK_LAYER_SHELL_EDGE_LEFT
import kn.gtk.GTK_LAYER_SHELL_EDGE_RIGHT
import kn.gtk.GTK_LAYER_SHELL_EDGE_TOP
import kn.gtk.gtk_layer_auto_exclusive_zone_enable
import kn.gtk.gtk_layer_init_for_window
import kn.gtk.gtk_layer_is_supported
import kn.gtk.gtk_layer_set_anchor
import kn.gtk.gtk_layer_set_exclusive_zone
import kn.gtk.gtk_layer_set_keyboard_mode
import kn.gtk.gtk_layer_set_layer
import kn.gtk.gtk_layer_set_margin
import kn.gtk.gtk_layer_set_namespace

/**
 * Turns [window] into a wlr-layer-shell surface according to [spec].
 *
 * Requires a wlroots-based compositor (Hyprland / Sway / Niri). On GNOME/Mutter the
 * protocol is not implemented and `gtk_layer_is_supported()` returns false.
 */
internal fun configureLayerShell(window: CPointer<GtkWindow>, spec: BarWindowSpec) {
    if (gtk_layer_is_supported() == 0) {
        println("[shell-ui] gtk4-layer-shell not supported by this compositor; using a normal window")
        return
    }
    gtk_layer_init_for_window(window)
    gtk_layer_set_namespace(window, spec.namespace)
    gtk_layer_set_layer(window, spec.layer)

    if (BarAnchor.Top in spec.anchors) {
        gtk_layer_set_anchor(window, GTK_LAYER_SHELL_EDGE_TOP, 1)
        gtk_layer_set_margin(window, GTK_LAYER_SHELL_EDGE_TOP, spec.margins.top)
    }
    if (BarAnchor.Bottom in spec.anchors) {
        gtk_layer_set_anchor(window, GTK_LAYER_SHELL_EDGE_BOTTOM, 1)
        gtk_layer_set_margin(window, GTK_LAYER_SHELL_EDGE_BOTTOM, spec.margins.bottom)
    }
    if (BarAnchor.Left in spec.anchors) {
        gtk_layer_set_anchor(window, GTK_LAYER_SHELL_EDGE_LEFT, 1)
        gtk_layer_set_margin(window, GTK_LAYER_SHELL_EDGE_LEFT, spec.margins.left)
    }
    if (BarAnchor.Right in spec.anchors) {
        gtk_layer_set_anchor(window, GTK_LAYER_SHELL_EDGE_RIGHT, 1)
        gtk_layer_set_margin(window, GTK_LAYER_SHELL_EDGE_RIGHT, spec.margins.right)
    }

    val zone = spec.exclusiveZone
    when (zone) {
        ExclusiveZone.Auto -> gtk_layer_auto_exclusive_zone_enable(window)
        ExclusiveZone.None -> gtk_layer_set_exclusive_zone(window, 0)
        is ExclusiveZone.Fixed -> gtk_layer_set_exclusive_zone(window, zone.pixels)
    }
    gtk_layer_set_keyboard_mode(window, spec.keyboardMode)

    println(
        "[shell-ui] layer-shell surface initialised (namespace=${spec.namespace}, " +
            "layer=${spec.layer}, anchors=${spec.anchors}, exclusive=${spec.exclusiveZone})"
    )
}

/**
 * Default rules for windows created through [ShellScope]: the shell's window background
 * and fully transparent layer-shell bars.
 */
internal const val RUNTIME_CSS: String = """
window.kn-window {
    background-color: rgba(18, 18, 24, 0.85);
}
window.kn-window.kn-bar {
    background-color: transparent;
}
"""
