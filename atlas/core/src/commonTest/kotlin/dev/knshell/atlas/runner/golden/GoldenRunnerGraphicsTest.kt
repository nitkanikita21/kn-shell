package dev.knshell.atlas.runner.golden

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.atlas.runner.AtlasEnvironment
import dev.knshell.compose.Box
import dev.knshell.compose.Color
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Semantics
import dev.knshell.compose.Tristate
import dev.knshell.compose.background
import dev.knshell.compose.closeComposeWindows
import dev.knshell.compose.focusable
import dev.knshell.compose.hoverBackground
import dev.knshell.compose.launchOnUiThread
import dev.knshell.compose.onKey
import dev.knshell.compose.padding
import dev.knshell.compose.runComposeWindow
import dev.knshell.compose.semantics
import dev.knshell.compose.testTag
import dev.knshell.inspect.InteractionState
import dev.knshell.inspect.VirtualKeyboard
import dev.knshell.inspect.disableUiTransitions
import dev.knshell.inspect.windowInspector
import dev.knshell.shell.Popup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The golden runner against a real window: states, key presses, popups and expectations. */
class GoldenRunnerGraphicsTest {

    private val catalog = listOf(
        group("Toggle") {
            render { _ ->
                var on by remember { mutableStateOf(false) }
                var open by remember { mutableStateOf(false) }
                Popup(
                    open = open,
                    onDismiss = { open = false },
                    content = { Box(Modifier.testTag("menu").focusable()) { Label("menu") } },
                ) {
                    Box(
                        Modifier.testTag("target")
                            .background(Color.hex(0x303040), transitionMs = 0)
                            .hoverBackground(Color.hex(0x606080))
                            .padding(all = 12)
                            .semantics(Semantics(role = Role.Switch, checked = if (on) Tristate.True else Tristate.False))
                            .focusable()
                            .onKey { event ->
                                when (event.key) {
                                    Key.Space -> on = !on
                                    Key.Down -> open = true
                                    else -> return@onKey false
                                }
                                true
                            },
                    ) { Label(if (on) "on" else "off") }
                }
            }
            story(
                "Keyboard",
                states = listOf(InteractionState.Hover),
                play = Play {
                    focus("target")
                    expect("target").role("switch").checked("false").focused()
                    press(Key.Space)
                    expect("target").checked("true")
                    snapshot("on")
                    press(Key.Down)
                    expect("menu").focused()
                    snapshot("open")
                    press(Key.Escape)
                    expectAbsent("menu")
                    expect("target").focused()
                },
            )
            story("Broken", play = Play { expect("target").checked("true") })
        },
    )

    @Test
    fun `states keys popups and expectations work on the stage`() {
        val environment = AtlasEnvironment("Only") { it() }
        val cases = goldenCases(catalog, environments = listOf(environment))
        var shown by mutableStateOf<GoldenCase?>(null)
        val shots = mutableListOf<CaseShots>()
        var failure: Throwable? = null
        disableUiTransitions()

        launchOnUiThread {
            try {
                val inspector = windowInspector()
                val keyboard = checkNotNull(VirtualKeyboard.fromEnvironment()) { "run under headless-compositor" }
                cases.forEach { case ->
                    shown = case
                    shots += shoot(inspector, keyboard, case)
                }
            } catch (e: Throwable) {
                failure = e
            } finally {
                closeComposeWindows()
            }
        }
        runComposeWindow("atlas golden test", width = 600, height = 400) { GoldenStage(shown) }

        failure?.let { throw it }
        val (keys, broken) = shots
        assertNull(keys.playFailure)
        assertEquals(listOf("hover", "on", "open"), keys.extra.map { it.first })
        val pictures = keys.extra.toMap()
        assertFalse(keys.base.contentEquals(pictures.getValue("hover")), "hover must look different")
        assertFalse(keys.base.contentEquals(pictures.getValue("on")), "the toggled switch must look different")
        assertEquals("after the start: target: expected checked true, got false", broken.playFailure)
        assertTrue(broken.extra.isEmpty())
    }
}
