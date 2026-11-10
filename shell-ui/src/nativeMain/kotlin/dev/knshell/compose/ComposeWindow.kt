@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import dev.knshell.shell.BarAnchor
import dev.knshell.shell.BarWindowSpec
import dev.knshell.shell.Margins
import dev.knshell.shell.ShellRuntime
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kn.gtk.g_idle_add
import kn.gtk.g_timeout_add

/**
 * Runs coroutine work on the GLib main loop thread (the thread that owns GTK).
 *
 * Recomposer applies UI changes from this dispatcher, which guarantees that all
 * `gtk_*` calls happen on the main thread. GTK has no thread-safe widget API, so
 * this is the single most important piece of the bridge.
 */
class GLibDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val ref = StableRef.create(block)
        g_idle_add(
            staticCFunction { data: COpaquePointer? ->
                val runnableRef = data?.asStableRef<Runnable>()
                runnableRef?.get()?.run()
                runnableRef?.dispose()
                0 // G_SOURCE_REMOVE
            }.reinterpret(),
            ref.asCPointer(),
        )
    }
}

/**
 * Compose frame clock driven by a 16 ms GLib timeout (~60 fps).
 *
 * Compose Runtime asks for a frame via `withFrameNanos`; we publish one tick per
 * timeout, so animations and recomposition advance in lock-step with the GTK main loop.
 */
class GLibFrameClock : MonotonicFrameClock {
    private val start = TimeSource.Monotonic.markNow()

    // Every suspended `withFrameNanos` caller is registered here and resumed on each
    // tick. This is the same broadcast semantics Compose's BroadcastFrameClock has:
    // multiple animations must all receive the same frame.
    private val waiters = mutableListOf<CancellableContinuation<Long>>()

    // Retained: the GLib timeout keeps the raw pointer as user_data.
    private val tickerRef by lazy { StableRef.create(this) }

    fun startTicker() {
        val ref = tickerRef
        g_timeout_add(
            16u,
            staticCFunction { data: COpaquePointer? ->
                data?.asStableRef<GLibFrameClock>()?.get()?.tick()
                1 // G_SOURCE_CONTINUE
            }.reinterpret(),
            ref.asCPointer(),
        )
    }

    private fun tick() {
        // Compose observes state changes through the snapshot apply notifications. On
        // Android/Compose Desktop the platform calls this; in this GTK bridge nothing
        // else does, and without it the Recomposer never learns about writes made from
        // coroutines (Rust service callbacks, clocks, animations) — the UI freezes after
        // the initial composition.
        Snapshot.sendApplyNotifications()

        val nanos = start.elapsedNow().inWholeNanoseconds
        if (waiters.isEmpty()) return
        val pending = waiters.toList()
        waiters.clear()
        pending.forEach { waiter ->
            waiter.resume(nanos) { }
        }
    }

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        val nanos = suspendCancellableCoroutine<Long> { continuation ->
            waiters += continuation
            continuation.invokeOnCancellation { waiters.remove(continuation) }
        }
        return onFrame(nanos)
    }
}

const val DEFAULT_CSS: String = """
window {
    background-color: rgba(18, 18, 24, 0.72);
    color: #e8e8ee;
}
window.kn-bar {
    background-color: rgba(14, 14, 20, 0.78);
}
label {
    color: #e8e8ee;
    font-size: 14px;
}
button {
    background-color: rgba(70, 70, 100, 0.85);
    color: #ffffff;
    border-radius: 8px;
    padding: 6px 14px;
    border: none;
}
button:hover {
    background-color: rgba(100, 100, 145, 0.9);
}
scale {
    padding: 4px 8px;
}
.kn-panel { padding: 8px 12px; }
"""

/**
 * Creates a GTK window, mounts a Compose composition into it and blocks in the
 * GLib main loop.
 *
 * Legacy entry point kept for the existing run modes; it delegates to the shared
 * [ShellRuntime] (one GTK init / loop / Recomposer per process).
 */
fun runComposeWindow(
    title: String,
    width: Int,
    height: Int,
    css: String = DEFAULT_CSS,
    layerShell: Boolean = false,
    barAtTop: Boolean = false,
    windowBackground: Color = Color.hex(0x121218, 0.85f),
    windowModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val runtime = ShellRuntime.instance()
    runtime.installCss(css)

    // Reuse the exact layer-shell geometry the previous implementation hard-coded.
    val barSpec: BarWindowSpec? = when {
        !layerShell -> null
        barAtTop -> BarWindowSpec()
        else -> BarWindowSpec(
            anchors = setOf(BarAnchor.Bottom, BarAnchor.Left, BarAnchor.Right),
            margins = Margins(top = 0, left = 0, right = 0, bottom = 0),
        )
    }

    runtime.createWindow(
        title = title,
        width = width,
        height = height,
        // Layer-shell bars stay transparent (RUNTIME_CSS); plain windows keep the
        // legacy configurable background.
        background = if (layerShell) null else windowBackground,
        decorations = true,
        cssClass = null,
        bar = barSpec,
        windowModifier = windowModifier,
        content = content,
    )
    println("[shell-ui] Compose composition mounted, entering GLib main loop")
    runtime.run()
}
