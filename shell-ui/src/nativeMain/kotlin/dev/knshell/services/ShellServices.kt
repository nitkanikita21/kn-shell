package dev.knshell.services

import dev.knshell.core.MediaListener
import dev.knshell.core.MediaState
import dev.knshell.core.VolumeListener
import dev.knshell.core.WindowInfo
import dev.knshell.core.WindowListener
import dev.knshell.core.subscribeMedia
import dev.knshell.core.subscribeVolume
import dev.knshell.core.subscribeWindows
import dev.knshell.core.setVolume
import dev.knshell.core.togglePlayPause
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Kotlin-side facade over the Rust services.
 *
 * The UniFFI callback objects are stored in [retained] so that Kotlin/Native does not
 * garbage-collect them while Rust still holds references (UniFFI keeps the native side
 * alive, but the Kotlin object must stay reachable).
 */
object ShellServices {
    val windows = MutableStateFlow<List<WindowInfo>>(emptyList())
    val volume = MutableStateFlow(0)
    val media = MutableStateFlow(MediaState("", "", false, "", false))

    private val retained = mutableListOf<Any>()
    private var started = false

    /** Sets the default sink volume (0-150 %) through the Rust AudioService. */
    fun setVolume(volume: Int) = dev.knshell.core.setVolume(volume)

    /** Toggles play/pause on the active MPRIS player. */
    fun togglePlayPause() = dev.knshell.core.togglePlayPause()

    fun start() {
        if (started) return
        started = true

        val windowListener = object : WindowListener {
            override fun onWindows(windows: List<WindowInfo>) {
                this@ShellServices.windows.value = windows
                val summary = windows.joinToString(separator = " | ") { "${it.appId} :: ${it.title}" }
                println("[services/windows] ${windows.size} toplevel(s): $summary")
            }
        }
        retained += windowListener
        subscribeWindows(windowListener)

        val volumeListener = object : VolumeListener {
            override fun onVolume(volume: Int) {
                this@ShellServices.volume.value = volume
                println("[services/audio] default sink volume = $volume%")
            }
        }
        retained += volumeListener
        subscribeVolume(volumeListener)

        val mediaListener = object : MediaListener {
            override fun onMedia(state: MediaState) {
                this@ShellServices.media.value = state
                println(
                    "[services/media] player=${state.player} playing=${state.playing} " +
                        "title=\"${state.title}\" artist=\"${state.artist}\" available=${state.available}"
                )
            }
        }
        retained += mediaListener
        subscribeMedia(mediaListener)
    }
}
