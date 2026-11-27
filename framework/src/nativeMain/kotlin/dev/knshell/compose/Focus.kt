// ---------------------------------------------------------------------------
// Keyboard focus — making widgets focusable, moving the focus from code, hearing about it, and
// keeping it inside a part of the window.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.remember
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kn.gtk.GList
import kn.gtk.GtkEventController
import kn.gtk.GtkOrientation
import kn.gtk.GtkPropagationPhase
import kn.gtk.GtkWidget
import kn.gtk.GtkWindow
import kn.gtk.g_list_free
import kn.gtk.gtk_window_is_active
import kn.gtk.gtk_window_list_toplevels
import kn.gtk.g_object_ref
import kn.gtk.g_object_unref
import kn.gtk.g_signal_connect_data
import kn.gtk.gtk_event_controller_focus_new
import kn.gtk.gtk_event_controller_key_new
import kn.gtk.gtk_event_controller_set_propagation_phase
import kn.gtk.gtk_widget_add_controller
import kn.gtk.gtk_widget_get_mapped
import kn.gtk.gtk_widget_child_focus
import kn.gtk.GtkDirectionType
import kn.gtk.g_type_name_from_instance
import kn.gtk.gtk_widget_get_can_focus
import kn.gtk.gtk_widget_get_first_child
import kn.gtk.gtk_widget_get_focusable
import kn.gtk.gtk_widget_get_next_sibling
import kn.gtk.gtk_widget_is_sensitive
import kn.gtk.gtk_widget_get_root
import kn.gtk.gtk_widget_grab_focus
import kn.gtk.gtk_widget_is_ancestor
import kn.gtk.gtk_widget_set_focusable
import kn.gtk.gtk_window_get_focus
import kn.gtk.gtk_window_set_focus

/**
 * Moves the keyboard focus to a widget from code: bind it with `Modifier.focusRequester`, then call
 * [requestFocus]. For roving focus, give every item its own requester and `focusable` only the
 * current one; requesting focus makes the target focusable itself.
 */
class FocusRequester {
    internal var widget: CPointer<GtkWidget>? = null

    /** Whether the widget, or something inside it, has the focus. */
    val hasFocus: Boolean get() = widget?.containsFocus() == true

    /**
     * Focuses the widget Tab would reach from this one, or Shift+Tab with [backwards]. For a menu or
     * popover that Tab closes: the focus goes on from its trigger.
     */
    fun moveFocus(backwards: Boolean = false) {
        val direction = if (backwards) GtkDirectionType.GTK_DIR_TAB_BACKWARD else GtkDirectionType.GTK_DIR_TAB_FORWARD
        launchOnUiThread {
            retryUntilOnScreen {
                val target = widget ?: return@retryUntilOnScreen false
                if (!target.containsFocus() && gtk_widget_grab_focus(target) == 0) return@retryUntilOnScreen false
                val window = gtk_widget_get_root(target)?.reinterpret<GtkWidget>() ?: return@retryUntilOnScreen false
                // The window answers "no" at its last stop; asked again, it starts over.
                if (gtk_widget_child_focus(window, direction) == 0) gtk_widget_child_focus(window, direction)
                true
            }
        }
    }

    /**
     * Focuses the widget once the current changes have reached the screen, so it may be called right
     * after the state change that creates or reveals it.
     */
    fun requestFocus() {
        launchOnUiThread {
            retryUntilOnScreen {
                val target = widget ?: return@retryUntilOnScreen false
                gtk_widget_set_focusable(target, 1)
                gtk_widget_grab_focus(target) != 0 || focusFirstNow(target)
            }
        }
    }
}

/**
 * Whether the widget takes the keyboard focus. A focusable widget is a Tab stop; [autofocus] gives
 * it the focus when it first appears.
 */
fun Modifier.focusable(focusable: Boolean = true, autofocus: Boolean = false): Modifier =
    with(FocusableElement(focusable, autofocus))

/** Binds [requester] to this widget. */
fun Modifier.focusRequester(requester: FocusRequester): Modifier = with(FocusRequesterElement(requester))

/** Hears when the focus enters this widget or its descendants, and when it leaves them all. */
fun Modifier.onFocusChange(handler: (hasFocus: Boolean) -> Unit): Modifier = with(FocusChangeElement(handler))

internal data class FocusableElement(val focusable: Boolean, val autofocus: Boolean) : ModifierElement

internal data class FocusRequesterElement(val requester: FocusRequester) : ModifierElement

internal data class FocusChangeElement(val handler: (Boolean) -> Unit) : ModifierElement

/** Whether the focus of the widget's window is on it or inside it. */
internal fun CPointer<GtkWidget>.containsFocus(): Boolean {
    val window = gtk_widget_get_root(this) ?: return false
    val focus = gtk_window_get_focus(window.reinterpret()) ?: return false
    return focus == this || gtk_widget_is_ancestor(focus, this) != 0
}

