package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.entry.Story
import dev.knshell.atlas.core.entry.StoryEntry
import dev.knshell.atlas.core.entry.Variants
import dev.knshell.atlas.runner.Selection
import dev.knshell.compose.Column
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
import dev.knshell.compose.rawStyle
import dev.knshell.compose.textColor
import dev.knshell.compose.weight
import dev.knshell.icons.lucide.Lucide
import dev.knshell.ui.primitives.Space

/** Vertically scrollable tree of story groups; the [selection] entry is highlighted. */
@Composable
fun Sidebar(
    groups: List<StoryGroup>,
    selection: Selection?,
    onSelect: (StoryGroup, StoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val frame = with(AtlasTheme) {
        modifier
            .background(panel, transitionMs = 0)
            .rawStyle("", "border-right: 1px solid ${line.toCss()};")
            .themedText()
    }

    Column(modifier = frame) {
        Scroll(modifier = Modifier.weight()) {
            Column(modifier = Modifier.hExpand()) {
                groups.forEach { group ->
                    StoryGroupRow(group, selection, onSelect)
                }
            }
        }
    }
}

@Composable
private fun StoryGroupRow(
    group: StoryGroup,
    selection: Selection?,
    onSelect: (StoryGroup, StoryEntry) -> Unit,
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
            group.entries.forEach { entry ->
                val active = selection != null &&
                    selection.group === group &&
                    selection.entry === entry
                when (entry) {
                    is Story -> StoryRow(group, entry, active, onSelect)
                    is Variants -> VariantsRow(group, entry, active, onSelect)
                }
            }
        }
    }
}

@Composable
private fun VariantsRow(
    group: StoryGroup,
    variants: Variants,
    active: Boolean,
    onSelect: (StoryGroup, StoryEntry) -> Unit,
) {
    EntryRow(
        name = variants.name,
        active = active,
        onClick = { onSelect(group, variants) },
    ) {
        Icon(Lucide.Grid2X2, size = 16, color = AtlasTheme.dim)
    }
}

@Composable
private fun StoryRow(
    group: StoryGroup,
    story: Story,
    active: Boolean,
    onSelect: (StoryGroup, StoryEntry) -> Unit,
) {
    EntryRow(
        name = story.name,
        active = active,
        onClick = { onSelect(group, story) },
    ) {
        Icon(Lucide.Bookmark, size = 16, color = AtlasTheme.dim)
    }
}

/** One selectable leaf of the tree: indented, with an icon and a label. */
@Composable
private fun EntryRow(
    name: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(spacing = 8, modifier = entryModifier(active, onClick)) {
        Space(16)
        icon()
        Label(
            name,
            modifier = Modifier.textColor(
                if (active) AtlasTheme.accent else AtlasTheme.fg,
                transitionMs = 0,
            ),
        )
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
