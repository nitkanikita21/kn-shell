// ---------------------------------------------------------------------------
// The box behind `Row`, `Column` and `Box`: a `GtkBox` that Tab can stop on. A plain GtkBox hands
// keyboard navigation straight to its children, so a focusable one would never be reached.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kn.gtk.GTypeQuery
import kn.gtk.GtkDirectionType
import kn.gtk.GtkOrientation
import kn.gtk.GtkWidget
import kn.gtk.GtkWidgetClass
import kn.gtk.g_object_new_with_properties
import kn.gtk.g_type_class_peek
import kn.gtk.g_type_query
import kn.gtk.g_type_register_static_simple
import kn.gtk.gtk_box_get_type
import kn.gtk.gtk_orientable_set_orientation
import kn.gtk.gtk_widget_get_focus_child
import kn.gtk.gtk_widget_get_focusable
import kn.gtk.gtk_widget_grab_focus
import kn.gtk.gtk_widget_is_focus

private val knBoxType: ULong by lazy {
    memScoped {
        val parent = alloc<GTypeQuery>()
        g_type_query(gtk_box_get_type(), parent.ptr)
        g_type_register_static_simple(
            parent_type = gtk_box_get_type(),
            type_name = "KnBox",
            class_size = parent.class_size,
            class_init = staticCFunction { klass: kotlinx.cinterop.COpaquePointer?, _: kotlinx.cinterop.COpaquePointer? ->
                klass!!.reinterpret<GtkWidgetClass>().pointed.focus = staticCFunction(::knBoxFocus)
            }.reinterpret(),
            instance_size = parent.instance_size,
            instance_init = null,
            flags = 0u,
        )
    }
}

/** A new box of [orientation] without spacing. */
internal fun newBox(orientation: GtkOrientation): CPointer<GtkWidget> {
    val box: CPointer<GtkWidget> = checkNotNull(g_object_new_with_properties(knBoxType, 0u, null, null)).reinterpret()
    gtk_orientable_set_orientation(box.reinterpret(), orientation)
    return box
}

/**
 * `GtkWidgetClass.focus` of [knBoxType]. A focusable box comes before its content: Tab reaches the
 * box, then goes inside; Shift+Tab leaves the content for the box. Otherwise it behaves as a box.
 */
private fun knBoxFocus(widget: CPointer<GtkWidget>?, direction: GtkDirectionType): Int {
    val box = widget ?: return 0
    fun intoContent(): Int =
        g_type_class_peek(gtk_box_get_type())!!.reinterpret<GtkWidgetClass>().pointed.focus!!.invoke(box, direction)
    if (gtk_widget_get_focusable(box) == 0) return intoContent()
    val backward = direction == GtkDirectionType.GTK_DIR_TAB_BACKWARD
    return when {
        gtk_widget_is_focus(box) != 0 -> if (backward) 0 else intoContent()
        gtk_widget_get_focus_child(box) != null -> when {
            intoContent() != 0 -> 1
            backward -> gtk_widget_grab_focus(box)
            else -> 0
        }
        backward && intoContent() != 0 -> 1
        else -> gtk_widget_grab_focus(box)
    }
}
