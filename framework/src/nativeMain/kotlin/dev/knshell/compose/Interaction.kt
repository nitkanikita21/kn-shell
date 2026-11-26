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
import kn.gtk.GTK_EVENT_CONTROLLER_SCROLL_BOTH_AXES
import kn.gtk.GtkWidget
import kn.gtk.g_signal_connect_data
import kn.gtk.gtk_event_controller_motion_new
import kn.gtk.gtk_event_controller_scroll_new
import kn.gtk.gtk_gesture_click_new
import kn.gtk.gtk_gesture_drag_new
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

/** `Modifier.onScroll { dx, dy -> … }`: wheel and touchpad deltas, in GTK's units. */
internal data class ScrollElement(val handler: (Double, Double) -> Unit) : ModifierElement

/** `Modifier.onDrag(…)`: the offsets of [onUpdate] are measured from where the drag started. */
internal data class DragElement(
    val onStart: (() -> Unit)?,
    val onUpdate: (Double, Double) -> Unit,
    val onEnd: (() -> Unit)?,
) : ModifierElement

/** Click / hover / cursor / scroll / drag wiring for one widget; lives as long as the [InlineStyle]. */
internal class InteractionState(private val widget: CPointer<GtkWidget>) {
    private var clickHandler: (() -> Unit)? = null
    private var hoverEnter: (() -> Unit)? = null
    private var hoverExit: (() -> Unit)? = null
    private var scrollHandler: ((Double, Double) -> Unit)? = null
    private var drag: DragElement? = null

    private var clickGesture: CPointer<GtkGesture>? = null
    private var motionController: CPointer<GtkEventController>? = null
    private var scrollController: CPointer<GtkEventController>? = null
    private var dragGesture: CPointer<GtkGesture>? = null
    private var cursorName: String? = null
    private var cursorBound = false

    @Suppress("unused")
    private val selfRef = StableRef.create(this)

    fun bind(
        click: (() -> Unit)?,
        enter: (() -> Unit)?,
        exit: (() -> Unit)?,
        cursor: String?,
        scroll: ((Double, Double) -> Unit)? = null,
        dragHandlers: DragElement? = null,
    ) {
        clickHandler = click
        hoverEnter = enter
        hoverExit = exit
        scrollHandler = scroll
        drag = dragHandlers

        if (click != null) ensureClickGesture()
        if (enter != null || exit != null) ensureMotionController()
        if (scroll != null) ensureScrollController()
        if (dragHandlers != null) ensureDragGesture()
        bindCursor(cursor)
    }

    private fun ensureScrollController() {
        if (scrollController != null) return
        val controller = gtk_event_controller_scroll_new(GTK_EVENT_CONTROLLER_SCROLL_BOTH_AXES)
            ?: return
        scrollController = controller
        val scrolled = staticCFunction { _: COpaquePointer?, dx: Double, dy: Double, data: COpaquePointer? ->
            data?.asStableRef<InteractionState>()?.get()?.scrollHandler?.invoke(dx, dy)
            0 // FALSE: the event goes on to the scroll view around the widget
        }
        g_signal_connect_data(
            instance = controller.reinterpret(),
            detailed_signal = "scroll",
            c_handler = scrolled.reinterpret(),
            data = selfRef.asCPointer(),
            destroy_data = null,
            connect_flags = 0u,
        )
        gtk_widget_add_controller(widget, controller)
    }

    private fun ensureDragGesture() {
        if (dragGesture != null) return
        val gesture = gtk_gesture_drag_new() ?: return
        dragGesture = gesture
        val begin = staticCFunction { _: COpaquePointer?, _: Double, _: Double, data: COpaquePointer? ->
            data?.asStableRef<InteractionState>()?.get()?.drag?.onStart?.invoke()
            Unit
        }
        val update = staticCFunction { _: COpaquePointer?, offsetX: Double, offsetY: Double, data: COpaquePointer? ->
            data?.asStableRef<InteractionState>()?.get()?.drag?.onUpdate?.invoke(offsetX, offsetY)
            Unit
        }
        val end = staticCFunction { _: COpaquePointer?, _: Double, _: Double, data: COpaquePointer? ->
            data?.asStableRef<InteractionState>()?.get()?.drag?.onEnd?.invoke()
            Unit
        }
        for ((signal, handler) in listOf("drag-begin" to begin, "drag-update" to update, "drag-end" to end)) {
            g_signal_connect_data(
                instance = gesture.reinterpret(),
                detailed_signal = signal,
                c_handler = handler.reinterpret(),
                data = selfRef.asCPointer(),
                destroy_data = null,
                connect_flags = 0u,
            )
        }
        gtk_widget_add_controller(widget, gesture.reinterpret<GtkEventController>())
    }

    /** Whether a `Modifier.onClick` handler is bound; the inspector checks it before a click. */
    internal val hasClickHandler: Boolean get() = clickHandler != null

    /** Runs the `Modifier.onClick` handler the way a user click on the widget would. */
    internal fun invokeClick() {
        clickHandler?.invoke()
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

/**
 * Scroll wheel and touchpad deltas over the widget: [handler] gets `(dx, dy)` in GTK's units.
 * The event is not consumed, so a surrounding `Scroll` still scrolls.
 */
fun Modifier.onScroll(handler: (dx: Double, dy: Double) -> Unit): Modifier = with(ScrollElement(handler))

/**
 * Pointer drag on the widget. [onUpdate] gets the offset from where the drag started, so a
 * canvas can pan with `offsetX`/`offsetY` against the values it had at [onStart].
 */
fun Modifier.onDrag(
    onUpdate: (offsetX: Double, offsetY: Double) -> Unit,
    onStart: (() -> Unit)? = null,
    onEnd: (() -> Unit)? = null,
): Modifier = with(DragElement(onStart, onUpdate, onEnd))

/** Hand cursor (`"pointer"`) — typical for clickable elements. */
fun Modifier.pointerCursor(): Modifier = cursor("pointer")

/**
 * CSS class the shell runtime looks for when it shapes a bar surface's Wayland input region
 * (`dev.knshell.shell.InputRegion`): a widget carrying `kn-input` is part of the clickable area.
 */
internal const val INPUT_REGION_CLASS: String = "kn-input"

/** `Modifier.inputRegion(…)` — the widget (and therefore this rectangle) accepts pointer input. */
internal data object InputRegionElement : ModifierElement

/**
 * Marks the widget as part of the host surface's clickable area.
 *
 * A layer-shell bar is one surface as wide as the output, so the transparent gaps between its
 * islands would otherwise swallow every click meant for the window behind the bar. The shell
 * runtime punches the surface's input region down to the union of the marked widgets' bounds, so
 * decorating an island with `inputRegion()` is what makes it — and only it — clickable:
 *
 * ```
 * CenterBox(modifier = Modifier.fillWidth(), start = {
 *     Island(Modifier.inputRegion()) { Workspaces() }
 * }, …)
 * ```
 *
 * The mark is inherited by nothing: mark each island (or its container), not the bar root, or the
 * whole bar becomes interactive again. In a plain (non-bar) window it is a no-op.
 */
fun Modifier.inputRegion(): Modifier = with(InputRegionElement)
