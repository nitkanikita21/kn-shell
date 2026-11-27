// ---------------------------------------------------------------------------
// The window of a golden run: one case at a time, alone on a fixed neutral stage. No sidebar, no
// args panel and no AtlasTheme: what a picture shows is what the story and its environment render.
// After the first picture come the forced states and the story's `play` script.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.atlas.runner.golden

import dev.knshell.atlas.play.PlayStep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.play.PlayDriver
import dev.knshell.atlas.play.PlayFailure
import dev.knshell.atlas.play.PlayScope
import dev.knshell.atlas.runner.AtlasEnvironment
import dev.knshell.atlas.runner.ui.Decorated
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Color
import dev.knshell.compose.Column
import dev.knshell.compose.Key
import dev.knshell.compose.Modifier
import dev.knshell.compose.Scroll
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.expand
import dev.knshell.compose.hExpand
import dev.knshell.compose.launchOnUiThread
import dev.knshell.compose.runComposeWindow
import dev.knshell.compose.testTag
import dev.knshell.compose.weight
import dev.knshell.inspect.InspectException
import dev.knshell.inspect.InspectNode
import dev.knshell.inspect.Inspector
import dev.knshell.inspect.InteractionState
import dev.knshell.inspect.VirtualKeyboard
import dev.knshell.inspect.disableUiTransitions
import dev.knshell.inspect.windowInspector
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import platform.posix.exit
import platform.posix.getenv

/** Behind every story. Not a colour of AtlasTheme: changing the atlas must not change the goldens. */
private val Stage = Color.hex(0x101010)

/** Names the fonts of the run in its environment: a digest of the pinned files, or `system`. */
private const val FONTS_ENV = "KN_ATLAS_GOLDEN_FONTS"

private const val WINDOW_WIDTH = 1280
private const val WINDOW_HEIGHT = 960

/** How often, and for how long, a picture is retried until two in a row are the same. */
private const val ATTEMPT_PAUSE_MS = 20L
private const val ATTEMPTS = 100

/** The time a key press needs to travel from the compositor to the widgets. */
private const val EVENT_MS = 40L

/** Runs the golden check or record described by [options] and ends the process with its status. */
internal fun runGolden(
    stories: List<StoryGroup>,
    environments: List<AtlasEnvironment>,
    options: GoldenOptions,
): Nothing {
    val cases = try {
        goldenCases(stories, options.filter, environments)
    } catch (e: IllegalArgumentException) {
        println("golden: ${e.message}")
        exit(2)
        error("unreachable")
    }
    disableUiTransitions()

    var shown by mutableStateOf<GoldenCase?>(null)
    val inspector = windowInspector()
    val keyboard = VirtualKeyboard.fromEnvironment()

    // Outside the composition: a story that breaks it must still be reported.
    launchOnUiThread {
        val status = try {
            val report = GoldenSession(
                options = options,
                cases = cases,
                binary = fileStamp("/proc/self/exe").orEmpty(),
                capture = { case ->
                    // An empty stage first: focus left by the previous case must not mark this one.
                    shown = null
                    inspector.awaitIdle()
                    inspector.resetFocus()
                    shown = case
                    shoot(inspector, keyboard, case)
                },
                environment = {
                    // The fonts are chosen by whoever started the run; see the Gradle plugin.
                    val fonts = getenv(FONTS_ENV)?.toKString() ?: "unknown"
                    RenderEnvironment(inspector.renderEnvironment() + ("fonts" to fonts))
                },
            ).run()
            exitCode(report)
        } catch (e: Throwable) {
            println("golden: the run failed: ${e.message}")
            1
        }
        exit(status)
    }

    runComposeWindow("KN Atlas golden", width = WINDOW_WIDTH, height = WINDOW_HEIGHT, windowBackground = Stage) {
        GoldenStage(shown)
    }
    // Reached only when the window was closed before the run ended.
    exit(1)
    error("unreachable")
}

@Composable
internal fun GoldenStage(case: GoldenCase?) {
    Column(modifier = Modifier.expand().background(Stage, transitionMs = 0)) {
        // The scroll gives a story its natural size, whatever the window offers.
        Scroll(modifier = Modifier.weight().hExpand(), horizontal = true, vertical = true) {
            // A Scroll takes one child; while the case changes, the old and the new one coexist.
            Box(Modifier.align(h = Align.Start, v = Align.Start)) {
                if (case != null) {
                    key(case.id) {
                        // The same surroundings as in the preview, so both show the same story.
                        Decorated(
                            case.environment,
                            Modifier.align(h = Align.Start, v = Align.Start).testTag(tagOf(case)),
                        ) {
                            case.Content()
                        }
                    }
                }
            }
        }
    }
}

