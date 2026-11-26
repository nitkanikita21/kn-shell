package dev.knshell.atlas.runner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.runner.ui.App
import dev.knshell.atlas.runner.ui.AtlasTheme
import dev.knshell.compose.Box
import dev.knshell.compose.Modifier
import dev.knshell.compose.background
import dev.knshell.compose.expand
import dev.knshell.compose.fontFamily
import dev.knshell.compose.runComposeWindow
import dev.knshell.compose.textColor
import dev.knshell.inspect.LineSocketServer
import dev.knshell.inspect.disableUiTransitions
import dev.knshell.inspect.windowInspector
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

val LocalAtlasHostService = staticCompositionLocalOf<AtlasHostService> { error("AtlasHostService is missing") }

private const val STORY_ENV = "KN_ATLAS_STORY"
private const val INSPECT_ENV = "KN_ATLAS_INSPECT"

// сервер інспектора; лишається посиланням до виходу з процесу
private var inspectServer: LineSocketServer? = null

@OptIn(ExperimentalForeignApi::class)
fun atlasMain(
    stories: List<StoryGroup>,
    args: Array<String> = emptyArray(),
    title: String = "Untitled",
) {
    val service = AtlasHostService(stories)
    val env = getenv(STORY_ENV)?.toKString()
    val spec = parseStorySpec(args, env)

    val initial = spec?.let { given ->
        resolveStory(stories, given) ?: run {
            val available = stories.flatMap { group -> group.entries.map { "${group.title}/${it.name}" } }
            println("atlas: unknown story \"$given\". Available stories:")
            available.forEach { println("  $it") }
            defaultSelection(stories)
        }
    } ?: defaultSelection(stories)

    initial?.let { service.select(it.group, it.entry) }

    // сокет інспектора тільки за --inspect / KN_ATLAS_INSPECT; без них процес поводиться як раніше
    parseInspectPath(args, getenv(INSPECT_ENV)?.toKString())?.let { path ->
        // інакше скріншот одразу після зміни ловить середину CSS-переходу
        disableUiTransitions()
        val server = LineSocketServer(path, AtlasCommands(service, windowInspector())::handle)
        if (server.start()) {
            inspectServer = server
        } else {
            println("atlas: cannot start the inspector on $path")
        }
    }

    runComposeWindow(
        "KN Atlas - $title",
        width = 1200,
        height = 800,
        windowBackground = AtlasTheme.bg,
    ) {
        CompositionLocalProvider(LocalAtlasHostService provides service) {
            Root()
        }
    }
}

@Composable
private fun Root() {
    Box(
        Modifier
            .expand()
            .fontFamily("JetBrainsMono Nerd Font")
            .background(AtlasTheme.bg)
            .textColor(AtlasTheme.fg)
    ) {
        App()
    }
}
