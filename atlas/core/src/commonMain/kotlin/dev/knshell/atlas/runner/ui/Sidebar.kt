package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.entry.MatrixView
import dev.knshell.atlas.core.entry.Story
import dev.knshell.atlas.core.entry.StoryEntry
import dev.knshell.atlas.core.entry.Variants
import dev.knshell.atlas.runner.AtlasEnvironment
import dev.knshell.atlas.runner.Selection
import dev.knshell.atlas.runner.golden.CaseStatus
import dev.knshell.atlas.runner.golden.GoldenController
import dev.knshell.atlas.runner.golden.storyStatuses
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Stack
import dev.knshell.compose.align
import dev.knshell.compose.size
import dev.knshell.compose.Icon
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.Scroll
import dev.knshell.compose.background
import dev.knshell.compose.extraBold
import dev.knshell.compose.hExpand
import dev.knshell.compose.hoverBackground
import dev.knshell.compose.onClick
import dev.knshell.compose.padding
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.BorderSide
import dev.knshell.compose.testTag
import dev.knshell.compose.textColor
import dev.knshell.compose.weight
import dev.knshell.icons.lucide.Lucide
import dev.knshell.ui.primitives.Space

/** Vertically scrollable tree of story groups; the [selection] entry is highlighted. */
@Composable
fun Sidebar(
    groups: List<StoryGroup>,
    environments: List<AtlasEnvironment>,
    environment: AtlasEnvironment?,
    onEnvironment: (AtlasEnvironment) -> Unit,
    selection: Selection?,
    onSelect: (StoryGroup, StoryEntry) -> Unit,
    viewOf: (Variants) -> MatrixView,
    golden: GoldenController,
    modifier: Modifier = Modifier,
) {
    val statuses = golden.report?.let(::storyStatuses).orEmpty()
    // Without anything differing the filter would empty the tree, with no switch left to turn it off.
    val onlyChanged = golden.onlyChanged && statuses.isNotEmpty()
    val frame = with(AtlasTheme) {
        modifier
            .testTag("sidebar")
            .background(panel, transitionMs = 0)
            .hairline(setOf(BorderSide.Right))
            .themedText()
    }

    Column(modifier = frame) {
        GoldenPanel(golden) {
            if (environments.size > 1) {
                Segmented(
                    options = environments,
                    selected = environment,
                    title = { it.name },
                    tag = { "environment:${it.name}" },
                    onSelect = onEnvironment,
                )
            }
        }
        Scroll(modifier = Modifier.weight()) {
            Column(modifier = Modifier.hExpand()) {
                // Categories in the order the catalog first names them.
                groups.groupBy { it.category }.forEach { (category, members) ->
                    val visible = members.map { group ->
                        group to sidebarOrder(group.entries)
                            .filter { !onlyChanged || "${group.title}/${it.name}" in statuses }
                    }.filter { it.second.isNotEmpty() }
                    if (category != null && visible.isNotEmpty()) {
                        key("category:$category") {
                            Label(
                                category,
                                align = 0f,
                                modifier = Modifier.padding(horizontal = 8, vertical = 4)
                                    .textColor(AtlasTheme.dim, transitionMs = 0).testTag("category:$category"),
                            )
                        }
                    }
                    visible.forEach { (group, entries) ->
                        key(group.title) {
                            StoryGroupRow(group, entries, statuses, selection, onSelect, viewOf)
                        }
                    }
                }
            }
        }
    }
}

/** How the tree lists the entries of a group: stories, then matrices, each as the catalog declares them. */
internal fun sidebarOrder(entries: List<StoryEntry>): List<StoryEntry> = entries.sortedBy { it is Variants }

