package dev.knshell.atlas.runner

import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.entry.StoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AtlasHostService(
    val stories: List<StoryGroup>
) {
    private var _activeStory: MutableStateFlow<StoryEntry?> = MutableStateFlow(null)
    val activeStory: StateFlow<StoryEntry?> get() = _activeStory.asStateFlow()


}