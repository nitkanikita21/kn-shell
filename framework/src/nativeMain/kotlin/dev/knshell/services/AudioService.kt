package dev.knshell.services

import dev.knshell.core.VolumeListener
import dev.knshell.core.subscribeVolume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Default sink volume in percent (0-150). */
class AudioService internal constructor() : ShellService {

    private val mutable = MutableStateFlow(0)

    val state: StateFlow<Int> = mutable.asStateFlow()

    private val retained = mutableListOf<Any>()
    private var started = false

    override fun start() {
        if (started) return
        started = true
        val listener = object : VolumeListener {
            override fun onVolume(volume: Int) {
                mutable.value = volume
                println("[services/audio] default sink volume = $volume%")
            }
        }
        retained += listener
        subscribeVolume(listener)
    }

    /** Sets the default sink volume (0-150 %). */
    fun setVolume(volume: Int) = dev.knshell.core.setVolume(volume)
}
