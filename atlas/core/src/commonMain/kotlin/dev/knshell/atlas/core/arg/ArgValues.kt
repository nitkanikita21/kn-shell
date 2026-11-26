package dev.knshell.atlas.core.arg

interface ArgValues {
    operator fun <T> get(arg: Arg<T>): T
}