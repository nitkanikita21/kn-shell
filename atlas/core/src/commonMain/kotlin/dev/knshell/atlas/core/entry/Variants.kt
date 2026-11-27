package dev.knshell.atlas.core.entry

import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.core.arg.ArgSnapshot
import dev.knshell.atlas.play.Play
import dev.knshell.inspect.InteractionState

class Variants(
    override val name: String,
    val combos: List<ArgSnapshot>,
    val axisArgs: List<Arg<*>>,
    // вигляд, з яким матриця відкривається вперше
    val defaultView: MatrixView,
    override val render: Render,
    override val states: List<InteractionState> = emptyList(),
    override val stateTarget: String = DEFAULT_STATE_TARGET,
    override val play: Play? = null,
) : StoryEntry {
    val views: List<MatrixView> get() = MatrixView.entries.filter { it.supports(axisArgs.size) }
}