@Composable
private fun StoryGroupRow(
    group: StoryGroup,
    entries: List<StoryEntry>,
    statuses: Map<String, CaseStatus>,
    selection: Selection?,
    onSelect: (StoryGroup, StoryEntry) -> Unit,
    viewOf: (Variants) -> MatrixView,
) {
    var isCollapsed by remember(group.title) { mutableStateOf(false) }

    Column(modifier = Modifier.hExpand()) {
        Row(
            spacing = 8,
            modifier = Modifier
                .hExpand()
                .pointerCursor()
                .padding(horizontal = 8, vertical = 4)
                .onClick { isCollapsed = !isCollapsed },
        ) {
            Icon(
                if (isCollapsed) Lucide.ChevronRight else Lucide.ChevronDown,
                size = 16,
                color = AtlasTheme.dim,
            )
            Icon(Lucide.Book, size = 16, color = AtlasTheme.accent)
            Label(group.title, modifier = Modifier.extraBold().textColor(AtlasTheme.fg, transitionMs = 0))
        }

        if (!isCollapsed) {
            entries.forEach { entry ->
                key(entry.name) {
                    val active = selection != null &&
                        selection.group === group &&
                        selection.entry === entry
                    val status = statuses["${group.title}/${entry.name}"]
                    when (entry) {
                        is Story -> StoryRow(group, entry, status, active, onSelect)
                        is Variants -> VariantsRow(group, entry, viewOf(entry), status, active, onSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun VariantsRow(
    group: StoryGroup,
    variants: Variants,
    view: MatrixView,
    status: CaseStatus?,
    active: Boolean,
    onSelect: (StoryGroup, StoryEntry) -> Unit,
) {
    EntryRow(
        name = variants.name,
        tag = "story:${group.title}/${variants.name}",
        status = status,
        active = active,
        onClick = { onSelect(group, variants) },
    ) {
        MatrixIcon(view)
    }
}

@Composable
private fun StoryRow(
    group: StoryGroup,
    story: Story,
    status: CaseStatus?,
    active: Boolean,
    onSelect: (StoryGroup, StoryEntry) -> Unit,
) {
    EntryRow(
        name = story.name,
        tag = "story:${group.title}/${story.name}",
        status = status,
        active = active,
        onClick = { onSelect(group, story) },
    ) {
        EntryIcon { Icon(Lucide.Bookmark, modifier = Modifier.align(Align.Center), size = 16, color = AtlasTheme.dim) }
    }
}

/** The same room for the icon of every entry, so that all names start on one line. */
@Composable
private fun EntryIcon(content: @Composable () -> Unit) {
    Stack(Modifier.size(width = 20, height = 18)) { content() }
}

/** The matrix icon with a badge in its corner that tells which view the matrix is shown in. */
@Composable
private fun MatrixIcon(view: MatrixView) {
    EntryIcon {
        Icon(Lucide.Grid2X2, modifier = Modifier.align(h = Align.Start, v = Align.Start), size = 15, color = AtlasTheme.dim)
        Box(
            Modifier
                .align(h = Align.End, v = Align.End)
                .background(AtlasTheme.panel, radius = AtlasTheme.RADIUS, transitionMs = 0),
        ) {
            Icon(view.icon(), size = 11, color = AtlasTheme.accent)
        }
    }
}

/** One selectable leaf of the tree: indented, with an icon and a label. */
@Composable
private fun EntryRow(
    name: String,
    tag: String,
    status: CaseStatus?,
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(spacing = 8, modifier = entryModifier(active, onClick).testTag(tag)) {
        Space(16)
        icon()
        Label(
            name,
            modifier = Modifier.textColor(
                if (active) AtlasTheme.accent else AtlasTheme.fg,
                transitionMs = 0,
            ),
        )
        if (status != null) {
            val (mark, color) = status.badge()
            Label(
                mark,
                align = 1f,
                modifier = Modifier.weight().textColor(color, transitionMs = 0)
                    .testTag("badge:${tag.removePrefix("story:")}"),
            )
        }
    }
}

// Built from scratch on every call, so a row that stops being active loses its
// accent2 fill: the active fill is only in the chain while `active` is true.
private fun entryModifier(active: Boolean, onClick: () -> Unit): Modifier = with(AtlasTheme) {
    val base = Modifier
        .hExpand()
        .pointerCursor()
        .padding(horizontal = 8, vertical = 4)
        .onClick(onClick)
    if (active) {
        base.background(accent2, transitionMs = 0)
    } else {
        base.hoverBackground(panel2)
    }
}
