// Battery service: charge/status polled from `/sys/class/power_supply` by the Rust core.

package dev.knshell.services

import dev.knshell.core.BatteryListener
import dev.knshell.core.BatteryState
import dev.knshell.core.subscribeBattery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Battery charge/status from `/sys/class/power_supply` (Rust poll, 2 s). */
class BatteryService() : ShellService {

    private val mutable = MutableStateFlow(BatteryState(false, 0, false, "Unknown"))

    /** Latest battery state, pushed by the Rust poll. */
    val state: StateFlow<BatteryState> = mutable.asStateFlow()

    private val retained = mutableListOf<Any>()
    private var started = false

    override fun start() {
        if (started) return
        started = true
        val listener = object : BatteryListener {
            override fun onBattery(state: BatteryState) {
                mutable.value = state
            }
        }
        retained += listener
        subscribeBattery(listener)
    }
}
