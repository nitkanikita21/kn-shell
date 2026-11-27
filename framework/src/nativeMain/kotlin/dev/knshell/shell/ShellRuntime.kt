// The process-wide runtime: one GTK initialisation, one GLib loop, one dispatcher, one frame
// clock, one `Recomposer` and the window registry. Layer-shell interop is in LayerShell.kt,
// the per-window record in RuntimeWindow.kt.

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import dev.knshell.compose.Color
import dev.knshell.compose.DEFAULT_CSS
import dev.knshell.compose.GLibDispatcher
import dev.knshell.compose.GtkFrameClock
import dev.knshell.compose.GtkApplier
import dev.knshell.compose.GtkBoxNode
import dev.knshell.compose.GtkNode
import dev.knshell.compose.Modifier
import dev.knshell.compose.installPlainReset
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kn.gtk.gtk_widget_queue_resize
import kn.gtk.GMainLoop
import kn.gtk.GdkDisplay
import kn.gtk.GtkOrientation
import kn.gtk.GtkWidget
import kn.gtk.GtkWindow
import kn.gtk.GTK_LAYER_SHELL_EDGE_LEFT
import kn.gtk.GTK_LAYER_SHELL_EDGE_TOP
import kn.gtk.g_main_loop_new
import kn.gtk.g_main_loop_quit
import kn.gtk.g_main_loop_run
import kn.gtk.g_signal_connect_data
import kn.gtk.gdk_display_get_default
import kn.gtk.gtk_box_new
import kn.gtk.gtk_css_provider_load_from_string
import kn.gtk.gtk_css_provider_new
import kn.gtk.gtk_init
import kn.gtk.gtk_layer_set_margin
import kn.gtk.gtk_style_context_add_provider
import kn.gtk.gtk_style_context_add_provider_for_display
import kn.gtk.gtk_widget_add_css_class
import kn.gtk.gtk_widget_get_style_context
import kn.gtk.gtk_widget_set_margin_bottom
import kn.gtk.gtk_widget_set_margin_end
import kn.gtk.gtk_widget_set_margin_start
import kn.gtk.gtk_widget_set_margin_top
import kn.gtk.gtk_window_close
import kn.gtk.gtk_window_get_child
import kn.gtk.gtk_window_new
import kn.gtk.gtk_window_present
import kn.gtk.gtk_window_set_child
import kn.gtk.gtk_window_set_decorated
import kn.gtk.gtk_window_set_default_size
import kn.gtk.gtk_window_set_title

/** A composition mounted into a widget and the node at its root. */
internal class MountedComposition(val composition: Composition, val root: GtkNode)

/**
 * The single runtime shared by every window of the shell.
 *
 * Owns exactly one GTK initialisation, one GLib main loop, one [GLibDispatcher], one
 * [GtkFrameClock], one coroutine scope and one [Recomposer]. Every window mounts its
 * own [Composition] but they all report to the same recomposer, so state written by a
 * coroutine (or by a Rust service callback) recomposes all windows on the same frame.
 */
internal class ShellRuntime private constructor() {

    private val loop: CPointer<GMainLoop> = g_main_loop_new(null, 0)!!

    private val dispatcher = GLibDispatcher()
    private val frameClock = GtkFrameClock()

