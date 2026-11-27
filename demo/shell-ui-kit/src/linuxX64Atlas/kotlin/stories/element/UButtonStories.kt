package stories.element

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.atlas.core.entry.MatrixView
import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.testTag
import dev.knshell.icons.lucide.Lucide
import dev.knshell.inspect.InteractionState
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.components.element.UButtonDefaults
import dev.knshell.uikit.components.element.UButtonUi
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.UVariant
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import dev.knshell.compose.radius
import stories.support.SampleRow
import stories.support.Samples

val uButtonStories = group("UButton", category = "Element") {
    docs("Дія. Роль button: Enter, Space і клік виконують onClick. Без тексту потрібен accessibleLabel.")

    val label by string("Зберегти")
    val color by enum(UColor.Primary)
    val variant by enum(UVariant.Solid)
    val size by enum(USize.Md)
    val loading by boolean(false)
    val disabled by boolean(false)

    render { v ->
        UButton(
            v[label],
            onClick = {},
            modifier = Modifier.testTag("target"),
            color = v[color],
            variant = v[variant],
            size = v[size],
            loading = v[loading],
            disabled = v[disabled],
        )
    }

    story("Playground")

    variants("Variants", view = MatrixView.Table) { axis(color); axis(variant) }

    variants("Sizes") { axis(size) }

    story("States", states = listOf(InteractionState.Hover, InteractionState.Focus, InteractionState.Pressed)) {
        Samples {
            SampleRow("звичайна, disabled, loading") {
                UButton("Зберегти", onClick = {}, modifier = Modifier.testTag("target"))
                UButton("Зберегти", onClick = {}, disabled = true)
                UButton("Зберегти", onClick = {}, loading = true)
            }
            SampleRow("з іконками") {
                UButton("Додати", onClick = {}, leadingIcon = Lucide.Plus)
                UButton("Далі", onClick = {}, variant = UVariant.Outline, trailingIcon = Lucide.ArrowRight)
                UButton(onClick = {}, variant = UVariant.Soft, leadingIcon = Lucide.Trash2, accessibleLabel = "Видалити")
            }
        }
    }

    story(
        "Keyboard",
        play = Play {
            focus("save")
            expect("save").focused()
            press(Key.Enter)
            expect("count").text("Натиснуто: 1")
            press(Key.Space)
            expect("count").text("Натиснуто: 2")
            press(Key.Tab)
            expect("busy").focused()
            press(Key.Enter)
            expect("count").text("Натиснуто: 2")
            // A disabled button is not a Tab stop.
            press(Key.Tab)
            expect("save").focused()
        },
    ) {
        var count by remember { mutableStateOf(0) }
        Samples {
            SampleRow("loading ігнорує натискання, disabled не фокусується") {
                UButton("Зберегти", onClick = { count++ }, modifier = Modifier.testTag("save"))
                UButton("Зачекайте", onClick = { count++ }, modifier = Modifier.testTag("busy"), loading = true)
                UButton("Недоступна", onClick = { count++ }, modifier = Modifier.testTag("off"), disabled = true)
            }
            Label("Натиснуто: $count", align = 0f, modifier = Modifier.testTag("count"))
        }
    }

    story(
        "Accessibility",
        play = Play {
            expect("text").role("button").name("Зберегти")
            expect("icon").role("button").name("Видалити")
            expect("busy").state("busy", "true").enabled()
            expect("off").state("disabled", "true").enabled(false)
        },
    ) {
        SampleRow("назва: текст або accessibleLabel") {
            UButton("Зберегти", onClick = {}, modifier = Modifier.testTag("text"))
            UButton(
                onClick = {},
                modifier = Modifier.testTag("icon"),
                leadingIcon = Lucide.Trash2,
                accessibleLabel = "Видалити",
            )
            UButton("Зачекайте", onClick = {}, modifier = Modifier.testTag("busy"), loading = true)
            UButton("Недоступна", onClick = {}, modifier = Modifier.testTag("off"), disabled = true)
        }
    }

    story("Examples") {
        Samples {
            SampleRow("стандарти з теми: UThemeConfig(UButtonDefaults(…))") {
                UTheme(config = UThemeConfig(UButtonDefaults(color = UColor.Neutral, variant = UVariant.Outline))) {
                    UButton("Відкрити", onClick = {})
                    UButton("Головна дія", onClick = {}, color = UColor.Primary, variant = UVariant.Solid)
                }
            }
            SampleRow("перевизначення слотів: ui = UButtonUi(…)") {
                UButton("Таблетка", onClick = {}, ui = UButtonUi(base = Modifier.radius(999)))
                UButton("Видалити", onClick = {}, color = UColor.Error, variant = UVariant.Link)
            }
        }
    }
}
