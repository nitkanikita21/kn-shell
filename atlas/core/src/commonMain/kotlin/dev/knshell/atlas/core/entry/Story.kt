package dev.knshell.atlas.core.entry

import dev.knshell.atlas.play.Play
import dev.knshell.inspect.InteractionState

class Story(
    override val name: String,
    override val render: Render,
    override val states: List<InteractionState> = emptyList(),
    override val stateTarget: String = DEFAULT_STATE_TARGET,
    override val play: Play? = null,
) : StoryEntry

/** The testTag states are forced on unless a story names another. */
const val DEFAULT_STATE_TARGET = "target"


