// Hyprland workspace service: event-driven socket with an initial refresh.

package dev.knshell.services

import dev.knshell.core.WorkspaceInfo
import dev.knshell.core.WorkspaceListener
import dev.knshell.core.subscribeWorkspaces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Hyprland workspaces (event-driven socket, initial refresh on start). */
class WorkspacesService() : ShellService {

    private val mutable = MutableStateFlow<List<WorkspaceInfo>>(emptyList())

    /** All workspaces known to the compositor, in compositor order. */
    val state: StateFlow<List<WorkspaceInfo>> = mutable.asStateFlow()

    private val retained = mutableListOf<Any>()
    private var started = false

    override fun start() {
        if (started) return
        started = true
        val listener = object : WorkspaceListener {
            override fun onWorkspaces(workspaces: List<WorkspaceInfo>) {
                mutable.value = workspaces
                println("[services/workspaces] " + workspaces.joinToString { if (it.active) "[${it.name}]" else it.name })
            }
        }
        retained += listener
        subscribeWorkspaces(listener)
    }

    /** Focuses a workspace by name via the compositor IPC. */
    fun focus(name: String) = dev.knshell.core.focusWorkspace(name)
}