    // The frame clock must be part of the *calling* coroutine context: Recomposer's
    // recompositionRunner() reads `coroutineContext.monotonicFrameClock` from it.
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + frameClock)
    private val recomposer = Recomposer(scope.coroutineContext)

    private val windows = mutableListOf<RuntimeWindow>()
    private val installedCss = mutableSetOf<String>()
    private var nextWindowId = 0
    private var nextCssClass = 0
    private var gtkReady = false

    /** What stopped the recomposer. Nothing composed after that reaches the screen. */
    private var compositionFailure: Throwable? = null

    init {
        ensureGtk()
    }

    /** True while at least one window is mounted. */
    val hasWindows: Boolean get() = windows.isNotEmpty()

    /** Number of windows currently mounted. */
    val windowCount: Int get() = windows.size

    /** Initialises GTK, the frame clock and the recomposition loop (once per process). */
    private fun ensureGtk() {
        if (gtkReady) return
        gtkReady = true
        gtk_init()
        installCss(DEFAULT_CSS)
        installCss(RUNTIME_CSS)
        // Widgets created by the Kotlin DSL must be visually plain: the reset stylesheet
        // (class-scoped, priority 600) is installed once, after the application sheet so
        // its class rules win over the element rules above. Per-widget inline styles stay
        // at priority 700 and therefore still override the reset.
        val display: CPointer<GdkDisplay>? = gdk_display_get_default()
        if (display != null) {
            installPlainReset(display)
        } else {
            println("[shell-runtime] no default GdkDisplay — kn-plain reset not installed")
        }
        frameClock.start()
        scope.launch {
            try {
                recomposer.runRecomposeAndApplyChanges()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                compositionFailure = e
                println("[shell-runtime] Recomposer FAILED: ${e.message}")
            }
        }
        println("[shell-runtime] GTK initialised — one GLib loop, one dispatcher, one Recomposer")
    }

    /** Resizes the window; layer-shell surfaces reconfigure on the next frame. */
    internal fun resizeWindow(window: CPointer<GtkWindow>, width: Int, height: Int) {
        gtk_window_set_default_size(window, width, height)
        gtk_widget_queue_resize(window.reinterpret())
    }

    /** Moves a layer-shell surface by setting its left/top margins. */
    internal fun setLayerMargin(window: CPointer<GtkWindow>, left: Int?, top: Int?) {
        if (left != null) gtk_layer_set_margin(window, GTK_LAYER_SHELL_EDGE_LEFT, left)
        if (top != null) gtk_layer_set_margin(window, GTK_LAYER_SHELL_EDGE_TOP, top)
        gtk_widget_queue_resize(window.reinterpret())
    }

    /**
     * Creates a window (plain or layer-shell bar), mounts [content] into it and presents it.
     *
     * Every window gets its own [Composition] and its own root `GtkBox`; they share the
     * runtime's [Recomposer], dispatcher and frame clock.
     */
    fun createWindow(
        title: String,
        width: Int,
        height: Int,
        background: Color?,
        decorations: Boolean,
        cssClass: String?,
        bar: BarWindowSpec?,
        windowModifier: Modifier = Modifier,
        content: @Composable () -> Unit,
    ): WindowHandle {
        ensureGtk()

        val window = gtk_window_new()!!.reinterpret<GtkWindow>()
        gtk_window_set_title(window, title)
        if (width > 0 || height > 0) {
            gtk_window_set_default_size(window, if (width > 0) width else -1, if (height > 0) height else -1)
        }

        val widget = window.reinterpret<GtkWidget>()
        gtk_widget_add_css_class(widget, "kn-window")
        if (bar != null) gtk_widget_add_css_class(widget, "kn-bar")
        cssClass?.let { gtk_widget_add_css_class(widget, it) }
        // Window-level decorations can also be expressed with modifiers.
        GtkNode(widget).applyModifier(windowModifier)

        if (bar != null) {
            configureLayerShell(window, bar)
            // Layer-shell surfaces derive their natural size from the content. With an empty
            // bar GTK falls back to its 200 px default; pin the configured bar height.
        } else {
            gtk_window_set_decorated(window, if (decorations) 1 else 0)
            background?.let { applyWindowBackground(widget, it) }
        }

        val rootWidget = gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0)!!
        if (bar == null) {
            gtk_widget_set_margin_start(rootWidget, 12)
            gtk_widget_set_margin_end(rootWidget, 12)
            gtk_widget_set_margin_top(rootWidget, 6)
            gtk_widget_set_margin_bottom(rootWidget, 6)
        }
        gtk_window_set_child(window, rootWidget)

        val rootNode = GtkBoxNode(rootWidget)
        val composition = Composition(applier = GtkApplier(rootNode), parent = recomposer)
        val record = RuntimeWindow(
            runtime = this,
            id = nextWindowId++,
            window = window,
            title = title,
            composition = composition,
            root = rootNode,
        )
        // The close-request handler receives this StableRef as user_data; it is kept in the
        // record so the pointer stays valid until the window is destroyed.
        record.selfRef = StableRef.create(record)
        windows += record

        composition.setContent(content)

        // Lazy vsync: the frame clock installs its tick callback on the root widget as soon
        // as the first waiter or size animation needs frames; unregister on close.
        frameClock.registerRoot(rootWidget)

        g_signal_connect_data(
            instance = window,
            detailed_signal = "close-request",
            c_handler = staticCFunction { _: COpaquePointer?, data: COpaquePointer? ->
                data?.asStableRef<RuntimeWindow>()?.get()?.let { it.runtime.onWindowClosed(it) }
                0 // FALSE: not handled — let GTK proceed and destroy the window
            }.reinterpret(),
            data = record.selfRef?.asCPointer(),
            destroy_data = null,
            connect_flags = 0u,
        )

        if (bar != null) {
            // Apply the bar size as late as possible: layer-shell computes the surface size
            // when the window is mapped.
        }
        if (bar != null) {
        // Shape the pointer input to the islands: a bar is a full-width transparent surface, and
        // without this every gap between islands would eat clicks meant for the windows below.
        record.inputRegion = InputRegion(window).also { it.start() }
    }
    gtk_window_present(window)
        println(
            "[shell-runtime] window #${record.id} \"$title\" " +
                (if (bar != null) "(layer-shell bar, namespace=${bar.namespace})" else "(plain)") +
                " mounted; ${windows.size} window(s) on the shared runtime"
        )
        return WindowHandle(
            window = window,
            closeAction = { closeWindow(record) },
            initialWidth = width,
            initialHeight = height,
            resizeAction = { w, h -> resizeWindow(window, w, h) },
            marginAction = { left, top -> setLayerMargin(window, left, top) },
        )
    }

    /** Root node of the first mounted window, or `null` while no window is mounted (inspector entry point). */
    internal fun firstWindowRoot(): GtkNode? = windows.firstOrNull()?.root

    /**
     * Waits until the runtime has settled after a change: pending snapshot writes are applied, the
     * [recomposer] has no work left, and GTK has had a few frames to lay out and paint the result.
     *
     * Bounded by [IDLE_TIMEOUT_MS]: an endless animation keeps the recomposer busy, and the caller
     * then gets the state as it is rather than waiting forever. Runs on the GLib dispatcher.
     *
     * Throws [CompositionFailedException] once the composition has failed: the widgets then show
     * what was there before the failure, which must not pass for the result of the last change.
     */
    internal suspend fun awaitIdle() {
        val settled = withTimeoutOrNull(IDLE_TIMEOUT_MS) {
            while (compositionFailure == null) {
                Snapshot.sendApplyNotifications()
                val state = recomposer.currentState.value
                if (state != Recomposer.State.PendingWork && state != Recomposer.State.InactivePendingWork) break
                delay(IDLE_STEP_MS)
            }
            repeat(IDLE_FRAMES) {
                delay(IDLE_STEP_MS)
                Snapshot.sendApplyNotifications()
            }
            true
        }
        compositionFailure?.let { throw CompositionFailedException(it) }
        if (settled == null) {
            println("[shell-runtime] awaitIdle: still busy after $IDLE_TIMEOUT_MS ms, returning anyway")
        }
    }

    /** Blocks in the GLib main loop until the last window is closed. */
    fun run() {
        g_main_loop_run(loop)
    }

    /** Adds an application-level stylesheet once (duplicates are ignored). */
    fun installCss(css: String) {
        if (css.isBlank() || !installedCss.add(css)) return
        val provider = gtk_css_provider_new() ?: return
        gtk_css_provider_load_from_string(provider, css)
        val display: CPointer<GdkDisplay>? = gdk_display_get_default()
        if (display != null) {
            // GTK_STYLE_PROVIDER_PRIORITY_APPLICATION == 600
            gtk_style_context_add_provider_for_display(display, provider.reinterpret(), 600u)
        }
    }

    /** Closes every window; the main loop ends with the last one. */
    fun closeAll() {
        windows.toList().forEach(::closeWindow)
    }

    private fun closeWindow(record: RuntimeWindow) {
        if (record.closed || windows.none { it === record }) return
        gtk_window_close(record.window)
    }

    private fun onWindowClosed(record: RuntimeWindow) {
        if (record.closed) return
        record.closed = true
        val root = gtk_window_get_child(record.window)
        if (root != null) frameClock.unregisterRoot(root.reinterpret())
        record.inputRegion?.stop()
        record.inputRegion = null
        record.composition.dispose()
        windows.remove(record)
        println(
            "[shell-runtime] window #${record.id} \"${record.title}\" closed; " +
                "${windows.size} window(s) remaining"
        )
        if (windows.isEmpty()) {
            println("[shell-runtime] last window closed — quitting GLib main loop")
            g_main_loop_quit(loop)
        }
    }

    /**
     * Applies a per-window background through a private CSS provider.
     *
     * The rule is scoped to a generated class so that plain windows with different
     * backgrounds can coexist in the same runtime.
     */
    private fun applyWindowBackground(widget: CPointer<GtkWidget>, color: Color) {
        val className = "kn-background-${nextCssClass++}"
        gtk_widget_add_css_class(widget, className)

        val provider = gtk_css_provider_new() ?: return
        val css = """
            window.$className { background-color: ${color.toCss()}; }
        """.trimIndent()
        gtk_css_provider_load_from_string(provider, css)

        val context = gtk_widget_get_style_context(widget)
        if (context != null) {
            // 700 = GTK_STYLE_PROVIDER_PRIORITY_APPLICATION + 100 (above the app stylesheet)
            gtk_style_context_add_provider(context, provider.reinterpret(), 700u)
        }
    }

    companion object {
        private var instance: ShellRuntime? = null

        /** The process-wide runtime; created lazily on first use. */
        fun instance(): ShellRuntime = instance ?: ShellRuntime().also { instance = it }

        /** Upper bound of [awaitIdle] in milliseconds. */
        private const val IDLE_TIMEOUT_MS = 2000L

        /** Pause between idle checks, and between the frames given to GTK after the recomposer settled. */
        private const val IDLE_STEP_MS = 20L

        /** Frames granted to GTK after the recomposer settled (about 60 ms in total). */
        private const val IDLE_FRAMES = 3
    }

    /**
     * Mounts a nested Compose composition into an arbitrary GTK container (used by
     * popovers). The returned composition is parented to the same [Recomposer] as the
     * windows, so services and state updates reach it on the shared frame.
     *
     * With [parent] (see `rememberCompositionContext`) the content sees the composition locals of the
     * place that opens it. The caller owns the returned composition and may dispose it when the
     * container is torn down.
     */
    internal fun mountCompose(
        container: CPointer<GtkWidget>,
        parent: CompositionContext? = null,
        content: @Composable () -> Unit,
    ): MountedComposition {
        ensureGtk()
        val root = GtkBoxNode(container)
        val composition = Composition(applier = GtkApplier(root), parent = parent ?: recomposer)
        composition.setContent(content)
        return MountedComposition(composition, root)
    }
}
