package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import atlas.StoryView
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.entry.Story
import dev.knshell.atlas.core.entry.Variants
import dev.knshell.atlas.runner.LocalAtlasHostService
import dev.knshell.compose.Align
import dev.knshell.compose.Color
import dev.knshell.compose.Column
import dev.knshell.compose.Icon
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.Scroll
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.extraBold
import dev.knshell.compose.hExpand
import dev.knshell.compose.hoverBackground
import dev.knshell.compose.onClick
import dev.knshell.compose.padding
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.vExpand
import dev.knshell.compose.weight
import dev.knshell.compose.width
import dev.knshell.icons.lucide.Lucide
import dev.knshell.ui.primitives.Space

@Composable
fun App() {
    val service = LocalAtlasHostService.current
    val activeStory by service.activeStory.collectAsState()
    val activeGroup by service.activeGroup.collectAsState()

    Row {
        Scroll(Modifier.width(300).vExpand().background(Color.hex(0x111111))) {
            Column(modifier = Modifier.align(Align.Start).hExpand()) {
                service.groups.forEach { StoryGroupRow(it) }
            }
        }
        if(activeGroup != null && activeStory != null) {
            when(activeStory) {
                is Story -> StoryView(activeGroup!!, activeStory!!)
            }
        }
    }
}

@Composable
fun StoryGroupRow(group: StoryGroup) {
    var isCollapsed by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier,
        spacing = 8
    ) {
        Column(
            modifier = Modifier.align(Align.Start).onClick { isCollapsed = !isCollapsed }
        ) {
            Icon(
                if (isCollapsed) Lucide.ChevronRight else Lucide.ChevronDown,
                size = 16,
                color = Color.hex(0xffffff, 0.8f)
            )
        }

        Column(
            spacing = 0, modifier = Modifier.hExpand()
        ) {
            Row(spacing = 8, modifier = Modifier.hExpand().onClick { isCollapsed = !isCollapsed }) {
                Icon(Lucide.Book, color = Color.hex(0xa7e650), size = 16)
                Label(group.title, modifier = Modifier.extraBold())
            }
            if (!isCollapsed) {
                group.entries.forEach {
                    Row(spacing = 0, modifier = Modifier.hExpand()) {
                        Space(16)
                        when (it) {
                            is Story -> StoryRow(group, it)
                            is Variants -> VariantsRow(group, it)
                        }
                    }
                }
            }
        }

    }
}

@Composable
fun VariantsRow(group: StoryGroup, variants: Variants) {
    val service = LocalAtlasHostService.current

    Row(
        spacing = 8,
        modifier = Modifier
            .pointerCursor()
            .hoverBackground(Color.hex(0xffffff, 0.1f))
            .padding(0, 4)
            .onClick { service.select(group, variants) }
    ) {
        Icon(Lucide.Grid2X2, color = Color.hex(0x6e4be3), size = 16)
        Label(variants.name)
    }
}

@Composable
fun StoryRow(group: StoryGroup, story: Story) {
    val service = LocalAtlasHostService.current

    Row(
        spacing = 8,
        modifier = Modifier
            .pointerCursor()
            .hoverBackground(Color.hex(0xffffff, 0.1f))
            .padding(0, 4)
            .onClick { service.select(group, story) }
    ) {
        Icon(Lucide.Bookmark, color = Color.hex(0x4babe3), size = 16)
        Label(story.name)
    }
}