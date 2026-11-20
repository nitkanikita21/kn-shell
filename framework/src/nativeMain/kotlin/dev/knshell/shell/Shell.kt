// Entry point of the public shell API: the `Shell` object, `Shell.run` and the `Shell.Hyprland`
// compositor actions.

package dev.knshell.shell

import dev.knshell.core.hyprctlDispatch
import dev.knshell.core.spawnCommand
import dev.knshell.services.Services

/**
 * Entry point of the shell runtime — the Quickshell-like API surface.
 *
 * ```
 * fun main() {
 *     Shell.run {
 *         barWindow { MyBar() }
 *         window(WindowSpec("Panel", 420, 260)) { MyPanel() }
 *     }
 * }
 * ```
 *
 * [run] blocks in the GLib main loop until the last window is closed. All windows
 * declared by [ShellScope] share one GTK initialisation, one loop, one Recomposer and
 * one coroutine scope.
 */
object Shell {

    /** Reactive services published by the Rust backend (see [Services]). */
    val services = Services

    /** Compositor actions (Hyprland IPC). */
    object Hyprland {
        /** Focuses a workspace by name, with the legacy dispatcher fallback in Rust. */
        fun focusWorkspace(name: String) = Services.workspaces.focus(name)

        /** Runs a raw Lua dispatcher, e.g. `hl.dsp.focus({ workspace = "2" })`. */
        fun dispatch(lua: String) = hyprctlDispatch(lua)

        /** Launches a command detached from the shell (`sh -c <command>`). */
        fun spawn(command: String) = spawnCommand(command)
    }

    private val runtime: ShellRuntime get() = ShellRuntime.instance()

    /**
     * Declares the shell windows and blocks in the GLib main loop.
     *
     * The content lambda only declares windows; it does not render. Window bodies are
     * Compose content mounted into their own GTK window.
     */
    fun run(content: ShellScope.() -> Unit) {
        // Bring up the reactive backend once; subscriptions are idempotent. Without this
        // no Rust service is subscribed and every flow stays empty.
        Services.startAll()
        val current = runtime
        ShellScope(current).content()

        if (!current.hasWindows) {
            println("[shell] no windows declared — returning without entering the GLib loop")
            return
        }
        println(
            "[shell] ${current.windowCount} window(s) mounted on the shared runtime, " +
                "entering GLib main loop"
        )
        current.run()
    }
}
