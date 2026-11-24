package dev.knshell.atlas.core.arg

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

class LiveArgs(args: List<Arg<*>>) : ArgValues {
    private val states = args.associateWith { mutableStateOf<Any?>(it.default) }
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(arg: Arg<T>): T = states.getValue(arg).value as T
    @Suppress("UNCHECKED_CAST")
    fun <T> set(arg: Arg<T>, v: T) { (states.getValue(arg)).value = v }
}