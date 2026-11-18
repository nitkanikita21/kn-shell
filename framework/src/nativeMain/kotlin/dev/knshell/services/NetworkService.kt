package dev.knshell.services

import dev.knshell.core.NetworkListener
import dev.knshell.core.NetworkState
import dev.knshell.core.subscribeNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live network throughput (RX/TX bytes per second, all non-loopback interfaces). */
class NetworkService() : ShellService {

    private val mutable = MutableStateFlow(
        NetworkState(
            rxBytesPerSec = 0uL,
            txBytesPerSec = 0uL,
            totalRxBytes = 0uL,
            totalTxBytes = 0uL,
            `interface` = "",
            connectionType = "disconnected",
            ssid = "",
            signalStrength = 0,
            ipAddress = "",
        ),
    )

    val state: StateFlow<NetworkState> = mutable.asStateFlow()

    private val retained = mutableListOf<Any>()
    private var started = false

    override fun start() {
        if (started) return
        started = true
        val listener = object : NetworkListener {
            override fun onNetwork(state: NetworkState) {
                mutable.value = state
            }
        }
        retained += listener
        subscribeNetwork(listener)
    }
}
