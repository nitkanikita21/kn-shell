@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import dev.knshell.animation.SizeAnimationRegistry
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.AtomicInt
import kotlin.time.TimeSource
import kn.gtk.GdkFrameClock
import kn.gtk.GtkWidget
import kn.gtk.g_idle_add
import kn.gtk.gdk_frame_clock_get_frame_time
import kn.gtk.gtk_widget_add_tick_callback
import kn.gtk.gtk_widget_remove_tick_callback

/**
 * Compose [MonotonicFrameClock] driven by the GTK frame clock (`GdkFrameClock`).
 *
 * This replaces the old `GLibFrameClock`, which woke the process up 62.5 times per second
 * through `g_timeout_add(16)` even when nothing was animating. Here frames come from GTK
 * itself:
 *
 *  - **time source**: `gdk_frame_clock_get_frame_time()` (microseconds) as reported by the
 *    `GdkFrameClock` passed to the tick callback, converted to nanoseconds. If the clock is
 *    missing, the host [TimeSource.Monotonic] is used instead;
 *  - **one timeline**: every window installs a tick callback on its own root widget, but all
 *    of them feed this single clock, so `Recomposer` (which holds one clock in its coroutine
 *    scope) sees one monotonic frame stream. Sibling windows that tick in the same compositor
 *    frame are coalesced into one published frame, otherwise animations would run twice as
 *    fast in a multi-window shell;
 *  - **lazy**: tick callbacks exist only while someone needs frames — a suspended
 *    `withFrameNanos` waiter or a running [SizeAnimationRegistry] animation. When the last of
 *    them goes away the callbacks remove themselves and the process is completely idle
 *    (zero wake-ups);
 *  - **snapshot notifications**: `Snapshot.sendApplyNotifications()` is no longer polled by a
 *    timer. It is scheduled by a snapshot apply observer (and by a global write observer, so
 *    that state written from Rust worker threads wakes the UI) into **one** deduplicated
 *    `g_idle_add` that runs on the GLib main loop.
 *
 * Threading: everything except [scheduleNotify] must run on the GTK main thread (that is the
 * thread that owns `GLibDispatcher`, the `Recomposer` and every widget). `scheduleNotify` is
 * the only entry point reachable from a foreign thread: it only touches an atomic flag and
 * calls `g_idle_add()`, which GLib documents as thread-safe.
 *
 * @see MonotonicFrameClock
 */
class GtkFrameClock : MonotonicFrameClock {

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

    /** Host monotonic source: frame-time fallback and the coalescing clock. */
    private val monotonic = TimeSource.Monotonic.markNow()

    // Every suspended `withFrameNanos` caller is registered here and resumed with the same
    // frame time — the same broadcast semantics Compose's BroadcastFrameClock has.
    private val waiters = mutableListOf<CancellableContinuation<Long>>()

    // Root widgets of this process (one per window), main thread only.
    private val bindings = mutableListOf<TickBinding>()

    // Shared, strictly non-decreasing frame timeline in nanoseconds. Compose requires frame
    // times to be monotonic; advancing it by a clamped delta also means an unmapped window
    // that comes back after a pause never produces a giant animation step.
    private var publishedNanos = 0L

    // Host monotonic time of the last published frame, used to coalesce sibling windows.
    private var lastPublishHostNanos = 0L

    // Last host sample used by the monotonic fallback path.
    private var lastFallbackNanos = 0L

    // Retained for exactly one more frame: waiters resumed on this frame re-register from the
    // dispatcher (a later main-loop iteration), so the callbacks must not stop immediately.
    private var graceFrame = false

    // Shared decision of the last published frame; read by coalesced sibling ticks.
    private var keepTicking = false

    /**
     * Diagnostic tick counter: total frames produced since [start].
     *
     * Read from `measure_idle` (via the `[frameclock]` log line, see [logTickStats]) to tell
     * the lazy GTK frame clock apart from app-level timers: app timers (Clock 1 Hz, PlayerChip
     * sheen 30 Hz) wake the process through the GLib loop but never produce a frame-clock
     * tick, while the removed `g_timeout_add(16)` produced ~62.5 ticks/s even in idle.
     */
    private var tickCount = 0L

    // Snapshot plumbing. Observers are process-lifetime; the runtime is a singleton.
    private var applyObserver: ObserverHandle? = null
    private var writeObserver: ObserverHandle? = null
    private var notifyRef: StableRef<GtkFrameClock>? = null

