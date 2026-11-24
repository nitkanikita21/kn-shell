package dev.knshell.atlas.dsl

import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.core.arg.ArgSnapshot

class Axis<T>(val arg: Arg<T>, val values: List<T>)

@AtlasDsl
class VariantsBuilder internal constructor() {
    internal val axes = mutableListOf<Axis<*>>()
    private val filters = mutableListOf<(ArgSnapshot) -> Boolean>()

    fun <T> axis(arg: Arg<T>, values: List<T>) { axes += Axis(arg, values) }
    fun <E> axis(arg: Arg.Choice<E>) = axis(arg, arg.options)
    fun axis(arg: Arg.Flag) = axis(arg, listOf(true, false))

    fun exclude(predicate: (ArgSnapshot) -> Boolean) { filters += predicate }

    internal fun combos(): List<ArgSnapshot> {
        val all = axes.fold(listOf(emptyMap<Arg<*>, Any?>())) { acc, ax ->
            acc.flatMap { m -> ax.values.map { v -> m + (ax.arg to v) } }
        }.map(::ArgSnapshot)
        return all.filterNot { s -> filters.any { it(s) } }
            .also { require(it.size <= 500) { "matrix has ${it.size} combos (limit 500)" } }
    }
}