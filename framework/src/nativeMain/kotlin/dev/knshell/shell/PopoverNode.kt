// The internal GTK popover node: surface creation and anchoring, the hover/click show-hide
// timers and the nested content composition it mounts (created by `Popover()` in Popover.kt).

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import dev.knshell.compose.GtkNode
import dev.knshell.compose.PLAIN_CLASS
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kn.gtk.GtkBox
import kn.gtk.GtkEventController
import kn.gtk.GtkOrientation
import kn.gtk.GtkPopover
import kn.gtk.GtkWidget
import kn.gtk.g_source_remove
import kn.gtk.g_signal_connect_data
import kn.gtk.g_timeout_add
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_new
import kn.gtk.gtk_box_remove
import kn.gtk.gtk_event_controller_motion_new
import kn.gtk.gtk_gesture_click_new
import kn.gtk.gtk_popover_new
import kn.gtk.gtk_popover_popdown
import kn.gtk.gtk_popover_popup
import kn.gtk.gtk_css_provider_load_from_string
import kn.gtk.gtk_css_provider_new
import kn.gtk.gtk_popover_set_autohide
import kn.gtk.gtk_popover_set_child
import kn.gtk.gtk_popover_set_has_arrow
import kn.gtk.gtk_style_context_add_provider_for_display
import kn.gtk.gtk_widget_get_display
import kn.gtk.gtk_widget_queue_resize
import kn.gtk.gtk_widget_add_controller
import kn.gtk.gtk_widget_add_css_class
import kn.gtk.gtk_widget_set_parent

