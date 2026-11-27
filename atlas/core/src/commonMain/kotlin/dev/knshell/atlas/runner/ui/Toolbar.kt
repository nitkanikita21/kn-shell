// ---------------------------------------------------------------------------
// The bars over the preview: panels and tabs, and under them the tools of the preview.
// ---------------------------------------------------------------------------

package dev.knshell.atlas.runner.ui

import dev.knshell.atlas.runner.golden.GoldenController
import androidx.compose.runtime.Composable
import dev.knshell.atlas.runner.MainTab
import dev.knshell.atlas.runner.PREVIEW_WIDTHS
import dev.knshell.atlas.runner.ViewState
import dev.knshell.compose.Align
import dev.knshell.compose.BorderSide
import dev.knshell.compose.Box
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.hExpand
import dev.knshell.compose.openStyleInspector
import dev.knshell.compose.padding
import dev.knshell.compose.testTag
import dev.knshell.compose.textColor
import dev.knshell.compose.weight
import dev.knshell.icons.lucide.Lucide

private val barModifier: Modifier
    get() = with(AtlasTheme) {
        Modifier.hExpand().hairline(setOf(BorderSide.Bottom)).padding(horizontal = 8, vertical = 4)
    }

/** The first bar: the panels at its ends, the [tabs] that have something to show between them. */
@Composable
internal fun MainBar(
    view: ViewState,
    tabs: List<Pair<MainTab, String>>,
    shown: MainTab,
    golden: GoldenController,
    /** The open story as `Group/Entry`, and its group: what the check buttons check. */
    story: String,
    group: String,
) {
    Row(spacing = 8, modifier = barModifier.background(AtlasTheme.panel, transitionMs = 0)) {
        ToolButton(
            "",
            "view:sidebar",
            icon = Lucide.PanelLeft,
            color = if (view.sidebarHidden) AtlasTheme.accent else AtlasTheme.dim,
        ) { view.sidebarHidden = !view.sidebarHidden }
        Segmented(
            options = tabs,
            selected = tabs.firstOrNull { it.first == shown },
            title = { it.second },
            tag = { "view:${it.first.name.lowercase()}" },
            onSelect = { view.tab = it.first },
        )
        Box(Modifier.weight())
        if (golden.running) {
            ToolButton(golden.progress ?: "запуск…", "check:progress", icon = Lucide.LoaderCircle, enabled = false) {}
        } else {
            Label("Перевірити:", modifier = dim.align(v = Align.Center))
            ToolButton("story", "check:story", icon = Lucide.Play, enabled = golden.available) { golden.check(story) }
            ToolButton("групу", "check:group", icon = Lucide.Play, enabled = golden.available) { golden.check(group) }
        }
        ToolButton(
            "",
            "view:args",
            icon = Lucide.PanelRight,
            color = if (view.argsHidden) AtlasTheme.accent else AtlasTheme.dim,
        ) { view.argsHidden = !view.argsHidden }
    }
}

/** The tools of the preview: its width, measuring and GTK's inspector. */
@Composable
internal fun PreviewBar(view: ViewState) {
    Row(spacing = 8, modifier = barModifier) {
        Label("Ширина:", modifier = dim.align(v = Align.Center))
        // `null`, the free width, is the last choice.
        Segmented(
            options = PREVIEW_WIDTHS + 0,
            selected = view.width ?: 0,
            title = { if (it == 0) "вільно" else it.toString() },
            tag = { "width:$it" },
            onSelect = { view.width = it.takeIf { width -> width != 0 } },
        )
        Box(Modifier.weight())
        ToolButton("Виміряти", "tool:measure", icon = Lucide.Ruler, active = view.measuring) {
            view.measure(!view.measuring)
        }
        ToolButton("GTK Inspector", "tool:gtk", icon = Lucide.Braces) { openStyleInspector() }
    }
}

private val dim: Modifier
    get() = with(AtlasTheme) { Modifier.themedText().textColor(dim, transitionMs = 0) }
