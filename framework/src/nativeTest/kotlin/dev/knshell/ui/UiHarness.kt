@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import dev.knshell.inspect.InspectNode
import dev.knshell.inspect.Inspector
import dev.knshell.inspect.VirtualKeyboard
import dev.knshell.inspect.windowInspector
import dev.knshell.shell.ShellRuntime
import dev.knshell.shell.WindowHandle
import kotlinx.cinterop.ExperimentalForeignApi
import kn.gtk.g_main_context_iteration
import kn.gtk.gtk_init_check
import platform.posix.usleep

/** A real window with [content], driven from a test: the main loop runs only inside [settle]. */
class UiHarness(content: @Composable () -> Unit) {
    private val window: WindowHandle
    val inspector: Inspector
    val keyboard: VirtualKeyboard = checkNotNull(VirtualKeyboard.fromEnvironment()) {
        "these tests run under headless-compositor (linuxX64GraphicsTest)"
    }

    init {
        check(gtk_init_check() != 0) { "UiHarness needs a display connection" }
        window = ShellRuntime.instance().createWindow(
            title = "test",
            width = 600,
            height = 400,
            background = null,
            decorations = false,
            cssClass = null,
            bar = null,
            content = content,
        )
        inspector = windowInspector()
        settle(300)
    }

    /** Runs the main loop for [ms], which lets recomposition, layout and events happen. */
    fun settle(ms: Int = 150) {
        repeat(ms / STEP_MS) {
            Snapshot.sendApplyNotifications()
            while (g_main_context_iteration(null, 0) != 0) Unit
            usleep((STEP_MS * 1000).toUInt())
        }
    }

    fun close() {
        window.close()
        settle(100)
    }

    private fun InspectNode.all(): List<InspectNode> = listOf(this) + children.flatMap { it.all() }

    fun nodes(): List<InspectNode> = inspector.tree().all()

    fun node(tag: String): InspectNode =
        nodes().singleOrNull { it.testTag == tag } ?: error("no single node tagged $tag: ${nodes().mapNotNull { it.testTag }}")

    fun exists(tag: String): Boolean = nodes().any { it.testTag == tag }

    /** The tags of the focused nodes. */
    fun focused(): List<String> = nodes().filter { it.focused }.mapNotNull { it.testTag }

    private companion object {
        const val STEP_MS = 10
    }
}

/** Runs [test] against a window showing [content] and closes it afterwards. */
fun uiTest(content: @Composable () -> Unit, test: UiHarness.() -> Unit) {
    val harness = UiHarness(content)
    try {
        harness.test()
    } finally {
        harness.close()
    }
}