    // Exactly one notify idle may be in flight. 0 = idle, 1 = scheduled/running.
    private val notifyScheduled = AtomicInt(0)

    /**
     * Starts the clock: registers the snapshot observers that replace the old
     * `g_timeout_add(16)` polling loop. Must be called once, on the GTK main thread,
     * before the first window is created. Does **not** install any tick callback — frames
     * start only when [withFrameNanos] or [SizeAnimationRegistry] asks for them.
     */
    fun start() {
        if (applyObserver != null) return
        notifyRef = StableRef.create(this)
        println("[frameclock] started ticks=0 (lazy: tick callback installed on demand)")
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
     * Registers the root widget of a window. Tick callbacks are installed lazily, as soon as
     * frames are needed; the widget is remembered until [unregisterRoot].
     */
    internal fun registerRoot(widget: CPointer<GtkWidget>) {
        if (bindings.any { it.widget.rawValue == widget.rawValue }) return
        bindings += TickBinding(this, widget)
        ensureTicking()
    }

    /**
     * Forgets a window root and removes its tick callback. Called from the window
     * close-request handler, while the widget is still alive.
     */
    internal fun unregisterRoot(widget: CPointer<GtkWidget>) {
        val binding = bindings.firstOrNull { it.widget.rawValue == widget.rawValue } ?: return
        bindings.remove(binding)
        removeTick(binding)
    }

    /**
     * Integration hook for animation code that starts work outside a composition (and
     * therefore outside the `Recomposer`'s `withFrameNanos`): asks for tick callbacks if
     * [SizeAnimationRegistry] reports work. Cheap and idempotent.
     */
    internal fun requestFrames() {
        ensureTicking()
    }

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        val nanos = suspendCancellableCoroutine<Long> { continuation ->
            waiters += continuation
            continuation.invokeOnCancellation { waiters.remove(continuation) }
            // The first waiter (re)installs the tick callbacks; they remove themselves once
            // the last waiter and the last animation are gone.
            ensureTicking()
        }
        return onFrame(nanos)
    }

    // ---------------------------------------------------------------------------------
    // Lazy tick callback management
    // ---------------------------------------------------------------------------------

    /** True when at least one Compose animation or one node-level size animation needs frames. */
    private fun needsFrames(): Boolean = waiters.isNotEmpty() || SizeAnimationRegistry.hasWork

    private fun ensureTicking() {
        if (!needsFrames()) return
        // Iterate a copy: nothing mutates `bindings` from inside, but installing may run GTK
        // code and this keeps the loop robust.
        for (binding in bindings.toList()) {
            if (!binding.installed) install(binding)
        }
    }

