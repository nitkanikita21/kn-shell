package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import dev.knshell.atlas.core.arg.ArgValues
import dev.knshell.atlas.core.entry.Story
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.expand
import dev.knshell.compose.hExpand
import dev.knshell.compose.weight

/**
 * Preview of one [Story] on the stage: the story is centred horizontally and vertically at its
 * natural size. Controls are not here (they live in ArgsPanel).
 */
@Composable
fun StoryView(story: Story, args: ArgValues, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.expand().background(AtlasTheme.stage, transitionMs = 0),
    ) {
        // Equal spacers above and below share the surplus height, so the row sits in the middle.
        Box(Modifier.weight())
        Row(modifier = Modifier.hExpand()) {
            // Equal spacers left and right share the surplus width.
            Box(Modifier.weight())
            Box(Modifier.align(Align.Center)) {
                story.render(args)
            }
            Box(Modifier.weight())
        }
        Box(Modifier.weight())
    }
}
