package stories.feedback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.atlas.core.entry.MatrixView
import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.compose.Column
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.width
import dev.knshell.compose.testTag
import dev.knshell.icons.lucide.Lucide
import dev.knshell.inspect.InteractionState
import dev.knshell.uikit.components.feedback.UAlert
import dev.knshell.uikit.core.UAction
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.UVariant
import stories.support.OverlayStage
import stories.support.Samples

private val noop: () -> Unit = {}

val uAlertStories = group("UAlert", category = "Feedback") {
    docs(
        "Повідомлення на місці. Error і Warning оголошуються одразу, решта як статус. " +
            "Tab іде по діях, потім до кнопки закриття; Enter або Space виконують.",
    )

    val title by string("Доступне оновлення")
    val description by string("Версія 0.4 чекає на встановлення.")
    val color by enum(UColor.Info)
    val variant by enum(UVariant.Soft)
    val closable by boolean(true)

    render { v ->
        UAlert(
            title = v[title],
            description = v[description].ifEmpty { null },
            modifier = Modifier.testTag("target"),
            color = v[color],
            variant = v[variant],
            onClose = if (v[closable]) noop else null,
        )
    }

    story("Playground")

    variants("Variants", view = MatrixView.Table) { axis(color); axis(variant) }

    story(
        "States",
        states = listOf(InteractionState.Hover, InteractionState.Focus),
        stateTarget = "action",
    ) {
        Samples {
            UAlert(
                "Немає мережі",
                description = "Перевірте кабель або Wi-Fi.",
                color = UColor.Error,
                variant = UVariant.Solid,
                actions = listOf(UAction("Повторити", icon = Lucide.RefreshCw, testTag = "action", onClick = noop)),
                onClose = noop,
            )
        }
    }

    story(
        "Keyboard",
        play = Play {
            focus("first")
            press(Key.Enter)
            expect("count").text("Дій: 1")
            press(Key.Tab)
            expect("second").focused()
            // After the actions comes the close button.
            press(Key.Tab)
            press(Key.Enter)
            expectAbsent("alert")
            expect("result").text("Закрито")
        },
    ) {
        var count by remember { mutableStateOf(0) }
        var shown by remember { mutableStateOf(true) }
        OverlayStage {
            Column(spacing = 8) {
                if (shown) {
                    UAlert(
                        "Доступне оновлення",
                        description = "Версія 0.4 чекає на встановлення.",
                        modifier = Modifier.testTag("alert"),
                        actions = listOf(
                            UAction("Встановити", testTag = "first", onClick = { count++ }),
                            UAction("Пізніше", testTag = "second", onClick = noop),
                        ),
                        onClose = { shown = false },
                    )
                }
                Label("Дій: $count", align = 0f, modifier = Modifier.testTag("count"))
                Label(if (shown) "Відкрито" else "Закрито", align = 0f, modifier = Modifier.testTag("result"))
            }
        }
    }

    story(
        "Accessibility",
        play = Play {
            expect("info").role("status").name("Доступне оновлення")
                .description("Версія 0.4 чекає на встановлення.")
            expect("error").role("alert").name("Немає мережі")
        },
    ) {
        Samples {
            UAlert(
                "Доступне оновлення",
                description = "Версія 0.4 чекає на встановлення.",
                modifier = Modifier.testTag("info"),
            )
            UAlert(
                "Немає мережі",
                color = UColor.Error,
                modifier = Modifier.testTag("error"),
            )
        }
    }

    story("Examples") {
        Samples {
            UAlert("Мало місця", description = "Лишилось 2 ГБ.", color = UColor.Warning, variant = UVariant.Outline)
            UAlert("Збережено", description = "Налаштування застосовано.", color = UColor.Success, variant = UVariant.Subtle)
        }
    }

    // Text longer than one line, so that a picture shows it wraps.
    story("Long text") {
        Column(modifier = Modifier.width(420)) {
            UAlert(
                "Мало місця на диску, і це заголовок, довший за звичайний",
                description = "Лишилося 2 ГБ. Звільніть місце, інакше оновлення не встановиться, а система " +
                    "нагадуватиме про це щодня, доки місця знову не вистачить.",
                color = UColor.Warning,
            )
        }
    }
}
