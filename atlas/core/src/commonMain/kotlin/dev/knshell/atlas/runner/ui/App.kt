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
import dev.knshell.atlas.runner.golden.CaseStatus
import dev.knshell.atlas.runner.LocalGoldenController
import dev.knshell.compose.Column
import dev.knshell.compose.hExpand
import dev.knshell.compose.padding
import dev.knshell.compose.Box
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.background
import dev.knshell.compose.centerBoth
import dev.knshell.compose.fillAll
import dev.knshell.compose.testTag
import dev.knshell.compose.textColor
import dev.knshell.compose.vExpand
import dev.knshell.compose.weight
import dev.knshell.compose.width

@Composable
fun App() {
    val service = LocalAtlasHostService.current
    val selection by service.selection.collectAsState()
    val current: Selection? = selection
    val golden = LocalGoldenController.current

    Row(modifier = Modifier.fillAll().background(AtlasTheme.bg, transitionMs = 0)) {
        Sidebar(
            groups = service.groups,
            selection = current,
            onSelect = { group, entry -> service.select(group, entry) },
            viewOf = service::viewOf,
            golden = golden,
            modifier = Modifier.width(240).vExpand(),
        )

        if (current == null) {
            Box(modifier = Modifier.weight().fillAll()) {
                Label(
                    "No stories",
                    modifier = with(AtlasTheme) { Modifier.centerBoth().themedText().textColor(dim, transitionMs = 0) },
                )
            }
        } else {
            val story = "${current.group.title}/${current.entry.name}"
            val differing = golden.report?.cases.orEmpty()
                .filter { it.story == story && it.status != CaseStatus.Same }
            val showDiff = golden.showDiff && differing.isNotEmpty() && !golden.running
            Column(modifier = Modifier.weight().fillAll()) {
                // Only a story with something to show has the two tabs.
                if (differing.isNotEmpty() && !golden.running) {
                    Row(spacing = 8, modifier = with(AtlasTheme) { Modifier.hExpand().padding(all = 6) }) {
                        ActionButton("Preview", "view:preview", active = !showDiff) { golden.showDiff = false }
                        ActionButton("Різниця (${differing.size})", "view:diff", active = showDiff) {
                            golden.showDiff = true
                        }
                    }
                }
                if (showDiff) {
                    DiffView(golden, story, differing, Modifier.weight().fillAll())
                } else {
                    key(current.entry) {
                        val live = service.argsFor(current)
                        val panelArgs: List<Arg<*>> = when (val entry = current.entry) {
                            is Variants -> current.group.args.filterNot { it in entry.axisArgs }
                            else -> current.group.args
                        }

                        Row(modifier = Modifier.weight().fillAll()) {
                            Box(modifier = Modifier.weight().fillAll().testTag("preview")) {
                                when (val entry = current.entry) {
                                    is Story -> StoryView(entry, live, Modifier.fillAll())
                                    is Variants -> VariantsView(entry, live, Modifier.fillAll())
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
        }
    }
}
