package atlas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.arg.LiveArgs
import dev.knshell.atlas.core.entry.StoryEntry
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Color
import dev.knshell.compose.Column
import dev.knshell.compose.Modifier
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.hExpand
import dev.knshell.compose.minHeight
import dev.knshell.compose.vExpand

@Composable
fun StoryView(group: StoryGroup, story: StoryEntry) {
    val live = remember(group, story) { LiveArgs(group.args) }

    Column(
        modifier = Modifier.hExpand()
    ) {
        Box(
            modifier = Modifier.hExpand().vExpand().align(Align.Center)
        ) {
            story.render(live)
        }
        Column(
            modifier = Modifier.hExpand().background(Color.hex(0x111111)).minHeight(100)
        ) {

        }
    }
}