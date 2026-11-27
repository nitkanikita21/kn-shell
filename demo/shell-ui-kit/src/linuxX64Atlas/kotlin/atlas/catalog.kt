package atlas

import dev.knshell.atlas.core.StoryGroup
import stories.element.uButtonStories
import stories.navigation.uTabsStories
import stories.overlays.uModalStories

/** Every group of the kit's catalog, in sidebar order: by category, as the packages are. */
val kitStories: List<StoryGroup> = listOf(
    // Element
    uButtonStories,
    // Navigation
    uTabsStories,
    // Overlays
    uModalStories,
)
