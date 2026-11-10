@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kn.gtk.GtkEventController
import kn.gtk.GtkGesture
import kn.gtk.GtkWidget
import kn.gtk.g_signal_connect_data
import kn.gtk.gtk_event_controller_motion_new
import kn.gtk.gtk_gesture_click_new
import kn.gtk.gtk_widget_add_controller
import kn.gtk.gtk_widget_set_cursor_from_name

/** `Modifier.onClick { … }` on any widget. */
internal data class ClickElement(val handler: () -> Unit) : ModifierElement

/** `Modifier.onHover { … }` on any widget. */
internal data class HoverElement(
    val onEnter: (() -> Unit)?,
    val onExit: (() -> Unit)?,
) : ModifierElement

/** `Modifier.cursor("pointer")` — GTK does not support the CSS `cursor` property. */
internal data class CursorElement(val name: String?) : ModifierElement

/** Click / hover / cursor wiring for one widget; lives as long as the [InlineStyle]. */
internal class InteractionState(private val widget: CPointer<GtkWidget>) {
    private var clickHandler: (() -> Unit)? = null
    private var hoverEnter: (() -> Unit)? = null
    private var hoverExit: (() -> Unit)? = null

    private var clickGesture: CPointer<GtkGesture>? = null
    private var motionController: CPointer<GtkEventController>? = null
    private var cursorName: String? = null
    private var cursorBound = false

    @Suppress("unused")
    private val selfRef = StableRef.create(this)

    fun bind(
        click: (() -> Unit)?,
        enter: (() -> Unit)?,
        exit: (() -> Unit)?,
        cursor: String?,
    ) {
        clickHandler = click
        hoverEnter = enter
        hoverExit = exit

        if (click != null) ensureClickGesture()
        if (enter != null || exit != null) ensureMotionController()
        bindCursor(cursor)
    }

    private fun ensureClickGesture() {
        if (clickGesture != null) return
        val gesture = gtk_gesture_click_new() ?: return
        clickGesture = gesture
        val ref = selfRef
        val released = staticCFunction { _: COpaquePointer?, _: Int, _: Double, _: Double, data: COpaquePointer? ->
            data?.asStableRef<InteractionState>()?.get()?.clickHandler?.invoke()
            Unit
        }
        g_signal_connect_data(
            instance = gesture.reinterpret(),
            detailed_signal = "released",
            c_handler = released.reinterpret(),
            data = ref.asCPointer(),
            destroy_data = null,
            connect_flags = 0u,
        )
        gtk_widget_add_controller(widget, gesture.reinterpret<GtkEventController>())
    }

    private fun ensureMotionController() {
        if (motionController != null) return
        val motion = gtk_event_controller_motion_new() ?: return
        motionController = motion
        val ref = selfRef
        val enter = staticCFunction { _: COpaquePointer?, _: Double, _: Double, data: COpaquePointer? ->
            data?.asStableRef<InteractionState>()?.get()?.hoverEnter?.invoke()
            Unit
        }
        val leave = staticCFunction { _: COpaquePointer?, data: COpaquePointer? ->
            data?.asStableRef<InteractionState>()?.get()?.hoverExit?.invoke()
            Unit
        }
        g_signal_connect_data(
            instance = motion.reinterpret(),
            detailed_signal = "enter",
            c_handler = enter.reinterpret(),
            data = ref.asCPointer(),
            destroy_data = null,
            connect_flags = 0u,
        )
        g_signal_connect_data(
            instance = motion.reinterpret(),
            detailed_signal = "leave",
            c_handler = leave.reinterpret(),
            data = ref.asCPointer(),
            destroy_data = null,
            connect_flags = 0u,
        )
        gtk_widget_add_controller(widget, motion)
    }

    private fun bindCursor(name: String?) {
        if (cursorBound && name == cursorName) return
        cursorBound = true
        cursorName = name
        gtk_widget_set_cursor_from_name(widget, name)
    }
}

/** Click handler for any widget (gesture-based, not a Button). */
fun Modifier.onClick(handler: () -> Unit): Modifier = with(ClickElement(handler))

/** Hover enter/exit handlers for any widget. */
fun Modifier.onHover(
    onEnter: (() -> Unit)? = null,
    onExit: (() -> Unit)? = null,
): Modifier = with(HoverElement(onEnter, onExit))

/** Sets the mouse cursor while hovering the widget: `"pointer"`, `"default"`, `"text"`, … */
fun Modifier.cursor(name: String): Modifier = with(CursorElement(name))

/** Hand cursor (`"pointer"`) — typical for clickable elements. */
fun Modifier.pointerCursor(): Modifier = cursor("pointer")
