package dev.knshell.atlas.runner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.arg.LiveArgs
import dev.knshell.atlas.core.entry.Story
import dev.knshell.atlas.core.entry.StoryEntry
import dev.knshell.atlas.core.entry.Variants
import dev.knshell.atlas.runner.ui.App
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Button
import dev.knshell.compose.Color
import dev.knshell.compose.Column
import dev.knshell.compose.Flow
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.Scroll
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.fontFamily
import dev.knshell.compose.registerFont
import dev.knshell.compose.runComposeWindow
import dev.knshell.compose.vExpand
import dev.knshell.compose.weight
import dev.knshell.compose.width
import dev.knshell.fontconfig.FcPatternCreate
import dev.knshell.fontconfig.FcPatternDestroy
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped

val LocalAtlasHostService = staticCompositionLocalOf<AtlasHostService> { error("AtlasHostService is missing") }

fun atlasMain(
    stories: List<StoryGroup>, title: String = "Untitled"
) {
    runComposeWindow(
        "KN Atlas - $title", width = -1, height = -1, windowBackground = Color.hex(0)
    ) {
        CompositionLocalProvider(LocalAtlasHostService provides AtlasHostService(stories)) {
            Box(Modifier.fontFamily("JetBrainsMono Nerd Font").background(Color.hex(0x000000))) {
                App()
            }
        }
    }
}

