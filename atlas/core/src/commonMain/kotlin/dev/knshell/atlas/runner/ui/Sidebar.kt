package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.entry.MatrixView
import dev.knshell.atlas.core.entry.Story
import dev.knshell.atlas.core.entry.StoryEntry
import dev.knshell.atlas.core.entry.Variants
import dev.knshell.atlas.runner.AtlasEnvironment
import dev.knshell.atlas.runner.Selection
import dev.knshell.compose.border
import dev.knshell.atlas.runner.sidebarSections
import dev.knshell.atlas.runner.highlighted
import dev.knshell.atlas.runner.SidebarState
import dev.knshell.atlas.runner.SidebarGroup
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

/** The head of the sidebar, the search and the tree of story groups under it. */
@Composable
fun Sidebar(
    groups: List<StoryGroup>,
    environments: List<AtlasEnvironment>,
    environment: AtlasEnvironment?,
    onEnvironment: (AtlasEnvironment) -> Unit,
    state: SidebarState,
    selection: Selection?,
    onSelect: (StoryGroup, StoryEntry) -> Unit,
    /** Where Escape in the empty search field sends the focus. */
    onLeaveSearch: () -> Unit,
    viewOf: (Variants) -> MatrixView,
    golden: GoldenController,
    modifier: Modifier = Modifier,
) {
    val statuses = golden.report?.let(::storyStatuses).orEmpty()
    // Without anything differing the filter would empty the tree, with no switch left to turn it off.
    val onlyChanged = golden.onlyChanged && statuses.isNotEmpty()
    val words = state.words
    val sections = sidebarSections(groups, words) { group, entry ->
        !onlyChanged || "${group.title}/${entry.name}" in statuses
    }
    val found = sections.sumOf { section -> section.groups.sumOf { it.entries.size } }
    val cursor = state.cursorKey
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
        Column(modifier = Modifier.hExpand().padding(horizontal = 8, vertical = 6)) {
            SearchField(
                state,
                onOpen = { state.atCursor()?.let { (group, entry) -> onSelect(group, entry) } },
                onLeave = onLeaveSearch,
            )
        }
        Row(modifier = Modifier.hExpand().padding(horizontal = 4)) {
            if (state.searching) {
                Label(
                    "знайдено: $found",
                    modifier = Modifier.padding(horizontal = 4, vertical = 3)
                        .textColor(AtlasTheme.dim, transitionMs = 0).testTag("tree:count"),
                )
            }
            Box(Modifier.weight())
            if (!state.searching) {
                ToolButton(
                    if (state.allClosed) "розгорнути все" else "згорнути все",
                    "tree:fold",
                    color = AtlasTheme.dim,
                ) { state.toggleAll() }
            }
        }
        Scroll(modifier = Modifier.weight()) {
            Column(modifier = Modifier.hExpand()) {
                if (state.searching && sections.isEmpty()) {
                    Column(spacing = 8, modifier = Modifier.hExpand().padding(all = 10)) {
                        Label(
                            "Нічого не знайдено за «${state.query}».",
                            align = 0f,
                            wrap = true,
                            maxWidthChars = EMPTY_CHARS,
                            modifier = Modifier.textColor(AtlasTheme.dim, transitionMs = 0).testTag("search:empty"),
                        )
                        ActionButton("Очистити пошук", "search:reset") { state.search("") }
                    }
                }
                sections.forEach { section ->
                    section.category?.let { category ->
                        key("category:$category") {
                            Label(
                                highlighted(category, words, ACCENT_MARKUP),
                                markup = true,
                                align = 0f,
                                modifier = Modifier.padding(horizontal = 8, vertical = 4)
                                    .textColor(AtlasTheme.dim, transitionMs = 0).testTag("category:$category"),
                            )
                        }
                    }
                    section.groups.forEach { listed ->
                        key(listed.group.title) {
                            StoryGroupRow(listed, state, statuses, selection, cursor, onSelect, viewOf)
                        }
                    }
                }
            }
        }
    }
}