/** Per case, so that the widgets of the previous one can never be taken for this one. */
private fun tagOf(case: GoldenCase): String = "golden:${case.id}"

/** The first picture of [case], then one per forced state, then its script with its snapshots. */
internal suspend fun shoot(inspector: Inspector, keyboard: VirtualKeyboard?, case: GoldenCase): CaseShots {
    val stage = tagOf(case)
    val base = capture(inspector, stage)
    val extra = mutableListOf<Pair<String, ByteArray>>()
    var failure: String? = null
    var steps = emptyList<PlayStep>()
    val driver = StageDriver(inspector, keyboard, stage) { name, picture -> extra += name to picture }
    try {
        for (state in case.entry.states) {
            val target = driver.tree().find(case.entry.stateTarget)
                ?: throw PlayFailure("states: nothing is tagged ${case.entry.stateTarget}")
            inspector.setState(target.id, state, true)
            driver.settle()
            extra += state.name.lowercase() to capture(inspector, stage)
            inspector.setState(target.id, state, false)
            driver.settle()
        }
        case.entry.play?.let { play ->
            val scope = PlayScope(driver)
            try {
                with(play) { scope.run() }
            } finally {
                steps = scope.log
            }
        }
    } catch (e: PlayFailure) {
        failure = e.message
    } catch (e: InspectException) {
        failure = e.message
    }
    return CaseShots(base, extra, failure, steps)
}

private fun InspectNode.find(tag: String): InspectNode? =
    if (testTag == tag) this else children.firstNotNullOfOrNull { it.find(tag) }

/** Drives the story on the stage for `play`; targets are looked up inside the stage only. */
private class StageDriver(
    private val inspector: Inspector,
    private val keyboard: VirtualKeyboard?,
    private val stage: String,
    private val keep: (String, ByteArray) -> Unit,
) : PlayDriver {
    override fun tree(): InspectNode = checkNotNull(inspector.tree().find(stage)) { "the story left the stage" }

    override suspend fun settle() {
        delay(EVENT_MS)
        inspector.awaitIdle()
    }

    override fun click(id: Int) = inspector.click(id)

    override fun focus(id: Int) = inspector.focus(id)

    override fun setState(id: Int, state: InteractionState, on: Boolean) = inspector.setState(id, state, on)

    override fun setText(id: Int, text: String) = inspector.setText(id, text)

    override fun press(key: Key, shift: Boolean, ctrl: Boolean, alt: Boolean, meta: Boolean) =
        keys().press(key, shift, ctrl, alt, meta)

    override fun type(text: String) = keys().type(text)

    private fun keys(): VirtualKeyboard =
        keyboard ?: throw PlayFailure("no keyboard: the run is not under headless-compositor")

    override suspend fun snapshot(name: String, id: Int?) = keep(
        name,
        if (id == null) capture(inspector, stage) else steady(inspector) { inspector.screenshot(id) },
    )
}

/**
 * The PNG of the stage content tagged [tag], once it is painted and stays the same. Throws when it
 * never is: it renders nothing, it keeps changing, or it does not fit the window.
 */
private suspend fun capture(inspector: Inspector, tag: String): ByteArray = steady(inspector) {
    val node = inspector.find(tag)
    val stage = inspector.tree().bounds
    val bounds = node.bounds
    check(bounds.x + bounds.width <= stage.width && bounds.y + bounds.height <= stage.height) {
        "the story is ${bounds.width}x${bounds.height} px, more than the ${stage.width}x${stage.height} px stage"
    }
    inspector.screenshot(node.id)
}

/** The first picture [shot] gives twice in a row. */
private suspend fun steady(inspector: Inspector, shot: () -> ByteArray): ByteArray {
    var previous: ByteArray? = null
    var problem = "nothing was painted"
    repeat(ATTEMPTS) {
        inspector.awaitIdle()
        try {
            val picture = shot()
            if (previous?.contentEquals(picture) == true) return picture
            previous = picture
        } catch (e: InspectException) {
            // Not laid out or not painted yet.
            problem = e.message ?: problem
        }
        delay(ATTEMPT_PAUSE_MS)
    }
    error(if (previous == null) problem else "the picture does not settle (an endless animation?)")
}
