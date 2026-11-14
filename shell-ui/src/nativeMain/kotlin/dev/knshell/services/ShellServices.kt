package dev.knshell.services

import dev.knshell.core.MediaState
import dev.knshell.core.SystemStats
import dev.knshell.core.WorkspaceInfo
import dev.knshell.core.WindowInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * Backward-compatible facade over [Services]; prefer the per-service API.
 *
 * ```kotlin
 * // old                          // new
 * ShellServices.media             Services.media.state
 * ShellServices.togglePlayPause() Services.media.togglePlayPause()
 * ShellServices.workspaces        Services.workspaces.state
 * ```
 */
@Deprecated(
    "Use dev.knshell.services.Services (Services.media.state, Services.workspaces.state, …)",
    ReplaceWith("dev.knshell.services.Services"),
)
object ShellServices {
    val windows: StateFlow<List<WindowInfo>> get() = Services.windows.state
    val volume: StateFlow<Int> get() = Services.volume.state
    val media: StateFlow<MediaState> get() = Services.media.state
    val workspaces: StateFlow<List<WorkspaceInfo>> get() = Services.workspaces.state
    val system: StateFlow<SystemStats> get() = Services.system.state
    val spectrum: StateFlow<List<Int>> get() = Services.spectrum.state

    fun setVolume(volume: Int) = Services.volume.setVolume(volume)
    fun togglePlayPause() = Services.media.togglePlayPause()
    fun focusWorkspace(name: String) = Services.workspaces.focus(name)

    /** Starts every service; lazy access already starts what you touch. */
    fun start() = Services.startAll()
}
