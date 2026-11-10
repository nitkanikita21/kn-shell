@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource
import kotlinx.coroutines.Runnable
import kn.gtk.GMainLoop
import kn.gtk.GdkDisplay
import kn.gtk.GtkWindow
import kn.gtk.GTK_LAYER_SHELL_EDGE_BOTTOM
import kn.gtk.GTK_LAYER_SHELL_EDGE_LEFT
import kn.gtk.GTK_LAYER_SHELL_EDGE_RIGHT
import kn.gtk.GTK_LAYER_SHELL_KEYBOARD_MODE_NONE
import kn.gtk.GTK_LAYER_SHELL_LAYER_TOP
import kn.gtk.gtk_layer_auto_exclusive_zone_enable
import kn.gtk.gtk_layer_init_for_window
import kn.gtk.gtk_layer_is_supported
import kn.gtk.gtk_layer_set_anchor
import kn.gtk.gtk_layer_set_keyboard_mode
import kn.gtk.gtk_layer_set_layer
import kn.gtk.gtk_layer_set_margin
import kn.gtk.gtk_layer_set_namespace
import kn.gtk.g_idle_add
import kn.gtk.g_main_loop_new
import kn.gtk.g_main_loop_quit
import kn.gtk.g_main_loop_run
import kn.gtk.g_signal_connect_data
import kn.gtk.g_timeout_add
import kn.gtk.gdk_display_get_default
import kn.gtk.gtk_css_provider_load_from_string
import kn.gtk.gtk_css_provider_new
import kn.gtk.gtk_init
import kn.gtk.gtk_style_context_add_provider_for_display
import kn.gtk.gtk_window_present
import kn.gtk.gtk_window_set_child
import kn.gtk.gtk_window_set_default_size
import kn.gtk.gtk_window_set_title
import kn.gtk.gtk_window_new
import kn.gtk.gtk_widget_set_margin_bottom
import kn.gtk.gtk_widget_set_margin_end
import kn.gtk.gtk_widget_set_margin_start
import kn.gtk.gtk_widget_set_margin_top

/** The GLib loop of the running shell (single window for the MVP). */
private var mainLoop: CPointer<GMainLoop>? = null

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

private fun installCss(css: String) {
    val provider = gtk_css_provider_new()
    gtk_css_provider_load_from_string(provider, css)
    val display: CPointer<GdkDisplay>? = gdk_display_get_default()
    if (display != null && provider != null) {
        // GTK_STYLE_PROVIDER_PRIORITY_APPLICATION == 600
        gtk_style_context_add_provider_for_display(display, provider.reinterpret(), 600u)
    }
}

/**
 * Turns the window into a wlr-layer-shell surface.
 *
 * Requires a wlroots-based compositor (Hyprland / Sway / Niri). On GNOME/Mutter
 * the protocol is not implemented and `gtk_layer_is_supported()` returns false.
 */
private fun configureLayerShell(window: CPointer<GtkWindow>) {
    if (gtk_layer_is_supported() == 0) {
        println("[shell-ui] gtk4-layer-shell not supported by this compositor; using a normal window")
        return
    }
    gtk_layer_init_for_window(window)
    gtk_layer_set_namespace(window, "kn-shell")
    gtk_layer_set_layer(window, GTK_LAYER_SHELL_LAYER_TOP)
    // Bottom bar: anchored to bottom + left + right.
    gtk_layer_set_anchor(window, GTK_LAYER_SHELL_EDGE_BOTTOM, 1)
    gtk_layer_set_anchor(window, GTK_LAYER_SHELL_EDGE_LEFT, 1)
    gtk_layer_set_anchor(window, GTK_LAYER_SHELL_EDGE_RIGHT, 1)
    gtk_layer_set_margin(window, GTK_LAYER_SHELL_EDGE_BOTTOM, 0)
    gtk_layer_auto_exclusive_zone_enable(window)
    gtk_layer_set_keyboard_mode(window, GTK_LAYER_SHELL_KEYBOARD_MODE_NONE)
    println("[shell-ui] layer-shell surface initialised (layer=TOP, anchors=bottom/left/right)")
}

/**
 * Creates a GTK window, mounts a Compose composition into it and blocks in the
 * GLib main loop.
 */
fun runComposeWindow(
    title: String,
    width: Int,
    height: Int,
    css: String = DEFAULT_CSS,
    layerShell: Boolean = false,
    content: @Composable () -> Unit,
) {
    gtk_init()
    installCss(css)

    val window = gtk_window_new()!!.reinterpret<GtkWindow>()
    gtk_window_set_title(window, title)
    gtk_window_set_default_size(window, width, height)
    if (layerShell) {
        configureLayerShell(window)
        kn.gtk.gtk_widget_add_css_class(window.reinterpret<kn.gtk.GtkWidget>(), "kn-bar")
    }

    val rootWidget = kn.gtk.gtk_box_new(kn.gtk.GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!
    gtk_widget_set_margin_start(rootWidget, 12)
    gtk_widget_set_margin_end(rootWidget, 12)
    gtk_widget_set_margin_top(rootWidget, 6)
    gtk_widget_set_margin_bottom(rootWidget, 6)
    gtk_window_set_child(window, rootWidget)

    val root = GtkBoxNode(rootWidget)
    val applier = GtkApplier(root)

    val dispatcher = GLibDispatcher()
    val frameClock = GLibFrameClock().also { it.startTicker() }
    // The frame clock must be part of the *calling* coroutine context: Recomposer's
    // recompositionRunner() reads `coroutineContext.monotonicFrameClock` from it.
    val scope = CoroutineScope(SupervisorJob() + dispatcher + frameClock)
    val recomposer = Recomposer(scope.coroutineContext)
    scope.launch { recomposer.runRecomposeAndApplyChanges() }

    val composition = Composition(applier = applier, parent = recomposer)
    composition.setContent(content)

    val loop = g_main_loop_new(null, 0)
    mainLoop = loop
    g_signal_connect_data(
        instance = window,
        detailed_signal = "close-request",
        c_handler = staticCFunction { _: COpaquePointer?, _: COpaquePointer? ->
            mainLoop?.let { g_main_loop_quit(it) }
            0
        }.reinterpret(),
        data = null,
        destroy_data = null,
        connect_flags = 0u,
    )

    gtk_window_present(window)
    println("[shell-ui] Compose composition mounted, entering GLib main loop")
    g_main_loop_run(loop)
}
