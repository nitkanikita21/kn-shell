package dev.knshell.atlas.core.entry

import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.core.arg.ArgSnapshot

class Variants(
    override val name: String,
    val combos: List<ArgSnapshot>,
    val axisArgs: List<Arg<*>>,
    // вигляд, з яким матриця відкривається вперше
    val defaultView: MatrixView,
    override val render: Render,
) : StoryEntry {
    val views: List<MatrixView> get() = MatrixView.entries.filter { it.supports(axisArgs.size) }
}