    private fun install(binding: TickBinding) {
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

    private fun removeTick(binding: TickBinding) {
        if (!binding.installed) return
        val id = binding.tickId
        // Clear the bookkeeping before the call: `gtk_widget_remove_tick_callback` invokes the
        // destroy notify above synchronously, which disposes the StableRef and resets these.
        binding.installed = false
        binding.tickId = 0u
        gtk_widget_remove_tick_callback(binding.widget, id)
    }

    // ---------------------------------------------------------------------------------
    // Frame production
    // ---------------------------------------------------------------------------------

    /**
     * GTK tick callback body. Runs on the GTK main thread, once per compositor frame while
     * the callback is installed.
     *
     * Order matters: apply snapshot notifications first (so state written since the last
     * frame is visible), then dispatch the frame to Compose waiters, then advance node-level
     * size animations with the very same frame time.
     *
     * @return `TRUE` to keep the callback, `FALSE` to let GTK remove it (which also runs the
     * destroy notify and frees the `StableRef`).
     */
    internal fun onTick(binding: TickBinding, clock: CPointer<GdkFrameClock>?): Int {
        if (!binding.installed) return G_SOURCE_REMOVE

        tickCount++
        if (tickCount == 1L || tickCount % 600L == 0L) logTickStats()

        val host = monotonic.elapsedNow().inWholeNanoseconds
        // Each window has its own GdkFrameClock, but all of them are driven by the same
        // compositor frame. Publish one frame per refresh period so a two-window shell does
        // not run its animations at double speed. The threshold is far below the shortest
        // refresh period in use (6.9 ms at 144 Hz, 4.2 ms at 240 Hz).
        if (lastPublishHostNanos != 0L && host - lastPublishHostNanos < MIN_FRAME_GAP_NANOS) {
            return if (keepTicking) G_SOURCE_CONTINUE else G_SOURCE_REMOVE
        }
        lastPublishHostNanos = host

        // 1. Compose observes state changes through the snapshot apply notifications; on
        //    Android the platform does this, here nothing would without it.
        Snapshot.sendApplyNotifications()

        // 2. Publish the frame to every suspended `withFrameNanos` caller (broadcast: all of
        //    them are resumed with the same nanos).
        val nanos = nextFrameNanos(binding, clock)
        val pending = waiters.toList()
        waiters.clear()
        for (waiter in pending) {
            waiter.resume(nanos) { }
        }

        // 3. Node-level size animations tick on the same frame time and do not register
        //    frame waiters, so they are advanced regardless of Compose's own waiters.
        SizeAnimationRegistry.tick(nanos)

        // 4. Laziness: keep ticking while anyone needs frames, plus one grace frame so that
        //    the waiters resumed above can re-register through the dispatcher before the
        //    callback tears itself down.
        if (needsFrames()) {
            keepTicking = true
            graceFrame = false
        } else if (graceFrame) {
            graceFrame = false
            keepTicking = false
        } else {
            graceFrame = true
            keepTicking = true
        }
        return if (keepTicking) G_SOURCE_CONTINUE else G_SOURCE_REMOVE
    }

    /**
     * Computes the next value of the shared frame timeline.
     *
     * The delta comes from `gdk_frame_clock_get_frame_time()` when GTK provides a clock, and
     * from [TimeSource.Monotonic] otherwise (or when a clock restarted — e.g. the surface was
     * unmapped and remapped — so the new frame time is no longer comparable). Deltas are
     * clamped to 1..50 ms, like `animateFloatAsState` does for its spring, so a long pause can
     * never turn into a single huge animation step.
     */
    private fun nextFrameNanos(binding: TickBinding, clock: CPointer<GdkFrameClock>?): Long {
        val raw: Long? = if (clock != null) {
            val micros = gdk_frame_clock_get_frame_time(clock)
            if (micros > 0L) micros * MICROS_TO_NANOS else null
        } else {
            null
        }

        val delta = if (raw != null && binding.lastRawNanos > 0L && raw >= binding.lastRawNanos) {
            (raw - binding.lastRawNanos).coerceIn(MIN_FRAME_DT_NANOS, MAX_FRAME_DT_NANOS)
        } else {
            // Fallback: no GdkFrameClock, first frame of this root, or a clock reset.
            val now = monotonic.elapsedNow().inWholeNanoseconds
            val previous = lastFallbackNanos
            lastFallbackNanos = now
            if (previous > 0L) {
                (now - previous).coerceIn(MIN_FRAME_DT_NANOS, MAX_FRAME_DT_NANOS)
            } else {
                DEFAULT_FRAME_DT_NANOS
            }
        }

        if (raw != null) binding.lastRawNanos = raw
        publishedNanos += delta
        return publishedNanos
    }

    // ---------------------------------------------------------------------------------
    // Snapshot apply notifications (replaces the old polling in the 16 ms timeout)
    // ---------------------------------------------------------------------------------

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
                val clock = data?.asStableRef<GtkFrameClock>()?.get()
                clock?.onNotifyIdle()
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
        ensureTicking()
    }

    /**
     * Periodic diagnostic line for `scripts/anim-bench.sh` (`idle.ticks` gate): total frames
     * produced so far plus current subscription state. Parsed as
     * `[frameclock] ticks=N waiters=W registry=R`.
     */
    private fun logTickStats() {
        println("[frameclock] ticks=$tickCount waiters=${waiters.size} registry=${SizeAnimationRegistry.hasWork} ${SizeAnimationRegistry.stats()}")
    }

    private companion object {
        const val G_SOURCE_CONTINUE = 1
        const val G_SOURCE_REMOVE = 0

        /** `gdk_frame_clock_get_frame_time()` reports microseconds. */
        const val MICROS_TO_NANOS = 1_000L

        const val MIN_FRAME_DT_NANOS = 1_000_000L // 1 ms
        const val MAX_FRAME_DT_NANOS = 50_000_000L // 50 ms
        const val DEFAULT_FRAME_DT_NANOS = 16_666_667L // one 60 Hz frame

        /** Sibling-window coalescing window; well below the shortest refresh period. */
        const val MIN_FRAME_GAP_NANOS = 2_000_000L // 2 ms
    }
}
