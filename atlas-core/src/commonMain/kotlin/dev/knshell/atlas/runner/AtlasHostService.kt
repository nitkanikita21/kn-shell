package dev.knshell.atlas.runner

import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.arg.LiveArgs
import dev.knshell.atlas.core.entry.StoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AtlasHostService(
    val groups: List<StoryGroup>
) {
    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    // живі значення args памʼятаються окремо для кожної story до закриття атласу
    private val argsByEntry = mutableMapOf<StoryEntry, LiveArgs>()

    fun select(group: StoryGroup, entry: StoryEntry) {
        _selection.value = Selection(group, entry)
    }

    fun argsFor(selection: Selection): LiveArgs =
        argsByEntry.getOrPut(selection.entry) { LiveArgs(selection.group.args) }
}
