package dev.knshell.services

import dev.knshell.core.SpectrumListener
import dev.knshell.core.subscribeSpectrum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** CAVA audio spectrum: 16 bins, each 0..100, updated at ~30 fps. */
class SpectrumService internal constructor() : ShellService {

    private val mutable = MutableStateFlow(List(16) { 0 })

    val state: StateFlow<List<Int>> = mutable.asStateFlow()

    private val retained = mutableListOf<Any>()
    private var started = false

    override fun start() {
        if (started) return
        started = true
        val listener = object : SpectrumListener {
            override fun onSpectrum(bars: List<Int>) {
                mutable.value = bars
            }
        }
        retained += listener
        subscribeSpectrum(listener)
    }
}