/** Key and focus wiring of one widget; lives as long as the [InlineStyle]. */
internal class InputState(private val widget: CPointer<GtkWidget>) {
    private var keys: List<KeyElement> = emptyList()
    private var focusChange: ((Boolean) -> Unit)? = null
    private var requester: FocusRequester? = null
    private var keyControllers = mutableMapOf<Boolean, CPointer<GtkEventController>>()
    private var focusController: CPointer<GtkEventController>? = null
    private var focusableWritten = false
    private var autofocused = false

    private val selfRef = StableRef.create(this)

    fun bind(
        keyElements: List<KeyElement>,
        focusable: FocusableElement?,
        newRequester: FocusRequester?,
        onFocus: ((Boolean) -> Unit)?,
    ) {
        keys = keyElements
        focusChange = onFocus
        keyElements.map { it.capture }.distinct().forEach(::ensureKeyController)
        if (onFocus != null) ensureFocusController()

        if (requester !== newRequester) {
            requester?.takeIf { it.widget == widget }?.widget = null
            requester = newRequester
        }
        newRequester?.widget = widget

        if (focusable != null) {
            gtk_widget_set_focusable(widget, if (focusable.focusable) 1 else 0)
            focusableWritten = true
            if (focusable.autofocus && !autofocused) {
                autofocused = true
                FocusRequester().also { it.widget = widget }.requestFocus()
            }
        } else if (focusableWritten) {
            gtk_widget_set_focusable(widget, 0)
            focusableWritten = false
        }
    }

    /** The widget leaves the tree. */
    fun release() {
        keys = emptyList()
        focusChange = null
        requester?.takeIf { it.widget == widget }?.widget = null
        requester = null
    }

    private fun handleKey(capture: Boolean, event: KeyEvent): Boolean {
        // Every handler of the phase sees the key until one uses it.
        return keys.filter { it.capture == capture }.any { it.handler(event) }
    }

    private fun ensureKeyController(capture: Boolean) {
        if (keyControllers.containsKey(capture)) return
        val controller = gtk_event_controller_key_new() ?: return
        keyControllers[capture] = controller
        val pressed = if (capture) {
            staticCFunction { _: COpaquePointer?, keyval: UInt, code: UInt, state: UInt, data: COpaquePointer? ->
                val self = data?.asStableRef<InputState>()?.get()
                if (self?.handleKey(true, keyEvent(keyval, state, code)) == true) 1 else 0
            }
        } else {
            staticCFunction { _: COpaquePointer?, keyval: UInt, code: UInt, state: UInt, data: COpaquePointer? ->
                val self = data?.asStableRef<InputState>()?.get()
                if (self?.handleKey(false, keyEvent(keyval, state, code)) == true) 1 else 0
            }
        }
        g_signal_connect_data(
            instance = controller.reinterpret(),
            detailed_signal = "key-pressed",
            c_handler = pressed.reinterpret(),
            data = selfRef.asCPointer(),
            destroy_data = null,
            connect_flags = 0u,
        )
        val phase = if (capture) GtkPropagationPhase.GTK_PHASE_CAPTURE else GtkPropagationPhase.GTK_PHASE_BUBBLE
        gtk_event_controller_set_propagation_phase(controller, phase)
        gtk_widget_add_controller(widget, controller)
    }

    private fun ensureFocusController() {
        if (focusController != null) return
        val controller = gtk_event_controller_focus_new() ?: return
        focusController = controller
        val enter = staticCFunction { _: COpaquePointer?, data: COpaquePointer? ->
            data?.asStableRef<InputState>()?.get()?.focusChange?.invoke(true)
            Unit
        }
        val leave = staticCFunction { _: COpaquePointer?, data: COpaquePointer? ->
            data?.asStableRef<InputState>()?.get()?.focusChange?.invoke(false)
            Unit
        }
        for ((signal, handler) in listOf("enter" to enter, "leave" to leave)) {
            g_signal_connect_data(
                instance = controller.reinterpret(),
                detailed_signal = signal,
                c_handler = handler.reinterpret(),
                data = selfRef.asCPointer(),
                destroy_data = null,
                connect_flags = 0u,
            )
        }
        gtk_widget_add_controller(widget, controller)
    }
}

/**
 * A part of the window with focus rules of its own, for dialogs and popups.
 *
 * - [autoFocus]: when it appears, the first focusable widget inside gets the focus.
 * - [trap]: Tab and Shift+Tab cycle inside it and cannot leave.
 * - [restoreFocus]: when it goes away, the focus returns to where it was.
 */