/** Longest line of the "nothing found" text before it wraps. */
private const val EMPTY_CHARS = 26

/** [AtlasTheme.accent] as Pango markup writes it. */
private const val ACCENT_MARKUP = "#7FD6A2"

@Composable
private fun StoryGroupRow(
    listed: SidebarGroup,
    state: SidebarState,
    statuses: Map<String, CaseStatus>,
    selection: Selection?,
    cursor: String?,
    onSelect: (StoryGroup, StoryEntry) -> Unit,
    viewOf: (Variants) -> MatrixView,
) {
    val group = listed.group
    val closed = state.isClosed(group)

    Column(modifier = Modifier.hExpand()) {
        Row(
            spacing = 8,
            modifier = Modifier
                .hExpand()
                .pointerCursor()
                .padding(horizontal = 8, vertical = 4)
                .onClick { state.toggle(group) }
                .testTag("tree:${group.title}"),
        ) {
            Icon(
                if (closed) Lucide.ChevronRight else Lucide.ChevronDown,
                size = 16,
                color = AtlasTheme.dim,
            )
            Icon(Lucide.Book, size = 16, color = AtlasTheme.accent)
            Label(
                highlighted(group.title, state.words, ACCENT_MARKUP),
                markup = true,
                modifier = Modifier.extraBold().textColor(AtlasTheme.fg, transitionMs = 0),
            )
        }

        if (!closed) {
            listed.entries.forEach { entry ->
                key(entry.name) {
                    val name = "${group.title}/${entry.name}"
                    val row = EntryState(
                        text = highlighted(entry.name, state.words, ACCENT_MARKUP),
                        status = statuses[name],
                        active = selection != null && selection.group === group && selection.entry === entry,
                        cursor = cursor == name,
                    )
                    when (entry) {
                        is Story -> StoryRow(group, entry, row, onSelect)
                        is Variants -> VariantsRow(group, entry, viewOf(entry), row, onSelect)
                    }
                }
            }
        }
    }
}

/** How one leaf of the tree looks now. */
private class EntryState(
    /** The name as markup, with what the search matched. */
    val text: String,
    val status: CaseStatus?,
    val active: Boolean,
    /** The arrows of the search are on it. */
    val cursor: Boolean,
)

@Composable
private fun VariantsRow(
    group: StoryGroup,
    variants: Variants,
    view: MatrixView,
    row: EntryState,
    onSelect: (StoryGroup, StoryEntry) -> Unit,
) {
    EntryRow(tag = "story:${group.title}/${variants.name}", row = row, onClick = { onSelect(group, variants) }) {
        MatrixIcon(view)
    }
}

@Composable
private fun StoryRow(
    group: StoryGroup,
    story: Story,
    row: EntryState,
    onSelect: (StoryGroup, StoryEntry) -> Unit,
) {
    EntryRow(tag = "story:${group.title}/${story.name}", row = row, onClick = { onSelect(group, story) }) {
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
private fun EntryRow(tag: String, row: EntryState, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(spacing = 8, modifier = entryModifier(row, onClick).testTag(tag)) {
        Space(16)
        icon()
        Label(
            row.text,
            markup = true,
            modifier = Modifier.textColor(
                if (row.active) AtlasTheme.accent else AtlasTheme.fg,
                transitionMs = 0,
            ),
        )
        if (row.status != null) {
            val (mark, color) = row.status.badge()
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
private fun entryModifier(row: EntryState, onClick: () -> Unit): Modifier = with(AtlasTheme) {
    val base = Modifier
        .hExpand()
        .pointerCursor()
        .padding(horizontal = 8, vertical = 4)
        // The same border on every row, seen only under the cursor, so that rows do not move.
        .border(if (row.cursor) accent else accent.copy(alpha = 0f))
        .onClick(onClick)
    if (row.active) {
        base.background(accent2, transitionMs = 0)
    } else {
        base.hoverBackground(panel2)
    }
}
