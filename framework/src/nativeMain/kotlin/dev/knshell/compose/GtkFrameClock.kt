// The Compose frame clock: one monotonic GTK-driven timeline per process, lazy tick
// callback installation, `withFrameNanos` broadcast, and the per-tick order
// (apply notifications -> frame -> node animations). GTK interop lives in
// GtkInterop.kt, waiter/notification bookkeeping in FrameCallbacks.kt.
@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import dev.knshell.animation.SizeAnimationRegistry
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.time.TimeSource
import kn.gtk.GdkFrameClock
import kn.gtk.GtkWidget
import kn.gtk.gdk_frame_clock_get_frame_time

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
 * Threading: everything except [SnapshotNotifier.scheduleNotify] must run on the GTK main thread (that is the
 * thread that owns `GLibDispatcher`, the `Recomposer` and every widget). `scheduleNotify` is
 * the only entry point reachable from a foreign thread: it only touches an atomic flag and
 * calls `g_idle_add()`, which GLib documents as thread-safe.
 *
 * @see MonotonicFrameClock
 */
internal class GtkFrameClock : MonotonicFrameClock {

    /** Host monotonic source: frame-time fallback and the coalescing clock. */
    private val monotonic = TimeSource.Monotonic.markNow()

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

    /** Suspended `withFrameNanos` callers; broadcast semantics live in [FrameCallbacks]. */
    private val frames = FrameCallbacks()

    /** Snapshot observers + the deduplicated apply-notification idle (see [SnapshotNotifier]). */
    private val snapshots = SnapshotNotifier(::ensureTicking)

    /**
     * Starts the clock: registers the snapshot observers that replace the old
     * `g_timeout_add(16)` polling loop. Must be called once, on the GTK main thread,
     * before the first window is created. Does **not** install any tick callback — frames
     * start only when [withFrameNanos] or [SizeAnimationRegistry] asks for them.
     */
    fun start() {
        if (snapshots.started) return
        println("[frameclock] started ticks=0 (lazy: tick callback installed on demand)")
        snapshots.start()
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
            frames.add(continuation)
            continuation.invokeOnCancellation { frames.remove(continuation) }
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
    private fun needsFrames(): Boolean = frames.isNotEmpty() || SizeAnimationRegistry.hasWork

    private fun ensureTicking() {
        if (!needsFrames()) return
        // Iterate a copy: nothing mutates `bindings` from inside, but installing may run GTK
        // code and this keeps the loop robust.
        for (binding in bindings.toList()) {
            if (!binding.installed) install(binding)
        }
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
        frames.dispatch(nanos)

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

    /**
     * Periodic diagnostic line for `scripts/anim-bench.sh` (`idle.ticks` gate): total frames
     * produced so far plus current subscription state. Parsed as
     * `[frameclock] ticks=N waiters=W registry=R`.
     */
    private fun logTickStats() {
        println("[frameclock] ticks=$tickCount waiters=${frames.pending} registry=${SizeAnimationRegistry.hasWork} ${SizeAnimationRegistry.stats()}")
    }


    private companion object {
        /** `gdk_frame_clock_get_frame_time()` reports microseconds. */
        const val MICROS_TO_NANOS = 1_000L

        const val MIN_FRAME_DT_NANOS = 1_000_000L // 1 ms
        const val MAX_FRAME_DT_NANOS = 50_000_000L // 50 ms
        const val DEFAULT_FRAME_DT_NANOS = 16_666_667L // one 60 Hz frame

        /** Sibling-window coalescing window; well below the shortest refresh period. */
        const val MIN_FRAME_GAP_NANOS = 2_000_000L // 2 ms
    }
}
