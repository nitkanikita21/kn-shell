package dev.knshell.atlas.runner.ui

import dev.knshell.atlas.runner.golden.GoldenController
import dev.knshell.compose.maxWidth
import dev.knshell.compose.expand
import dev.knshell.compose.border
import dev.knshell.compose.Align
import dev.knshell.compose.align
import dev.knshell.compose.Stack
import dev.knshell.atlas.runner.MainTab
import androidx.compose.runtime.Composable
import dev.knshell.compose.focusRequester
import dev.knshell.compose.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
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

    val previewFocus = remember { FocusRequester() }
    val view = service.view

    CompositionLocalProvider(LocalAtlasEnvironment provides service.environment) {
        Row(modifier = Modifier.fillAll().background(AtlasTheme.bg, transitionMs = 0)) {
            if (!view.sidebarHidden) {
                Sidebar(
                    groups = service.groups,
                    environments = service.environments,
                    environment = service.environment,
                    onEnvironment = { service.environment = it },
                    state = service.sidebar,
                    selection = current,
                    onSelect = { group, entry -> service.select(group, entry) },
                    onLeaveSearch = { previewFocus.requestFocus() },
                    viewOf = service::viewOf,
                    golden = golden,
                    modifier = Modifier.width(240).vExpand(),
                )
            }

            if (current == null) {
                Box(modifier = Modifier.weight().fillAll()) {
                    Label(
                        "No stories",
                        modifier = with(AtlasTheme) { Modifier.centerBoth().themedText().textColor(dim, transitionMs = 0) },
                    )
                }
            } else {
                Main(current, golden, previewFocus)
            }
        }
    }
}

/** Everything beside the tree: the bars, the preview or the differences, and the args. */
@Composable
private fun Main(current: Selection, golden: GoldenController, previewFocus: FocusRequester) {
    val service = LocalAtlasHostService.current
    val view = service.view
    val story = "${current.group.title}/${current.entry.name}"
    val differing = golden.report?.cases.orEmpty().filter { it.story == story && it.status != CaseStatus.Same }
    // Only a story with something to show has the tab of differences.
    val tabs = buildList {
        add(MainTab.Preview to "Preview")
        if (differing.isNotEmpty() && !golden.running) add(MainTab.Diff to "Різниця (${differing.size})")
    }
    val shown = view.tab.takeIf { wanted -> tabs.any { it.first == wanted } } ?: MainTab.Preview

    Column(modifier = Modifier.weight().fillAll()) {
        MainBar(view, tabs, shown)
        when (shown) {
            MainTab.Diff -> DiffView(golden, story, differing, Modifier.weight().fillAll())
            MainTab.Preview -> {
                PreviewBar(view)
                key(current.entry) {
                    val live = service.argsFor(current)
                    val panelArgs: List<Arg<*>> = when (val entry = current.entry) {
                        is Variants -> current.group.args.filterNot { it in entry.axisArgs }
                        else -> current.group.args
                    }

                    Row(modifier = Modifier.weight().fillAll()) {
                        Stack(
                            modifier = Modifier.weight().fillAll()
                                // Takes the focus only when asked: where Escape in the search leads.
                                .focusRequester(previewFocus)
                                .testTag("preview"),
                        ) {
                            HeldToWidth(view.width) {
                                when (val entry = current.entry) {
                                    is Story -> StoryView(entry, live, Modifier.fillAll())
                                    is Variants -> VariantsView(entry, live, Modifier.fillAll())
                                }
                            }
                            if (view.measuring) MeasureOverlay(view)
                        }
                        if (!view.argsHidden) {
                            ArgsPanel(
                                args = panelArgs,
                                live = live,
                                docs = current.group.docs,
                                modifier = Modifier.width(280).vExpand(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** [content] in a column of [width] pixels in the middle of the preview, or filling it. */
@Composable
private fun HeldToWidth(width: Int?, content: @Composable () -> Unit) {
    if (width == null) {
        Column(modifier = Modifier.expand().align(Align.Fill)) { content() }
        return
    }
    Column(modifier = Modifier.expand().align(Align.Fill).background(AtlasTheme.stage, transitionMs = 0)) {
        Column(
            modifier = Modifier.weight().vExpand().align(h = Align.Center).width(width).maxWidth(width),
        ) {
            Label(
                "$width px",
                align = 0f,
                modifier = with(AtlasTheme) { Modifier.padding(vertical = 4).themedText().textColor(dim, transitionMs = 0) },
            )
            Column(
                modifier = Modifier.weight().hExpand().border(AtlasTheme.line2).testTag("preview:width"),
            ) { content() }
        }
    }
}
