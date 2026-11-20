// GTK tick-callback interop for [GtkFrameClock]: the per-window [TickBinding]
// bookkeeping and the raw `gtk_widget_add_tick_callback` / remove cinterop calls,
// plus the StableRef destroy-notify that owns the binding's lifetime.
@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kn.gtk.GdkFrameClock
import kn.gtk.GtkWidget
import kn.gtk.gtk_widget_add_tick_callback
import kn.gtk.gtk_widget_remove_tick_callback

/** `gboolean` returned from a GTK tick callback: keep the source. */
internal const val G_SOURCE_CONTINUE = 1

/** `gboolean` returned from a GTK tick callback: let GTK remove the source. */
internal const val G_SOURCE_REMOVE = 0

/**
 * One installed tick callback, owned by the clock (the GTK callback gets a `StableRef`
 * to this object as `user_data`).
 *
 * The `StableRef` lives until GTK invokes the destroy notify (explicit
 * `gtk_widget_remove_tick_callback` or widget destruction) — see [install].
 */
internal class TickBinding(
    val clock: GtkFrameClock,
    val widget: CPointer<GtkWidget>,
) {
    /** `guint` id returned by `gtk_widget_add_tick_callback`; `0u` when not installed. */
    var tickId: UInt = 0u

    /** Kept alive for as long as the GTK callback holds its raw pointer. */
    var stableRef: StableRef<TickBinding>? = null

    var installed: Boolean = false

    /** Last raw `GdkFrameClock` frame time seen by this root (clock-local epoch). */
    var lastRawNanos: Long = 0L
}

internal fun install(binding: TickBinding) {
    if (binding.installed) return
    val ref = StableRef.create(binding)
    binding.stableRef = ref
    binding.installed = true
    // A fresh callback starts a fresh delta: the first frame falls back to the host
    // monotonic source instead of comparing epochs of two different GdkFrameClocks.
    binding.lastRawNanos = 0L
    binding.tickId = gtk_widget_add_tick_callback(
        binding.widget,
        // gboolean (*)(GtkWidget *, GdkFrameClock *, gpointer)
        staticCFunction { _: CPointer<GtkWidget>?, clock: CPointer<GdkFrameClock>?, data: COpaquePointer? ->
            val tick = data?.asStableRef<TickBinding>()?.get()
            if (tick != null) tick.clock.onTick(tick, clock) else G_SOURCE_REMOVE
        }.reinterpret(),
        ref.asCPointer(),
        // Destroy notify: called by GTK when the callback is removed or the widget is
        // destroyed. It is the single owner of the StableRef, so a window torn down
        // without an explicit unregisterRoot still cannot leak it.
        staticCFunction { data: COpaquePointer? ->
            val stable = data?.asStableRef<TickBinding>()
            val tick = stable?.get()
            if (tick != null) {
                tick.installed = false
                tick.tickId = 0u
                tick.stableRef = null
            }
            stable?.dispose()
            Unit
        }.reinterpret(),
    )
}

internal fun removeTick(binding: TickBinding) {
    if (!binding.installed) return
    val id = binding.tickId
    // Clear the bookkeeping before the call: `gtk_widget_remove_tick_callback` invokes the
    // destroy notify above synchronously, which disposes the StableRef and resets these.
    binding.installed = false
    binding.tickId = 0u
    gtk_widget_remove_tick_callback(binding.widget, id)
}
