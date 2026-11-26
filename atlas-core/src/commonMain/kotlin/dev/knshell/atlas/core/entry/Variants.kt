package dev.knshell.atlas.core.entry

import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.core.arg.ArgSnapshot

class Variants(
    override val name: String,
    val combos: List<ArgSnapshot>,
    val axisArgs: List<Arg<*>>,
    override val render: Render,
) : StoryEntry
