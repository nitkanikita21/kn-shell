package dev.knshell.services

import dev.knshell.core.WindowInfo
import dev.knshell.core.WindowListener
import dev.knshell.core.subscribeWindows
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Toplevel windows reported by the compositor (`ext-foreign-toplevel-list-v1`). */
class WindowsService() : ShellService {

    private val mutable = MutableStateFlow<List<WindowInfo>>(emptyList())

    /** All toplevel windows known to the compositor. */
    val state: StateFlow<List<WindowInfo>> = mutable.asStateFlow()

    private val retained = mutableListOf<Any>()
    private var started = false

    override fun start() {
        if (started) return
        started = true
        val listener = object : WindowListener {
            override fun onWindows(windows: List<WindowInfo>) {
                mutable.value = windows
                val summary = windows.joinToString(separator = " | ") { "${it.appId} :: ${it.title}" }
                println("[services/windows] ${windows.size} toplevel(s): $summary")
            }
        }
        retained += listener
        subscribeWindows(listener)
    }
}
