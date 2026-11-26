package dev.knshell.atlas.core.arg

class ArgSnapshot(val values: Map<Arg<*>, Any?>) : ArgValues {
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(arg: Arg<T>): T =
        if (arg in values) values[arg] as T else arg.default
}