@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kn.gtk.g_idle_add
import kn.gtk.gdk_display_get_default

/**
 * Runs coroutine work on the GLib main loop thread (the thread that owns GTK).
 *
 * Recomposer applies UI changes from this dispatcher, which guarantees that all
 * `gtk_*` calls happen on the main thread. GTK has no thread-safe widget API, so
 * this is the single most important piece of the bridge.
 */
internal class GLibDispatcher : CoroutineDispatcher() {
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

/** Text colour of a bare window, the one [resetStyles] starts over with. */
internal const val DEFAULT_FOREGROUND: String = "#e8e8ee"

const val DEFAULT_CSS: String = """
window {
    background-color: rgba(18, 18, 24, 0.72);
    color: $DEFAULT_FOREGROUND;
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
 * Closes every window, which makes [runComposeWindow] return. For code that drives windows from the
 * outside, see [launchOnUiThread].
 */
fun closeComposeWindows() = ShellRuntime.instance().closeAll()

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

    // ShellRuntime.instance() has initialised GTK by now: register the kn-plain reset on
    // the default display. Without a display (headless) there is nothing to style.
    val defaultDisplay = gdk_display_get_default()
    if (defaultDisplay != null) {
        installPlainReset(defaultDisplay)
    } else {
        println("[shell-ui] no default GdkDisplay — kn-plain reset not installed")
    }

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
