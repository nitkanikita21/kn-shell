package dev.knshell.atlas.core.arg

// значення комірки матриці: axis з комбінації, решта args з live-контролів
class CellArgs(private val combo: ArgSnapshot, private val live: ArgValues) : ArgValues {
    override fun <T> get(arg: Arg<T>): T = if (arg in combo.values) combo[arg] else live[arg]
}
