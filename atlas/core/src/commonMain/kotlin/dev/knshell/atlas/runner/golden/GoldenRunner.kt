// ---------------------------------------------------------------------------
// The window of a golden run: one story at a time, alone on a fixed neutral stage. No sidebar, no
// args panel and no AtlasTheme: what the picture shows is what the story renders.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.atlas.runner.golden

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Color
import dev.knshell.compose.Column
import dev.knshell.compose.Modifier
import dev.knshell.compose.Scroll
import dev.knshell.compose.StyleReset
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.expand
import dev.knshell.compose.hExpand
import dev.knshell.compose.launchOnUiThread
import dev.knshell.compose.runComposeWindow
import dev.knshell.compose.testTag
import dev.knshell.compose.weight
import dev.knshell.inspect.InspectException
import dev.knshell.inspect.Inspector
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

/** Runs the golden check or record described by [options] and ends the process with its status. */
internal fun runGolden(stories: List<StoryGroup>, options: GoldenOptions): Nothing {
    val cases = try {
        goldenCases(stories, options.filter)
    } catch (e: IllegalArgumentException) {
        println("golden: ${e.message}")
        exit(2)
        error("unreachable")
    }
    disableUiTransitions()

    var shown by mutableStateOf<GoldenCase?>(null)
    val inspector = windowInspector()

    // Outside the composition: a story that breaks it must still be reported.
    launchOnUiThread {
        val status = try {
            val report = GoldenSession(
                options = options,
                cases = cases,
                binary = fileStamp("/proc/self/exe").orEmpty(),
                capture = { case ->
                    shown = case
                    capture(inspector, case)
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
private fun GoldenStage(case: GoldenCase?) {
    Column(modifier = Modifier.expand().background(Stage, transitionMs = 0)) {
        // The scroll gives a story its natural size, whatever the window offers.
        Scroll(modifier = Modifier.weight().hExpand(), horizontal = true, vertical = true) {
            // A Scroll takes one child; while the case changes, the old and the new one coexist.
            Box(Modifier.align(h = Align.Start, v = Align.Start)) {
                if (case != null) {
                    key(case.id) {
                        // The same boundary as in the preview, so both show the same story.
                        StyleReset(Modifier.align(h = Align.Start, v = Align.Start).testTag(tagOf(case))) {
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

/**
 * The PNG of the story on the stage, once it is painted and stays the same. Throws when it never
 * is: it renders nothing, it keeps changing, or it does not fit the window.
 */
private suspend fun capture(inspector: Inspector, case: GoldenCase): ByteArray {
    var previous: ByteArray? = null
    var problem = "nothing was painted"
    repeat(ATTEMPTS) {
        inspector.awaitIdle()
        try {
            val node = inspector.find(tagOf(case))
            val stage = inspector.tree().bounds
            val bounds = node.bounds
            check(bounds.x + bounds.width <= stage.width && bounds.y + bounds.height <= stage.height) {
                "the story is ${bounds.width}x${bounds.height} px, more than the ${stage.width}x${stage.height} px stage"
            }
            val picture = inspector.screenshot(node.id)
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
