// MPRIS service: active player state plus play/pause control.

package dev.knshell.services

import dev.knshell.core.MediaListener
import dev.knshell.core.MediaState
import dev.knshell.core.subscribeMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Active MPRIS player state and transport controls. */
class MediaService() : ShellService {

    private val mutable = MutableStateFlow(MediaState("", "", false, "", false))

    /** Current player state; updates on every MPRIS change (`playerctl --follow`). */
    val state: StateFlow<MediaState> = mutable.asStateFlow()

    private val retained = mutableListOf<Any>()
    private var started = false

    override fun start() {
        if (started) return
        started = true
        val listener = object : MediaListener {
            override fun onMedia(state: MediaState) {
                mutable.value = state
                println(
                    "[services/media] player=${state.player} playing=${state.playing} " +
                        "title=\"${state.title}\" artist=\"${state.artist}\" available=${state.available}"
                )
            }
        }
        retained += listener
        subscribeMedia(listener)
    }

    /** Toggles play/pause on the active player. */
    fun togglePlayPause() = dev.knshell.core.togglePlayPause()
}
