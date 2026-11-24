// Pointer shape of a bar surface: everything that is not an island must be click-through.
//
// A layer-shell bar spans the whole output width, but its content is a few islands on a
// transparent background — and Wayland delivers input to the *surface rectangle*, so the empty
// gaps would swallow clicks meant for the windows behind the bar. wlr-layer-shell has no
// input-region API (gtk4-layer-shell 1.x only does anchors, layer, margins and exclusive zones),
// so the shape is punched directly into `wl_surface`: the clickable area is the union of the
// bounds of every widget carrying `Modifier.inputRegion()`.
//
// The union is a snapshot of GTK's allocation, not a hook into its layout: GTK 4 has no
// allocation signal, and a permanent tick callback would make the whole shell render at 60 fps
// forever. A cheap timer therefore re-reads the allocations a few times a second and only talks
// to the compositor when the union actually changed.

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.shell

import dev.knshell.compose.INPUT_REGION_CLASS
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kn.gtk.GdkDisplay
import kn.gtk.GdkSurface
import kn.gtk.GtkWidget
import kn.gtk.GtkWindow
import kn.gtk.WL_COMPOSITOR_CREATE_REGION
import kn.gtk.WL_MARSHAL_FLAG_DESTROY
import kn.gtk.WL_REGION_ADD
import kn.gtk.WL_REGION_DESTROY
import kn.gtk.WL_SURFACE_COMMIT
import kn.gtk.WL_SURFACE_SET_INPUT_REGION
import kn.gtk.wl_interface
import kn.gtk.g_source_remove
import kn.gtk.g_timeout_add
import kn.gtk.gdk_display_get_name
import kn.gtk.gdk_surface_get_scale_factor
import kn.gtk.gdk_wayland_display_get_wl_compositor
import kn.gtk.gdk_wayland_surface_get_wl_surface
import kn.gtk.graphene_rect_t
import kn.gtk.gtk_native_get_surface
import kn.gtk.gtk_widget_compute_bounds
import kn.gtk.gtk_widget_get_display
import kn.gtk.gtk_widget_get_first_child
import kn.gtk.gtk_widget_get_native
import kn.gtk.gtk_widget_get_next_sibling
import kn.gtk.gtk_widget_has_css_class
import kn.gtk.wl_proxy_get_version
import kn.gtk.wl_proxy_marshal_flags
import kn.gtk.wl_region_interface

/**
 * Keeps one bar window's Wayland input region in sync with the widgets marked
 * `Modifier.inputRegion()`.
 *
 * Owned by the window: [start] once the surface exists, [stop] when the window closes. A window
 * without any marked widget keeps the default region (the whole surface), so a bar that does not
 * opt in behaves exactly as before.
 */
internal class InputRegion(private val window: CPointer<GtkWindow>) {

    /** Last union pushed to the compositor; `null` = the default, full-surface region. */
    private var applied: List<Rect>? = null

    /** `guint` of the repeating check; `0u` when stopped. */
    private var sourceId: UInt = 0u

    private val selfRef = StableRef.create(this)

    /** Starts the periodic geometry check (idempotent). */
    fun start() {
        if (sourceId != 0u) return
        sourceId = g_timeout_add(
            interval = POLL_MS,
            function = staticCFunction { data: COpaquePointer? ->
                data?.asStableRef<InputRegion>()?.get()?.sync()
                G_SOURCE_CONTINUE
            },
            data = selfRef.asCPointer(),
        )
    }

    /** Stops the check and drops the timer source. The region itself is left as it is. */
    fun stop() {
        if (sourceId == 0u) return
        g_source_remove(sourceId)
        sourceId = 0u
    }

    /** Recomputes the union and pushes it when it differs from what the compositor has. */
    internal fun sync() {
        val widget = window.reinterpret<GtkWidget>()
        val native = gtk_widget_get_native(widget) ?: return
        val surface = gtk_native_get_surface(native) ?: return
        val display = gtk_widget_get_display(widget)
        // X11 has neither a wl_surface nor layer shell: nothing to shape.
        if (!isWayland(display)) return

        val next = collectRects(widget)
        if (next == applied) return
        apply(surface, display, next)
        applied = next
        println(
            "[shell-runtime] input region: " +
                if (next.isEmpty()) "default (no Modifier.inputRegion() widget)"
                else next.joinToString(", ") { "${it.width}x${it.height}+${it.x}+${it.y}" },
        )
    }

