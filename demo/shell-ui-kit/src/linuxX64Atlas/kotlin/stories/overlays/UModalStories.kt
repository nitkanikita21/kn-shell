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
import dev.knshell.compose.padding
import dev.knshell.compose.testTag
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.components.overlays.UModal
import dev.knshell.uikit.components.overlays.UModalRole
import dev.knshell.uikit.components.overlays.UModalUi
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.UVariant
import dev.knshell.uikit.theme.USurface
import dev.knshell.uikit.theme.UTheme
import stories.support.OverlayStage
import stories.support.sampleTokens

/** A button that opens a dialog with the given settings. */
@Composable
private fun SampleModal(
    role: UModalRole = UModalRole.Dialog,
    dismissible: Boolean = true,
    title: String = "Перейменувати простір",
    description: String? = "Нову назву побачать у панелі та в перемикачі просторів.",
    startOpen: Boolean = false,
    confirm: String = "Зберегти",
    confirmColor: UColor = UColor.Primary,
    extra: @Composable () -> Unit = {},
) {
    var open by remember { mutableStateOf(startOpen) }
    var result by remember { mutableStateOf("—") }
    fun close(with: String) {
        result = with
        open = false
    }
    OverlayStage {
        UButton("Відкрити", onClick = { open = true }, modifier = Modifier.testTag("trigger"))
        Label("Відповідь: $result", align = 0f, modifier = Modifier.testTag("result"))
    }
    UModal(
        open = open,
        onOpenChange = { open = it },
        title = title,
        description = description,
        role = role,
        dismissible = dismissible,
        ui = UModalUi(content = Modifier.testTag("dialog")),
        footer = {
            UButton(
                "Скасувати",
                onClick = { close("скасовано") },
                modifier = Modifier.testTag("cancel"),
                color = UColor.Neutral,
                variant = UVariant.Outline,
            )
            UButton(
                confirm,
                onClick = { close("збережено") },
                modifier = Modifier.testTag("confirm"),
                color = confirmColor,
            )
        },
    ) {
        extra()
    }
}

val uModalStories = group("UModal", category = "Overlays") {
    docs("Діалог поверх вікна. Фокус лишається всередині; Escape або клік поруч закривають dismissible.")

    val title by string("Перейменувати простір")
    val description by string("Нову назву побачать у панелі та в перемикачі просторів.")
    val role by enum(UModalRole.Dialog)
    val dismissible by boolean(true)

    render { v ->
        SampleModal(
            role = v[role],
            dismissible = v[dismissible],
            title = v[title],
            description = v[description].ifEmpty { null },
        )
    }

    story("Playground")

    story("Open") { SampleModal(startOpen = true) }

    story("Alert dialog") {
        SampleModal(
            role = UModalRole.AlertDialog,
            title = "Видалити простір?",
            description = "Вікна переїдуть у перший простір.",
            startOpen = true,
            confirm = "Видалити",
            confirmColor = UColor.Error,
        )
    }

    story(
        "Keyboard",
        play = Play {
            focus("trigger")
            press(Key.Enter)
            expect("dialog").role("dialog")
            snapshot("open")
            // The keyboard stays inside: the close button, then the footer, around.
            press(Key.Tab)
            expect("cancel").focused()
            press(Key.Tab)
            expect("confirm").focused()
            press(Key.Tab)
            press(Key.Tab)
            expect("cancel").focused()
            press(Key.Tab, shift = true)
            press(Key.Tab, shift = true)
            expect("confirm").focused()
            press(Key.Escape)
            expectAbsent("dialog")
            expect("trigger").focused()
            // Closing by a button also gives the focus back.
            press(Key.Space)
            press(Key.Tab)
            press(Key.Tab)
            press(Key.Enter)
            expectAbsent("dialog")
            expect("result").text("Відповідь: збережено")
            expect("trigger").focused()
        },
    ) {
        SampleModal()
    }

    story(
        "Alert keyboard",
        play = Play {
            focus("trigger")
            press(Key.Enter)
            // The safe action is focused.
            expect("cancel").focused()
            press(Key.Escape)
            expect("dialog").role("alertdialog")
        },
    ) {
        SampleModal(role = UModalRole.AlertDialog, dismissible = false)
    }

    story(
        "Accessibility",
        play = Play {
            click("trigger")
            expect("dialog")
                .role("dialog")
                .name("Перейменувати простір")
                .description("Нову назву побачать у панелі та в перемикачі просторів.")
                .state("modal", "true")
        },
    ) {
        SampleModal()
    }

    story(
        "Theme change",
        play = Play {
            click("trigger")
            snapshot("dark")
            click("switch-theme")
            snapshot("light")
            expect("dialog").role("dialog")
        },
    ) {
        // The theme is chosen inside the story, whatever the environment: the open dialog follows.
        var isDark by remember { mutableStateOf(true) }
        UTheme(sampleTokens(isDark, hue = 280f)) {
            USurface(Modifier.padding(all = 12)) {
                SampleModal {
                    UButton(
                        "Змінити тему",
                        onClick = { isDark = !isDark },
                        modifier = Modifier.testTag("switch-theme"),
                        color = UColor.Secondary,
                    )
                }
            }
        }
    }
}
