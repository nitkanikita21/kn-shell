package dev.knshell.atlas.runner

import dev.knshell.atlas.runner.golden.writeText
import dev.knshell.atlas.runner.golden.readText
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.runner.golden.GoldenController
import dev.knshell.atlas.runner.golden.runGolden
import dev.knshell.atlas.runner.ui.App
import dev.knshell.atlas.runner.ui.AtlasTheme
import dev.knshell.compose.Box
import dev.knshell.compose.Modifier
import dev.knshell.compose.background
import dev.knshell.compose.expand
import dev.knshell.compose.runComposeWindow
import dev.knshell.inspect.LineSocketServer
import dev.knshell.inspect.disableUiTransitions
import dev.knshell.inspect.windowInspector
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

val LocalAtlasHostService = staticCompositionLocalOf<AtlasHostService> { error("AtlasHostService is missing") }

val LocalGoldenController = staticCompositionLocalOf<GoldenController> { error("GoldenController is missing") }

private const val STORY_ENV = "KN_ATLAS_STORY"
private const val INSPECT_ENV = "KN_ATLAS_INSPECT"

// сервер інспектора; лишається посиланням до виходу з процесу
private var inspectServer: LineSocketServer? = null

@OptIn(ExperimentalForeignApi::class)
fun atlasMain(
    stories: List<StoryGroup>,
    args: Array<String> = emptyArray(),
    title: String = "Untitled",
    environments: List<AtlasEnvironment> = emptyList(),
) {
    parseGoldenOptions(args)?.let { runGolden(stories, environments, it) }

    val service = AtlasHostService(stories, environments)
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

    val inspectPath = parseInspectPath(args, getenv(INSPECT_ENV)?.toKString())
    // An atlas a tool drives neither takes the user's place nor overwrites it.
    val memoryPath = if (inspectPath != null) null else GoldenController.executablePath()?.let { executable ->
        memoryFile(executable, getenv("XDG_STATE_HOME")?.toKString(), getenv("HOME")?.toKString())
    }
    val memory = memoryPath?.let(::readText)?.let(AtlasMemory::parse) ?: AtlasMemory()
    service.restore(memory)

    // A story named on the command line wins over the remembered one.
    val opened = if (spec == null) memory.story?.let { resolveStory(stories, it) } ?: initial else initial
    opened?.let { service.select(it.group, it.entry) }

    val golden = GoldenController.forThisProcess()

    // сокет інспектора тільки за --inspect / KN_ATLAS_INSPECT; без них процес поводиться як раніше
    inspectPath?.let { path ->
        // інакше скріншот одразу після зміни ловить середину CSS-переходу
        disableUiTransitions()
        val server = LineSocketServer(path, AtlasCommands(service, windowInspector(), golden = golden)::handle)
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
        windowModifier = shortcuts(service, golden),
    ) {
        AtlasWindow(service, golden)
        memoryPath?.let { Remember(service, it) }
    }
}

/** How long the window has to stay as it is before that is written down. */
private const val REMEMBER_AFTER_MS = 400L

/** Writes the memory of [service] to [path] whenever it changes. */
@Composable
internal fun Remember(service: AtlasHostService, path: String) {
    val selection by service.selection.collectAsState()
    val memory = service.memory(selection)
    LaunchedEffect(memory) {
        delay(REMEMBER_AFTER_MS)
        // Not worth stopping for: a state directory that cannot be written only loses the place.
        runCatching { writeText(path, memory.toJson()) }
    }
}

/** The content of the atlas window. */
@Composable
internal fun AtlasWindow(service: AtlasHostService, golden: GoldenController) {
    CompositionLocalProvider(
        LocalAtlasHostService provides service,
        LocalGoldenController provides golden,
    ) {
        Root()
    }
}

@Composable
private fun Root() {
    // No font or text colour here: the chrome styles its own parts, and a story starts from a bare
    // window (see StyleReset).
    Box(Modifier.expand().background(AtlasTheme.bg)) {
        App()
    }
}