    private fun apply(surface: CPointer<GdkSurface>, display: CPointer<GdkDisplay>?, rects: List<Rect>) {
        val wlSurface = gdk_wayland_surface_get_wl_surface(surface) ?: return
        if (rects.isEmpty()) {
            // No marked widget: back to the default, full-surface input region.
            setInputRegion(wlSurface, null)
            commit(wlSurface)
            return
        }
        val compositor = gdk_wayland_display_get_wl_compositor(display) ?: return
        val region = createRegion(compositor) ?: return
        // GTK allocates in logical pixels; the surface speaks buffer (physical) pixels.
        val scale = gdk_surface_get_scale_factor(surface).coerceAtLeast(1)
        for (rect in rects) {
            regionAdd(region, rect.x * scale, rect.y * scale, rect.width * scale, rect.height * scale)
        }
        setInputRegion(wlSurface, region)
        regionDestroy(region)
        commit(wlSurface)
    }

    /** Bounds of every `kn-input` widget in [root]'s tree, in [root]-relative logical pixels. */
    private fun collectRects(root: CPointer<GtkWidget>): List<Rect> = memScoped {
        val bounds = alloc<graphene_rect_t>()
        val found = mutableListOf<Rect>()
        fun walk(widget: CPointer<GtkWidget>) {
            if (gtk_widget_has_css_class(widget, INPUT_REGION_CLASS) != 0 &&
                gtk_widget_compute_bounds(widget, root, bounds.ptr) != 0
            ) {
                val x = bounds.origin.x.toInt()
                val y = bounds.origin.y.toInt()
                val width = (bounds.origin.x + bounds.size.width).toInt() - x
                val height = (bounds.origin.y + bounds.size.height).toInt() - y
                // An island mid-recompose can allocate to nothing; an empty rectangle would only
                // add a no-op to the region.
                if (width > 0 && height > 0) found += Rect(x, y, width, height)
            }
            var child = gtk_widget_get_first_child(widget)
            while (child != null) {
                walk(child)
                child = gtk_widget_get_next_sibling(child)
            }
        }
        walk(root)
        found
    }

    /** `wayland-0`, `wayland-1`, … on Wayland; a display string like `:0` on X11. */
    private fun isWayland(display: CPointer<GdkDisplay>?): Boolean {
        val name = display?.let { gdk_display_get_name(it)?.toKString() } ?: return false
        return name.startsWith("wayland")
    }

    // The wl_region / wl_surface requests are `static inline` wrappers around
    // `wl_proxy_marshal_flags`, so cinterop cannot see them as functions: these five helpers are
    // those wrappers, with the opcodes and flags the generated headers use.
    private fun createRegion(compositor: CPointer<cnames.structs.wl_compositor>): CPointer<cnames.structs.wl_region>? {
        val proxy = compositor.reinterpret<cnames.structs.wl_proxy>()
        return wl_proxy_marshal_flags(
            proxy,
            WL_COMPOSITOR_CREATE_REGION.toUInt(),
            wl_region_interface.ptr,
            wl_proxy_get_version(proxy),
            0u,
            null, // new_id: the compositor allocates it
        )?.reinterpret()
    }

    private fun regionAdd(region: CPointer<cnames.structs.wl_region>, x: Int, y: Int, width: Int, height: Int) {
        val proxy = region.reinterpret<cnames.structs.wl_proxy>()
        wl_proxy_marshal_flags(
            proxy,
            WL_REGION_ADD.toUInt(),
            null,
            wl_proxy_get_version(proxy),
            0u,
            x,
            y,
            width,
            height,
        )
    }

    private fun regionDestroy(region: CPointer<cnames.structs.wl_region>) {
        val proxy = region.reinterpret<cnames.structs.wl_proxy>()
        wl_proxy_marshal_flags(
            proxy,
            WL_REGION_DESTROY.toUInt(),
            null,
            wl_proxy_get_version(proxy),
            WL_MARSHAL_FLAG_DESTROY.toUInt(),
        )
    }

    private fun setInputRegion(surface: CPointer<cnames.structs.wl_surface>, region: CPointer<cnames.structs.wl_region>?) {
        val proxy = surface.reinterpret<cnames.structs.wl_proxy>()
        wl_proxy_marshal_flags(
            proxy,
            WL_SURFACE_SET_INPUT_REGION.toUInt(),
            null,
            wl_proxy_get_version(proxy),
            0u,
            region,
        )
    }

    private fun commit(surface: CPointer<cnames.structs.wl_surface>) {
        val proxy = surface.reinterpret<cnames.structs.wl_proxy>()
        wl_proxy_marshal_flags(
            proxy,
            WL_SURFACE_COMMIT.toUInt(),
            null,
            wl_proxy_get_version(proxy),
            0u,
        )
    }

    /** One clickable rectangle, in [collectRects] coordinates. */
    private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    private companion object {
        /** How often the union is re-read (ms): ≤4 wakeups/s, and a reshape within [POLL_MS]. */
        const val POLL_MS: UInt = 250u

        /** `gboolean` returned from a `GSourceFunc`: keep the source alive. */
        const val G_SOURCE_CONTINUE = 1
    }
}
