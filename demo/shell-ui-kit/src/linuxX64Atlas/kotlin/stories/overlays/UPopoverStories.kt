package stories.overlays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.testTag
import dev.knshell.icons.lucide.Lucide
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.components.overlays.UPopover
import dev.knshell.uikit.components.overlays.UPopoverUi
import dev.knshell.uikit.core.UAlign
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USide
import dev.knshell.uikit.core.UVariant
import stories.support.OverlayStage

/** A button that opens a panel with two controls; the answer of the last control is shown under it. */
@Composable
private fun SamplePopover(
    label: String,
    side: USide = USide.Bottom,
    align: UAlign = UAlign.Start,
    startOpen: Boolean = false,
) {
    var open by remember { mutableStateOf(startOpen) }
    var result by remember { mutableStateOf("—") }
    OverlayStage {
        UPopover(
            open = open,
            onOpenChange = { open = it },
            accessibleLabel = label,
            side = side,
            align = align,
            ui = UPopoverUi(body = Modifier.testTag("panel")),
            content = {
                UButton(
                    "Вимкнути звук",
                    onClick = { result = "звук вимкнено" },
                    modifier = Modifier.testTag("mute"),
                    color = UColor.Neutral,
                    variant = UVariant.Outline,
                )
                UButton(
                    "Скинути",
                    onClick = { result = "скинуто" },
                    modifier = Modifier.testTag("reset"),
                    color = UColor.Neutral,
                    variant = UVariant.Outline,
                )
            },
            trigger = { trigger ->
                UButton(
                    label,
                    onClick = trigger.onClick,
                    modifier = Modifier.testTag("trigger").then(trigger.modifier),
                    color = UColor.Neutral,
                    variant = UVariant.Outline,
                    trailingIcon = Lucide.ChevronDown,
                )
            },
        )
        Label("Відповідь: $result", align = 0f, modifier = Modifier.testTag("result"))
    }
}

val uPopoverStories = group("UPopover", category = "Overlays") {
    docs(
        "Панель довільного вмісту біля тригера. Клік відкриває її, фокус іде на перший елемент. " +
            "Escape або клік поруч закривають і повертають фокус на тригер; Tab після останнього елемента закриває й іде далі.",
    )

    val label by string("Гучність")
    val side by enum(USide.Bottom)
    val align by enum(UAlign.Start)

    render { v -> SamplePopover(label = v[label], side = v[side], align = v[align]) }

    story("Playground")

    story(
        "Keyboard",
        play = Play {
            focus("trigger")
            press(Key.Enter)
            expect("panel").role("dialog").name("Гучність")
            snapshot("open", "panel")
            // The first control takes the focus when the panel opens.
            expect("mute").focused()
            press(Key.Tab)
            expect("reset").focused()
            // Tab past the last control closes the panel.
            press(Key.Tab)
            expectAbsent("panel")
            // Escape closes and gives the focus back to the trigger.
            focus("trigger")
            press(Key.Space)
            expect("mute").focused()
            press(Key.Escape)
            expectAbsent("panel")
            expect("trigger").focused()
            press(Key.Enter)
            press(Key.Enter)
            expect("result").text("Відповідь: звук вимкнено")
        },
    ) {
        SamplePopover("Гучність")
    }

    story(
        "Accessibility",
        play = Play {
            expect("trigger").expanded(false)
            click("trigger")
            expect("trigger").expanded()
            expect("panel").role("dialog").name("Гучність")
        },
    ) {
        SamplePopover("Гучність")
    }

    story("Examples") {
        SamplePopover("Вгору, по центру", side = USide.Top, align = UAlign.Center, startOpen = true)
    }
}
