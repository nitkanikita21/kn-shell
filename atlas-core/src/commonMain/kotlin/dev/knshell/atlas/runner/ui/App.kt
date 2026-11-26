package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import dev.knshell.atlas.core.arg.Arg
import dev.knshell.atlas.core.entry.Story
import dev.knshell.atlas.core.entry.Variants
import dev.knshell.atlas.runner.LocalAtlasHostService
import dev.knshell.atlas.runner.Selection
import dev.knshell.compose.Box
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.background
import dev.knshell.compose.centerBoth
import dev.knshell.compose.fillAll
import dev.knshell.compose.textColor
import dev.knshell.compose.vExpand
import dev.knshell.compose.weight
import dev.knshell.compose.width

@Composable
fun App() {
    val service = LocalAtlasHostService.current
    val selection by service.selection.collectAsState()
    val current: Selection? = selection

    Row(modifier = Modifier.fillAll().background(AtlasTheme.bg, transitionMs = 0)) {
        Sidebar(
            groups = service.groups,
            selection = current,
            onSelect = { group, entry -> service.select(group, entry) },
            modifier = Modifier.width(240).vExpand(),
        )

        if (current == null) {
            Box(modifier = Modifier.weight().fillAll()) {
                Label(
                    "No stories",
                    modifier = Modifier.centerBoth().textColor(AtlasTheme.dim, transitionMs = 0),
                )
            }
        } else {
            key(current.entry) {
                val live = service.argsFor(current)
                val panelArgs: List<Arg<*>> = when (val entry = current.entry) {
                    is Variants -> current.group.args.filterNot { it in entry.axisArgs }
                    else -> current.group.args
                }

                Box(modifier = Modifier.weight().fillAll()) {
                    when (val entry = current.entry) {
                        is Story -> StoryView(entry, live, Modifier.fillAll())
                        is Variants -> MatrixView(entry, live, Modifier.fillAll())
                    }
                }
                ArgsPanel(
                    args = panelArgs,
                    live = live,
                    modifier = Modifier.width(280).vExpand(),
                )
            }
        }
    }
}
