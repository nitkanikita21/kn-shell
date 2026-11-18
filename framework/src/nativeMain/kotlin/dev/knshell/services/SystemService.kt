package dev.knshell.services

import dev.knshell.core.SystemListener
import dev.knshell.core.SystemStats
import dev.knshell.core.subscribeSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** CPU / RAM statistics. */
class SystemService() : ShellService {

    private val mutable = MutableStateFlow(SystemStats(0, 0))

    val state: StateFlow<SystemStats> = mutable.asStateFlow()

    private val retained = mutableListOf<Any>()
    private var started = false

    override fun start() {
        if (started) return
        started = true
        val listener = object : SystemListener {
            override fun onStats(stats: SystemStats) {
                mutable.value = stats
            }
        }
        retained += listener
        subscribeSystem(listener)
    }
}