@Composable
fun FocusScope(
    modifier: Modifier = Modifier,
    trap: Boolean = false,
    autoFocus: Boolean = true,
    restoreFocus: Boolean = true,
    onTabOut: ((backwards: Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Taken during composition, before any widget of the scope can have taken the focus.
    val previous = remember { PreviousFocus() }
    ComposeNode<GtkFocusScopeNode, GtkApplier>(
        factory = { GtkFocusScopeNode(previous, autoFocus).plain() },
        update = {
            set(restoreFocus) { restores = it }
            set(trap) { traps = it }
            set(onTabOut) { tabOut = it }
            set(modifier) { value -> applyModifier(value) }
        },
        content = content,
    )
}

/** The focused widget at the time it was asked for, kept alive until it is given the focus back. */
internal class PreviousFocus {
    var widget: CPointer<GtkWidget>? = focusedWidget()?.also { g_object_ref(it) }
        private set

    fun restore(restoreIt: Boolean) {
        val target = widget ?: return
        widget = null
        if (restoreIt) {
            launchOnUiThread {
                if (gtk_widget_get_root(target) != null) gtk_widget_grab_focus(target)
                g_object_unref(target)
            }
        } else {
            g_object_unref(target)
        }
    }
}

internal class GtkFocusScopeNode(private val previous: PreviousFocus, private val autoFocus: Boolean) :
    GtkBoxNode(newBox(GtkOrientation.GTK_ORIENTATION_VERTICAL)) {

    var restores = true
    var traps = false
    var tabOut: ((Boolean) -> Unit)? = null
    private var appeared = false

    init {
        configureBox(vertical = true, spacing = 0, homogeneous = false)
    }

    internal override fun applyModifier(modifier: Modifier) {
        // The trap sees Tab before the focused widget moves the focus by itself.
        super.applyModifier(
            modifier.onKey(capture = true) { event ->
                when {
                    event.key != Key.Tab || event.ctrl || event.alt -> false
                    traps -> {
                        cycle(backwards = event.shift)
                        true
                    }
                    else -> leave(backwards = event.shift)
                }
            },
        )
        if (!appeared) {
            appeared = true
            if (autoFocus) {
                launchOnUiThread { retryUntilOnScreen { widget.containsFocus() || focusFirstNow(widget) } }
            }
        }
    }

    /** Tells [tabOut] when Tab would take the focus past an end of the scope. */
    private fun leave(backwards: Boolean): Boolean {
        val handler = tabOut ?: return false
        val stops = focusableWidgets(widget)
        val end = if (backwards) stops.firstOrNull() else stops.lastOrNull()
        // A container that holds the focus itself still has its content ahead.
        val atEnd = end == null || (end.containsFocus() && (backwards || stops.none { it != end && it.isInside(end) }))
        if (atEnd) handler(backwards)
        return atEnd
    }

    private fun cycle(backwards: Boolean) {
        val stops = focusableWidgets(widget)
        if (stops.isEmpty()) return
        val current = stops.indexOfFirst { it.containsFocus() }
        val next = when {
            current < 0 -> if (backwards) stops.lastIndex else 0
            backwards -> (current - 1 + stops.size) % stops.size
            else -> (current + 1) % stops.size
        }
        gtk_widget_grab_focus(stops[next])
    }

    override fun cancelAnimations() {
        previous.restore(restores)
        super.cancelAnimations()
    }
}

private fun CPointer<GtkWidget>.isInside(other: CPointer<GtkWidget>): Boolean =
    gtk_widget_is_ancestor(this, other) != 0

/** The focused widget of the active window, else of any window that has one. */
internal fun focusedWidget(): CPointer<GtkWidget>? {
    val windows = gtk_window_list_toplevels() ?: return null
    var any: CPointer<GtkWidget>? = null
    var node: CPointer<GList>? = windows
    while (node != null) {
        val window = node.pointed.data?.reinterpret<GtkWindow>()
        val focus = window?.let { gtk_window_get_focus(it) }
        if (focus != null) {
            if (gtk_window_is_active(window) != 0) {
                any = focus
                break
            }
            if (any == null) any = focus
        }
        node = node.pointed.next
    }
    g_list_free(windows)
    return any
}

private const val FRAME_MS = 16L

/** About half a second: how long a new widget may take to reach the screen. */
private const val FRAMES_TO_APPEAR = 30

/** Runs [attempt] once per frame, starting after the next one, until it succeeds or time is up. */
internal suspend fun retryUntilOnScreen(attempt: () -> Boolean) {
    repeat(FRAMES_TO_APPEAR) {
        delay(FRAME_MS)
        if (attempt()) return
    }
}

/**
 * The widgets inside [root] that can take the focus now, in tree order, which is the order of
 * composition. Content of nested popups is not part of it.
 */
internal fun focusableWidgets(root: CPointer<GtkWidget>): List<CPointer<GtkWidget>> {
    val found = mutableListOf<CPointer<GtkWidget>>()
    fun walk(parent: CPointer<GtkWidget>) {
        var child = gtk_widget_get_first_child(parent)
        while (child != null) {
            val usable = gtk_widget_get_mapped(child) != 0 && gtk_widget_is_sensitive(child) != 0 &&
                g_type_name_from_instance(child.reinterpret())?.toKString() != "GtkPopover"
            if (usable) {
                if (gtk_widget_get_focusable(child) != 0 && gtk_widget_get_can_focus(child) != 0) found += child
                walk(child)
            }
            child = gtk_widget_get_next_sibling(child)
        }
    }
    walk(root)
    return found
}

/** Focuses the first widget inside [root] that takes it. */
internal fun focusFirstNow(root: CPointer<GtkWidget>): Boolean =
    focusableWidgets(root).any { gtk_widget_grab_focus(it) != 0 }
