package dev.knshell.atlas.core.arg

sealed class Arg<T>(val name: String, val default: T) {
    class Text(name: String, default: String) : Arg<String>(name, default)
    class Flag(name: String, default: Boolean) : Arg<Boolean>(name, default)
    class Choice<E>(name: String, val options: List<E>, default: E) : Arg<E>(name, default)
}