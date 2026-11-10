@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.knshell.compose.Color
import dev.knshell.compose.DEFAULT_CSS
import dev.knshell.compose.GLibDispatcher
import dev.knshell.compose.GLibFrameClock
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kn.gtk.GMainLoop
import kn.gtk.GdkDisplay
import kn.gtk.GtkOrientation
import kn.gtk.GtkWidget
import kn.gtk.GtkWindow
import kn.gtk.GTK_LAYER_SHELL_EDGE_BOTTOM
import kn.gtk.GTK_LAYER_SHELL_EDGE_LEFT
import kn.gtk.GTK_LAYER_SHELL_EDGE_RIGHT
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
import kn.gtk.gtk_layer_auto_exclusive_zone_enable
import kn.gtk.gtk_layer_init_for_window
import kn.gtk.gtk_layer_is_supported
import kn.gtk.gtk_layer_set_anchor
import kn.gtk.gtk_layer_set_exclusive_zone
import kn.gtk.gtk_layer_set_keyboard_mode
import kn.gtk.gtk_layer_set_layer
import kn.gtk.gtk_layer_set_margin
import kn.gtk.gtk_layer_set_namespace
import kn.gtk.gtk_style_context_add_provider
import kn.gtk.gtk_style_context_add_provider_for_display
import kn.gtk.gtk_widget_add_css_class
import kn.gtk.gtk_widget_get_style_context
import kn.gtk.gtk_widget_set_margin_bottom
import kn.gtk.gtk_widget_set_margin_end
import kn.gtk.gtk_widget_set_size_request
import kn.gtk.gtk_widget_set_margin_start
import kn.gtk.gtk_widget_set_margin_top
import kn.gtk.gtk_window_close
import kn.gtk.gtk_window_new
import kn.gtk.gtk_window_present
import kn.gtk.gtk_window_set_child
import kn.gtk.gtk_window_set_decorated
import kn.gtk.gtk_window_set_default_size
import kn.gtk.gtk_window_set_title

/**
 * The single runtime shared by every window of the shell.
 *
 * Owns exactly one GTK initialisation, one GLib main loop, one [GLibDispatcher], one
 * [GLibFrameClock], one coroutine scope and one [Recomposer]. Every window mounts its
 * own [Composition] but they all report to the same recomposer, so state written by a
 * coroutine (or by a Rust service callback) recomposes all windows on the same frame.
 */
internal class ShellRuntime private constructor() {

    private val loop: CPointer<GMainLoop> = g_main_loop_new(null, 0)!!

    private val dispatcher = GLibDispatcher()
    private val frameClock = GLibFrameClock()

    // The frame clock must be part of the *calling* coroutine context: Recomposer's
    // recompositionRunner() reads `coroutineContext.monotonicFrameClock` from it.
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + frameClock)
    private val recomposer = Recomposer(scope.coroutineContext)

    private val windows = mutableListOf<RuntimeWindow>()
    private val installedCss = mutableSetOf<String>()
    private var nextWindowId = 0
    private var nextCssClass = 0
    private var gtkReady = false

    init {
        ensureGtk()
    }

    val hasWindows: Boolean get() = windows.isNotEmpty()

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
        frameClock.startTicker()
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        println("[shell-runtime] GTK initialised — one GLib loop, one dispatcher, one Recomposer")
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
        gtk_window_set_default_size(window, width, height)

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

        val composition = Composition(applier = GtkApplier(GtkBoxNode(rootWidget)), parent = recomposer)
        val record = RuntimeWindow(
            runtime = this,
            id = nextWindowId++,
            window = window,
            title = title,
            composition = composition,
        )
        // The close-request handler receives this StableRef as user_data; it is kept in the
        // record so the pointer stays valid until the window is destroyed.
        record.selfRef = StableRef.create(record)
        windows += record

        composition.setContent(content)

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
        gtk_window_present(window)
        println(
            "[shell-runtime] window #${record.id} \"$title\" " +
                (if (bar != null) "(layer-shell bar, namespace=${bar.namespace})" else "(plain)") +
                " mounted; ${windows.size} window(s) on the shared runtime"
        )
        return WindowHandle(window) { closeWindow(record) }
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

    private fun closeWindow(record: RuntimeWindow) {
        if (record.closed || windows.none { it === record }) return
        gtk_window_close(record.window)
    }

    private fun onWindowClosed(record: RuntimeWindow) {
        if (record.closed) return
        record.closed = true
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
    }
}

/** A window mounted on the runtime; tracked until its close-request fires. */
private class RuntimeWindow(
    val runtime: ShellRuntime,
    val id: Int,
    val window: CPointer<GtkWindow>,
    val title: String,
    val composition: Composition,
) {
    var selfRef: StableRef<RuntimeWindow>? = null
    var closed: Boolean = false
}

/**
 * Turns [window] into a wlr-layer-shell surface according to [spec].
 *
 * Requires a wlroots-based compositor (Hyprland / Sway / Niri). On GNOME/Mutter the
 * protocol is not implemented and `gtk_layer_is_supported()` returns false.
 */
private fun configureLayerShell(window: CPointer<GtkWindow>, spec: BarWindowSpec) {
    if (gtk_layer_is_supported() == 0) {
        println("[shell-ui] gtk4-layer-shell not supported by this compositor; using a normal window")
        return
    }
    gtk_layer_init_for_window(window)
    gtk_layer_set_namespace(window, spec.namespace)
    gtk_layer_set_layer(window, spec.layer)

    if (BarAnchor.Top in spec.anchors) {
        gtk_layer_set_anchor(window, GTK_LAYER_SHELL_EDGE_TOP, 1)
        gtk_layer_set_margin(window, GTK_LAYER_SHELL_EDGE_TOP, spec.margins.top)
    }
    if (BarAnchor.Bottom in spec.anchors) {
        gtk_layer_set_anchor(window, GTK_LAYER_SHELL_EDGE_BOTTOM, 1)
        gtk_layer_set_margin(window, GTK_LAYER_SHELL_EDGE_BOTTOM, spec.margins.bottom)
    }
    if (BarAnchor.Left in spec.anchors) {
        gtk_layer_set_anchor(window, GTK_LAYER_SHELL_EDGE_LEFT, 1)
        gtk_layer_set_margin(window, GTK_LAYER_SHELL_EDGE_LEFT, spec.margins.left)
    }
    if (BarAnchor.Right in spec.anchors) {
        gtk_layer_set_anchor(window, GTK_LAYER_SHELL_EDGE_RIGHT, 1)
        gtk_layer_set_margin(window, GTK_LAYER_SHELL_EDGE_RIGHT, spec.margins.right)
    }

    val zone = spec.exclusiveZone
    when (zone) {
        ExclusiveZone.Auto -> gtk_layer_auto_exclusive_zone_enable(window)
        ExclusiveZone.None -> gtk_layer_set_exclusive_zone(window, 0)
        is ExclusiveZone.Fixed -> gtk_layer_set_exclusive_zone(window, zone.pixels)
    }
    gtk_layer_set_keyboard_mode(window, spec.keyboardMode)

    println(
        "[shell-ui] layer-shell surface initialised (namespace=${spec.namespace}, " +
            "layer=${spec.layer}, anchors=${spec.anchors}, exclusive=${spec.exclusiveZone})"
    )
}

/**
 * Default rules for windows created through [ShellScope]: the shell's window background
 * and fully transparent layer-shell bars.
 */
private const val RUNTIME_CSS: String = """
window.kn-window {
    background-color: rgba(18, 18, 24, 0.85);
}
window.kn-window.kn-bar {
    background-color: transparent;
}
"""
