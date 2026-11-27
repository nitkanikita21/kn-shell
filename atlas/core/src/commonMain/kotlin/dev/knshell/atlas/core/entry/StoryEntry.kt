package dev.knshell.atlas.core.entry

import dev.knshell.atlas.play.Play
import dev.knshell.inspect.InteractionState

interface StoryEntry {
    val name: String
    val render: Render

    /** Interaction states the golden run also takes pictures in, forced on the widget [stateTarget]. */
    val states: List<InteractionState>
    val stateTarget: String

    /** The interaction script of the story, run by the golden run. */
    val play: Play?
}