internal class GtkHoverPopoverNode(
    private val delayMs: Int,
    private val hideDelayMs: Int,
    private val openOnHover: Boolean,
    private val openOnClick: Boolean,
) : GtkNode(gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, 0)!!) {

    internal var controller: PopoverController? = null

    /** True while the popover is visible. */
    internal val isShown: Boolean get() = shown

    /** Latest content lambda; the mounted composition reads it, so updates propagate. */
    internal val contentState = mutableStateOf<@Composable () -> Unit>({})

    private val popover: CPointer<GtkWidget>? = gtk_popover_new()
    private val contentBox: CPointer<GtkWidget> =
        gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!

    private var composition: Composition? = null
    private var popoverCssInstalled = false
    private var shown = false
    private var showSource = 0u
    private var hideSource = 0u
    private var popupSource = 0u
    private var pendingPopup = false
    private var closedByUser = false

    @Suppress("unused")
    private val selfRef = StableRef.create(this)

    private val showCallback = staticCFunction { data: COpaquePointer? ->
        data?.asStableRef<GtkHoverPopoverNode>()?.get()?.showNow()
        0 // G_SOURCE_REMOVE
    }

    private val hideCallback = staticCFunction { data: COpaquePointer? ->
        data?.asStableRef<GtkHoverPopoverNode>()?.get()?.hideNow()
        0 // G_SOURCE_REMOVE
    }

    private val popupCallback = staticCFunction { data: COpaquePointer? ->
        data?.asStableRef<GtkHoverPopoverNode>()?.get()?.popupNow()
        0 // G_SOURCE_REMOVE
    }

    init {
        gtk_widget_add_css_class(widget, PLAIN_CLASS)
        gtk_widget_add_css_class(contentBox, PLAIN_CLASS)

        val popupWidget = popover
        if (popupWidget != null) {
            val typed = popupWidget.reinterpret<GtkPopover>()
            gtk_widget_add_css_class(popupWidget, PLAIN_CLASS)
            gtk_widget_add_css_class(popupWidget, POPOVER_CLASS)
            installPopoverCss(popupWidget)
            gtk_popover_set_child(typed, contentBox)
            // No arrow (waybar-like tooltip) and no autohide: in a layer-shell bar autohide
            // can close the popover right after popup() and the hover logic reopens it,
            // which looks like blinking. Closing is driven by the hover timers instead.
            gtk_popover_set_has_arrow(typed, 0)
            // Click popovers rely on GTK autohide to close on outside clicks; hover popovers
            // keep it off (autohide + layer-shell caused popdown/popup blinking).
            gtk_popover_set_autohide(typed, if (openOnClick && !openOnHover) 1 else 0)
            // Re-parenting is the GTK4 way to anchor a popover to a widget.
            gtk_widget_set_parent(popupWidget, widget)

            val closed = staticCFunction { _: COpaquePointer?, data: COpaquePointer? ->
                data?.asStableRef<GtkHoverPopoverNode>()?.get()?.onClosedByUser()
                Unit
            }
            g_signal_connect_data(
                instance = popupWidget,
                detailed_signal = "closed",
                c_handler = closed.reinterpret(),
                data = selfRef.asCPointer(),
                destroy_data = null,
                connect_flags = 0u,
            )
        }

        if (openOnHover) {
            attachHover(widget)
            if (popover != null) attachHover(popover)
        }
        if (openOnClick) attachClick(widget)

        // Development aid: KN_POPOVER_DEBUG=1 pops every popover once, so layout and
        // styling can be checked without moving the pointer.
        if (getenv("KN_POPOVER_DEBUG")?.toKString() == "1") {
            showSource = g_timeout_add(2000u, showCallback, selfRef.asCPointer())
        }
    }

    override fun attachChild(child: CPointer<GtkWidget>) {
        gtk_box_append(widget.reinterpret<GtkBox>(), child)
    }

    override fun detachChild(child: CPointer<GtkWidget>) {
        gtk_box_remove(widget.reinterpret<GtkBox>(), child)
    }

    /** Called by the composable whenever a new content lambda is available. */
    private fun ensureComposition() {
        if (composition != null) return
        composition = ShellRuntime.instance().mountCompose(contentBox) {
            contentState.value()
            // The popover surface caches its size from the first presentation; without this
            // a content update (wifi details arriving, changing rates) is clipped inside the
            // old allocation. Queue a resize on every content recomposition.
            SideEffect {
                // Resize the content box, not the popover: queueing a resize on the popover
                // while GTK is mid-positioning can hit gtk_widget_is_ancestor on a stale
                // relative-to widget. The popover follows its content size anyway.
                gtk_widget_queue_resize(contentBox)
            }
        }
    }

    /**
     * The popover frame/shadow is drawn on the `contents` CSS node, and a provider attached
     * to the popover widget's style context does not reach it reliably. Install the reset on
     * the display instead (once), like the app-wide plain reset.
     */
    private fun installPopoverCss(popoverWidget: CPointer<GtkWidget>) {
        if (popoverCssInstalled) return
        val display = gtk_widget_get_display(popoverWidget) ?: return
        val provider = gtk_css_provider_new() ?: return
        gtk_css_provider_load_from_string(provider, POPOVER_CSS)
        gtk_style_context_add_provider_for_display(display, provider.reinterpret(), 700u)
        popoverCssInstalled = true
    }

    private fun attachClick(target: CPointer<GtkWidget>) {
        val gesture = gtk_gesture_click_new() ?: return
        val ref = selfRef
        val pressed = staticCFunction { _: COpaquePointer?, _: Int, _: Double, _: Double, data: COpaquePointer? ->
            data?.asStableRef<GtkHoverPopoverNode>()?.get()?.onTriggerClicked()
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
        gtk_widget_add_controller(target, gesture.reinterpret<GtkEventController>())
    }

    private fun onTriggerClicked() {
        cancelledShow()
        cancelledHide()
        if (shown) hideNow() else showNow()
    }

    override fun cancelAnimations() {
        super.cancelAnimations()
        controller?.detach()
        controller = null
    }

    private fun attachHover(target: CPointer<GtkWidget>) {
        val motion = gtk_event_controller_motion_new() ?: return
        val ref = selfRef
        val enter = staticCFunction { _: COpaquePointer?, _: Double, _: Double, data: COpaquePointer? ->
            data?.asStableRef<GtkHoverPopoverNode>()?.get()?.onHoverEnter()
            Unit
        }
        val leave = staticCFunction { _: COpaquePointer?, data: COpaquePointer? ->
            data?.asStableRef<GtkHoverPopoverNode>()?.get()?.onHoverLeave()
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
        gtk_widget_add_controller(target, motion)
    }

    private fun onHoverEnter() {
        cancelledShow()
        cancelledHide()
        closedByUser = false
        if (shown) return
        showSource = g_timeout_add(delayMs.toUInt().coerceAtLeast(1u), showCallback, selfRef.asCPointer())
    }

    private fun onHoverLeave() {
        cancelledShow()
        // Only hide when something is actually visible; leaving the trigger before the
        // delay elapsed must not schedule a hide that was never needed.
        if (shown) scheduleHide()
    }

    private fun scheduleHide() {
        cancelledHide()
        hideSource = g_timeout_add(hideDelayMs.toUInt().coerceAtLeast(1u), hideCallback, selfRef.asCPointer())
    }

    private fun cancelledShow() {
        if (showSource != 0u) {
            g_source_remove(showSource)
            showSource = 0u
        }
    }

    private fun cancelledHide() {
        if (hideSource != 0u) {
            g_source_remove(hideSource)
            hideSource = 0u
        }
    }

    internal fun showNow() {
        showSource = 0u
        if (popover == null || shown || pendingPopup) return
        ensureComposition()
        // The popover negotiates its size from the child during popup(); mounting the
        // composition and popping in the same call measures a not-yet-laid-out tree (tiny
        // popover) and can trip GTK's ancestor check. Wait one frame instead.
        pendingPopup = true
        popupSource = g_timeout_add(16u, popupCallback, selfRef.asCPointer())
    }

    internal fun popupNow() {
        popupSource = 0u
        pendingPopup = false
        val popup = popover ?: return
        if (shown) return
        gtk_popover_popup(popup.reinterpret<GtkPopover>())
        shown = true
    }

    internal fun hideNow() {
        hideSource = 0u
        if (pendingPopup) {
            pendingPopup = false
            if (popupSource != 0u) {
                g_source_remove(popupSource)
                popupSource = 0u
            }
        }
        val popup = popover ?: return
        if (!shown) return
        gtk_popover_popdown(popup.reinterpret<GtkPopover>())
        shown = false
        // Drop the content composition: the popover surface caches the size it negotiated
        // on the first presentation, so a later show with fresh data must mount anew.
        composition?.dispose()
        composition = null
    }

    private fun onClosedByUser() {
        shown = false
        cancelledShow()
        // The surface was dismissed by GTK (outside click); drop the composition so the next
        // open negotiates its size from scratch.
        composition?.dispose()
        composition = null
    }

}
