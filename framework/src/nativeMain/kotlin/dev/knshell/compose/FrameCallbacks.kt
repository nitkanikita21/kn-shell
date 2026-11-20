// Per-frame bookkeeping for [GtkFrameClock]: the broadcast waiter list behind
// `withFrameNanos` (every waiter resumes with the same frame time) and the
// snapshot-apply notification plumbing — one deduplicated `g_idle_add` that calls
// `Snapshot.sendApplyNotifications()` instead of the old 16 ms polling timer.
@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CancellableContinuation
import kotlin.concurrent.AtomicInt
import kn.gtk.g_idle_add

/**
 * Suspended `withFrameNanos` callers of one clock.
 *
 * Broadcast semantics, like Compose's `BroadcastFrameClock`: every waiter registered
 * before a frame is resumed with the same frame time, so all animations advance
 * together. Main thread only.
 */
internal class FrameCallbacks {

    // Registered in `withFrameNanos`, drained by [dispatch] once per published frame.
    private val waiters = mutableListOf<CancellableContinuation<Long>>()

    /** Number of suspended callers; drives the `[frameclock]` diagnostic line. */
    val pending: Int get() = waiters.size

    /** True while at least one caller waits for a frame. */
    fun isNotEmpty(): Boolean = waiters.isNotEmpty()

    /** Registers a suspended caller; it is resumed by the next [dispatch]. */
    fun add(waiter: CancellableContinuation<Long>) {
        waiters += waiter
    }

    /** Drops a caller that was cancelled before its frame arrived. */
    fun remove(waiter: CancellableContinuation<Long>) {
        waiters.remove(waiter)
    }

    /** Resumes every waiter registered so far with [nanos] and clears the list. */
    fun dispatch(nanos: Long) {
        val pending = waiters.toList()
        waiters.clear()
        for (waiter in pending) {
            waiter.resume(nanos) { }
        }
    }
}

/**
 * Snapshot plumbing that replaces the old `g_timeout_add(16)` polling: an apply observer
 * and a global write observer both feed **one** deduplicated `g_idle_add` that calls
 * `Snapshot.sendApplyNotifications()` on the GLib main loop.
 *
 * [scheduleNotify] is the only entry point reachable from a foreign thread (a Rust worker
 * writing `mutableStateOf`): it only touches an atomic flag and calls `g_idle_add()`,
 * which GLib documents as thread-safe. Everything else is main-thread only.
 *
 * @param onApplied invoked after the notifications are dispatched, so a state change that
 *   started node-level work without a Compose frame waiter can re-arm the tick callbacks.
 */
internal class SnapshotNotifier(
    private val onApplied: () -> Unit,
) {

    // Observers are process-lifetime; the runtime is a singleton.
    private var applyObserver: ObserverHandle? = null
    private var writeObserver: ObserverHandle? = null
    private var notifyRef: StableRef<SnapshotNotifier>? = null

    // Exactly one notify idle may be in flight. 0 = idle, 1 = scheduled/running.
    private val notifyScheduled = AtomicInt(0)

    /** True once [start] registered the observers; [start] is idempotent on this flag. */
    val started: Boolean get() = applyObserver != null

    /** Registers the observers. Must be called once, on the GTK main thread. */
    fun start() {
        if (applyObserver != null) return
        notifyRef = StableRef.create(this)
        // Apply observers fire on the thread that applied a snapshot — e.g. `ComposeRuntime`
        // applying `MutableSnapshot`s or a Rust callback committing on a worker thread.
        applyObserver = Snapshot.registerApplyObserver { _, _ -> scheduleNotify() }
        // Global write observers fire synchronously on the thread that writes state. This is
        // what makes a `mutableStateOf` write from a Rust worker thread reach the UI: without
        // it nothing would ever send the apply notifications that wake the Recomposer, which
        // is exactly why the old implementation had to poll every 16 ms. Both observers feed
        // the same deduplicated idle, so a write costs at most one main-loop round trip.
        writeObserver = Snapshot.registerGlobalWriteObserver { scheduleNotify() }
    }

    /**
     * Schedules one `Snapshot.sendApplyNotifications()` on the GLib main loop.
     *
     * Safe to call from any thread: the atomic flag keeps a burst of writes down to a single
     * idle source and `g_idle_add()` is documented as thread-safe.
     */
    private fun scheduleNotify() {
        if (!notifyScheduled.compareAndSet(0, 1)) return
        val ref = notifyRef
        if (ref == null) {
            notifyScheduled.value = 0
            return
        }
        g_idle_add(
            staticCFunction { data: COpaquePointer? ->
                val notifier = data?.asStableRef<SnapshotNotifier>()?.get()
                notifier?.onNotifyIdle()
                G_SOURCE_REMOVE
            }.reinterpret(),
            ref.asCPointer(),
        )
    }

    /** Runs on the GLib main thread (idle source) after at least one state write/applied snapshot. */
    private fun onNotifyIdle() {
        // Clear before sending: a write that lands while notifications are dispatched must
        // schedule a new idle instead of being folded into the round we already advanced.
        notifyScheduled.value = 0
        Snapshot.sendApplyNotifications()
        // A state change may have started node-level work without a Compose frame waiter.
        onApplied()
    }
}
