package dev.knshell.atlas.core

import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.core.entry.StoryEntry

class StoryGroup(val title: String, val args: List<Arg<*>>, val entries: List<StoryEntry>)