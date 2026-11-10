@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.gtk

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kn.gtk.GtkBox
import kn.gtk.GtkOrientation
import kn.gtk.GtkWidget
import kn.gtk.GtkWindow
import kn.gtk.g_main_loop_new
import kn.gtk.g_main_loop_run
import kn.gtk.g_signal_connect_data
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_new
import kn.gtk.gtk_button_new_with_label
import kn.gtk.gtk_init
import kn.gtk.gtk_label_new
import kn.gtk.gtk_label_set_label
import kn.gtk.gtk_widget_set_margin_bottom
import kn.gtk.gtk_widget_set_margin_end
import kn.gtk.gtk_widget_set_margin_start
import kn.gtk.gtk_widget_set_margin_top
import kn.gtk.gtk_window_present
import kn.gtk.gtk_window_set_child
import kn.gtk.gtk_window_set_default_size
import kn.gtk.gtk_window_set_title
import kn.gtk.gtk_window_new

/**
 * Phase 1 step 1 — prove that the GTK4 cinterop works at all:
 * open a normal window with a label and a button, and change the label on click.
 * GTK4 has no `gtk_main()`; a GLib main loop drives the widgets instead.
 */
fun runGtkSmokeTest() {
    gtk_init()

    val window = gtk_window_new()!!.reinterpret<GtkWindow>()
    gtk_window_set_title(window, "KN Shell — GTK smoke test")
    gtk_window_set_default_size(window, 420, 180)

    val boxWidget = gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 12)!!
    gtk_widget_set_margin_start(boxWidget, 16)
    gtk_widget_set_margin_end(boxWidget, 16)
    gtk_widget_set_margin_top(boxWidget, 16)
    gtk_widget_set_margin_bottom(boxWidget, 16)

    val label = gtk_label_new("Rendered by native GTK4 from Kotlin/Native")!!
    gtk_box_append(boxWidget.reinterpret<GtkBox>(), label)

    val button = gtk_button_new_with_label("Click me")!!
    gtk_box_append(boxWidget.reinterpret<GtkBox>(), button)

    connectClicked(button) {
        gtk_label_set_label(label.reinterpret(), "Button clicked — GTK signal works")
    }

    gtk_window_set_child(window, boxWidget)
    gtk_window_present(window)

    println("[shell-ui] GTK window presented; entering GLib main loop")
    val loop = g_main_loop_new(null, 0)
    g_main_loop_run(loop)
}

private fun connectClicked(button: CPointer<GtkWidget>, onClick: () -> Unit) {
    val ref = StableRef.create(onClick)
    val handler = staticCFunction { _: COpaquePointer?, data: COpaquePointer? ->
        data?.asStableRef<() -> Unit>()?.get()?.invoke()
        Unit
    }
    val destroy = staticCFunction { data: COpaquePointer?, _: COpaquePointer? ->
        data?.asStableRef<() -> Unit>()?.dispose()
        Unit
    }

    g_signal_connect_data(
        instance = button,
        detailed_signal = "clicked",
        c_handler = handler.reinterpret(),
        data = ref.asCPointer(),
        destroy_data = destroy.reinterpret(),
        connect_flags = 0u,
    )
}
