package dev.knshell.services

import dev.knshell.core.NetworkListener
import dev.knshell.core.NetworkState
import dev.knshell.core.subscribeNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live network throughput (RX/TX bytes per second, all non-loopback interfaces). */
class NetworkService internal constructor() : ShellService {

    private val mutable = MutableStateFlow(NetworkState(0uL, 0uL))

    val state: StateFlow<NetworkState> = mutable.asStateFlow()

    private val retained = mutableListOf<Any>()
    private var started = false
    private var loggedTraffic = false

    override fun start() {
        if (started) return
        started = true
        val listener = object : NetworkListener {
            override fun onNetwork(state: NetworkState) {
                mutable.value = state
                if (!loggedTraffic && (state.rxBytesPerSec > 0uL || state.txBytesPerSec > 0uL)) {
                    loggedTraffic = true
                    println(
                        "[services/network] first traffic: " +
                            "rx=${state.rxBytesPerSec} B/s tx=${state.txBytesPerSec} B/s",
                    )
                }
            }
        }
        retained += listener
        subscribeNetwork(listener)
    }
}
