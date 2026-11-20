// Hyprland focus service: the active window and the current keybind submap.

package dev.knshell.services

import dev.knshell.core.ActiveWindow
import dev.knshell.core.HyprlandListener
import dev.knshell.core.subscribeHyprland
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Hyprland focus events: active window and current submap. */
class HyprlandService() : ShellService {

    private val window = MutableStateFlow<ActiveWindow?>(null)
    private val submap = MutableStateFlow("")

    /** Currently focused window, or null when nothing is focused. */
    val activeWindow: StateFlow<ActiveWindow?> = window.asStateFlow()

    /** Current keybind submap (empty string for the default). */
    val currentSubmap: StateFlow<String> = submap.asStateFlow()

    private val retained = mutableListOf<Any>()
    private var started = false

    override fun start() {
        if (started) return
        started = true
        val listener = object : HyprlandListener {
            override fun onActiveWindow(window: ActiveWindow) {
                this@HyprlandService.window.value = window
            }

            override fun onSubmap(name: String) {
                submap.value = name
            }
        }
        retained += listener
        subscribeHyprland(listener)
    }
}
