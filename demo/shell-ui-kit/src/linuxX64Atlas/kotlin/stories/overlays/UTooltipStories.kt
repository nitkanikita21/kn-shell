package stories.overlays

import androidx.compose.runtime.Composable
import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.compose.Key
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.testTag
import dev.knshell.icons.lucide.Lucide
import dev.knshell.inspect.InteractionState
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.components.overlays.UTooltip
import dev.knshell.uikit.components.overlays.UTooltipUi
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USide
import dev.knshell.uikit.core.UVariant
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import dev.knshell.uikit.components.overlays.UTooltipDefaults
import stories.support.OverlayStage
import stories.support.SampleRow
import stories.support.Samples

/** A copy button with a tooltip, followed by a second button to move the focus past it. */
@Composable
private fun SampleTooltip(
    text: String,
    kbds: List<String> = emptyList(),
    delayMs: Int = 500,
    side: USide = USide.Top,
) {
    OverlayStage {
        Row(spacing = 8) {
            UTooltip(
                text,
                kbds = kbds,
                delayMs = delayMs,
                side = side,
                ui = UTooltipUi(bubble = Modifier.testTag("tip")),
                trigger = { trigger ->
                    UButton(
                        onClick = trigger.onClick,
                        modifier = Modifier.testTag("trigger").then(trigger.modifier),
                        color = UColor.Neutral,
                        variant = UVariant.Outline,
                        leadingIcon = Lucide.Copy,
                        accessibleLabel = "Копіювати",
                    )
                },
            )
            UButton(
                "Далі",
                onClick = {},
                modifier = Modifier.testTag("next"),
                color = UColor.Neutral,
                variant = UVariant.Outline,
            )
        }
    }
}

val uTooltipStories = group("UTooltip", category = "Overlays") {
    docs(
        "Підказка про елемент. Відкривається одразу, коли елемент отримує фокус, і після затримки, коли " +
            "на ньому затримується вказівник. Escape, клік, вихід фокуса чи вказівника ховають її. Текст стає описом елемента.",
    )

    val text by string("Копіювати")
    val withKeys by boolean(true)
    val side by enum(USide.Top)
    val delay by int(500, 0..2000)

    render { v ->
        SampleTooltip(
            text = v[text],
            kbds = if (v[withKeys]) listOf("Ctrl", "C") else emptyList(),
            delayMs = v[delay],
            side = v[side],
        )
    }

    story("Playground")

    story("States", states = listOf(InteractionState.Hover, InteractionState.Focus), stateTarget = "trigger") {
        Samples {
            SampleRow("кнопка з підказкою, у фокусі та при наведенні") {
                SampleTooltip("Копіювати", kbds = listOf("Ctrl", "C"), delayMs = 0)
            }
        }
    }

    story(
        "Keyboard",
        play = Play {
            focus("trigger")
            expect("tip").role("tooltip")
            snapshot("open", "tip")
            // Escape hides it and keeps the focus.
            press(Key.Escape)
            expectAbsent("tip")
            expect("trigger").focused()
            press(Key.Tab)
            expect("next").focused()
            expectAbsent("tip")
            press(Key.Tab, shift = true)
            expect("trigger").focused()
            expect("tip").role("tooltip")
            // A click hides it too.
            click("trigger")
            expectAbsent("tip")
        },
    ) {
        SampleTooltip("Копіювати", kbds = listOf("Ctrl", "C"))
    }

    story(
        "Accessibility",
        play = Play {
            focus("trigger")
            expect("trigger").role("button").name("Копіювати").description("Копіювати")
            expect("tip").role("tooltip")
        },
    ) {
        SampleTooltip("Копіювати")
    }

    story("Examples") {
        Samples {
            SampleRow("знизу, без затримки, з клавішами") {
                SampleTooltip("Зберегти файл", kbds = listOf("Ctrl", "S"), delayMs = 0, side = USide.Bottom)
            }
            SampleRow("стандарти з теми: UThemeConfig(UTooltipDefaults(…))") {
                UTheme(config = UThemeConfig(UTooltipDefaults(side = USide.Bottom, delayMs = 200))) {
                    UTooltip(
                        "Тема задає сторону і затримку",
                        trigger = { trigger ->
                            UButton(
                                onClick = trigger.onClick,
                                modifier = trigger.modifier,
                                color = UColor.Neutral,
                                variant = UVariant.Outline,
                                leadingIcon = Lucide.Info,
                                accessibleLabel = "Підказка",
                            )
                        },
                    )
                }
            }
        }
    }
}
