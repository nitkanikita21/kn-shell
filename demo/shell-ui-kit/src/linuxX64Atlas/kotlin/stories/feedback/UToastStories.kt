package stories.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.compose.Column
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.testTag
import dev.knshell.icons.lucide.Lucide
import dev.knshell.inspect.InteractionState
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.components.feedback.UPosition
import dev.knshell.uikit.components.feedback.UToast
import dev.knshell.uikit.components.feedback.UToastCard
import dev.knshell.uikit.components.feedback.UToastReply
import dev.knshell.uikit.components.feedback.UToasterHost
import dev.knshell.uikit.components.feedback.rememberUToaster
import dev.knshell.uikit.components.feedback.uToastShortcut
import dev.knshell.uikit.core.UAction
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.UVariant
import stories.support.OverlayStage
import stories.support.SampleRow
import stories.support.Samples

private val noop: () -> Unit = {}

/** A window with buttons that add toasts, and the stack of [UToasterHost] in its corner. */
@Composable
private fun ToastStage(position: UPosition = UPosition.BottomEnd) {
    val toaster = rememberUToaster()
    Column(modifier = Modifier.uToastShortcut(toaster)) {
        OverlayStage {
            Row(spacing = 8) {
                UButton(
                    "Скріншот",
                    onClick = {
                        toaster.add(
                            UToast(
                                title = "Скріншот збережено",
                                description = "~/Pictures/shot.png",
                                color = UColor.Success,
                                actions = listOf(UAction("Відкрити", onClick = noop)),
                            ),
                        )
                    },
                    modifier = Modifier.testTag("add"),
                    color = UColor.Neutral,
                    variant = UVariant.Outline,
                )
                UButton(
                    "Помилка",
                    onClick = {
                        toaster.add(
                            UToast(
                                title = "Не вдалося підключитись",
                                description = "Спробуйте ще раз.",
                                color = UColor.Error,
                            ),
                        )
                    },
                    modifier = Modifier.testTag("add-error"),
                    color = UColor.Error,
                    variant = UVariant.Outline,
                )
                UButton("Очистити", onClick = { toaster.clear() }, modifier = Modifier.testTag("clear"), variant = UVariant.Ghost)
            }
        }
        UToasterHost(toaster, position = position)
    }
}

val uToastStories = group("UToast", category = "Feedback") {
    docs(
        "Тимчасове повідомлення. UToastCard — картка сама по собі. UToasterHost складає їх у куток вікна. " +
            "Таймер стоїть під курсором, фокусом і поки в полі відповіді є текст. Помилки не зникають самі. " +
            "F8 переносить фокус до найновішого; Escape на картці закриває її.",
    )

    val title by string("Скріншот збережено")
    val description by string("~/Pictures/shot.png")
    val color by enum(UColor.Success)
    val withIcon by boolean(true)
    val showBar by boolean(true)
    val closable by boolean(true)

    render { v ->
        UToastCard(
            title = v[title],
            description = v[description].ifEmpty { null },
            color = v[color],
            icon = if (v[withIcon]) Lucide.Info else null,
            progress = if (v[showBar]) 0.6f else null,
            onClose = if (v[closable]) noop else null,
            modifier = Modifier.testTag("target"),
        )
    }

    story("Playground")

    variants("Colors") { axis(color) }

    story(
        "States",
        states = listOf(InteractionState.Hover, InteractionState.Focus),
        stateTarget = "card",
    ) {
        Samples {
            SampleRow("картка з відліком") {
                UToastCard(
                    title = "Чат · Дизайн",
                    description = "Подивись на новий фон",
                    icon = Lucide.Bell,
                    progress = 0.6f,
                    onClose = noop,
                    modifier = Modifier.testTag("card"),
                )
            }
        }
    }

    story("Reply") {
        Samples {
            SampleRow("відповідь: Enter надсилає, Escape повертає фокус на картку") {
                UToastCard(
                    title = "Чат · Дизайн",
                    description = "Подивись на новий фон",
                    icon = Lucide.Bell,
                    meta = { Label("14:02") },
                    reply = UToastReply(onSend = {}),
                    progress = 0.6f,
                    onClose = noop,
                )
            }
        }
    }

    story(
        "Keyboard",
        play = Play {
            focus("card")
            press(Key.Escape)
            expectAbsent("card")
        },
    ) {
        var shown by remember { mutableStateOf(true) }
        OverlayStage {
            if (shown) {
                UToastCard(
                    title = "Скріншот збережено",
                    description = "~/Pictures/shot.png",
                    color = UColor.Success,
                    icon = Lucide.CircleCheck,
                    onClose = { shown = false },
                    modifier = Modifier.testTag("card"),
                )
            }
        }
    }

    story(
        "Stack keyboard",
        play = Play {
            click("add")
            click("add-error")
            focus("add-error")
            press(Key.F8)
            expect("uToast-2").focused()
            press(Key.Escape)
            expectAbsent("uToast-2")
            focus("add")
            press(Key.F8)
            expect("uToast-1").focused()
            press(Key.Escape)
            expectAbsent("uToast-1")
        },
    ) {
        ToastStage()
    }

    story(
        "Stack",
        play = Play {
            click("add")
            click("add-error")
            snapshot("stack")
        },
    ) {
        ToastStage(position = UPosition.TopCenter)
    }

    story(
        "Accessibility",
        play = Play {
            click("add")
            expect("uToast-1").role("status").name("Скріншот збережено").description("~/Pictures/shot.png")
            click("add-error")
            expect("uToast-2").role("alert").name("Не вдалося підключитись")
        },
    ) {
        ToastStage()
    }

    story("Examples") {
        Samples {
            SampleRow("повідомлення з аватаром, дією та відліком") {
                UToastCard(
                    title = "Пошта",
                    description = "3 нові листи",
                    leading = { Label("ПШ") },
                    meta = { Label("13:48") },
                    actions = listOf(
                        UAction("Відкрити", onClick = noop),
                        UAction("Прочитано", onClick = noop),
                    ),
                    progress = 0.4f,
                    onClose = noop,
                )
            }
        }
    }
}
