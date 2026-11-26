@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.inspect

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kn.gtk.GValue
import kn.gtk.g_object_set_property
import kn.gtk.g_type_from_name
import kn.gtk.g_value_init
import kn.gtk.g_value_set_boolean
import kn.gtk.g_value_unset
import kn.gtk.gtk_init
import kn.gtk.gtk_settings_get_default

/**
 * Turns GTK's CSS transitions and animations off for the whole process.
 *
 * An inspected process needs this: [Inspector.awaitIdle] waits for recomposition and layout, not
 * for a transition (`Modifier.background` fades for 200 ms by default), so a screenshot taken right
 * after a change would show a colour half way between the old and the new one. Compose-driven
 * animations are not affected. Initialises GTK, so call it on the main thread.
 */
fun disableUiTransitions() {
    gtk_init()
    val settings = gtk_settings_get_default() ?: return
    memScoped {
        val value = alloc<GValue>()
        g_value_init(value.ptr, g_type_from_name("gboolean"))
        g_value_set_boolean(value.ptr, 0)
        g_object_set_property(settings.reinterpret(), "gtk-enable-animations", value.ptr)
        g_value_unset(value.ptr)
    }
}
