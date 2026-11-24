package dev.knshell.atlas.core.entry

import dev.knshell.atlas.core.arg.ArgSnapshot

class Variants(override val name: String, val combos: List<ArgSnapshot>, override val render: Render) : StoryEntry