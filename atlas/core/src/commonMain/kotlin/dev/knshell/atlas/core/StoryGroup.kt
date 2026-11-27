package dev.knshell.atlas.core

import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.core.entry.StoryEntry

class StoryGroup(
    val title: String,
    val args: List<Arg<*>>,
    val entries: List<StoryEntry>,
    /** The heading the sidebar lists the group under; `null` for none. Not part of story names. */
    val category: String? = null,
    /** What the component is for, shown beside its stories. */
    val docs: String? = null,
)