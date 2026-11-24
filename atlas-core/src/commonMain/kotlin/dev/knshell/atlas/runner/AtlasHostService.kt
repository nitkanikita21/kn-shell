package dev.knshell.atlas.runner

import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.entry.StoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AtlasHostService(
    val groups: List<StoryGroup>
) {
    private var _activeStory: MutableStateFlow<StoryEntry?> = MutableStateFlow(null)
    val activeStory: StateFlow<StoryEntry?> get() = _activeStory.asStateFlow()

    private var _activeGroup: MutableStateFlow<StoryGroup?> = MutableStateFlow(null)
    val activeGroup: StateFlow<StoryGroup?> get() = _activeGroup.asStateFlow()

    fun select(group: StoryGroup, entry: StoryEntry) {
        _activeStory.value = entry
        _activeGroup.value = group
    }
}