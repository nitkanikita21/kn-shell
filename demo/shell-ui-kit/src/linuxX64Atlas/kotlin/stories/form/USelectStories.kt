package stories.form

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
import dev.knshell.inspect.InteractionState
import dev.knshell.uikit.components.form.USelect
import dev.knshell.uikit.components.form.USelectDefaults
import dev.knshell.uikit.components.form.USelectUi
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import stories.support.SampleRow
import stories.support.Samples

private val fonts = listOf("Adwaita Sans", "JetBrains Mono", "Noto Sans")

/**
 * A select that keeps its own value, starting at [initial], with the value shown in a label. Only a
 * [tagged] select carries test tags, which must be unique within a story.
 */
@Composable
private fun SampleSelect(
    initial: String? = null,
    placeholder: String = "Оберіть шрифт",
    color: UColor = UColor.Primary,
    size: USize = USize.Md,
    disabled: Boolean = false,
    invalid: Boolean = false,
    tagged: Boolean = true,
) {
    var value by remember { mutableStateOf(initial) }
    Samples {
        USelect(
            items = fonts,
            value = value,
            onValueChange = { value = it },
            label = { it },
            modifier = if (tagged) Modifier.testTag("trigger") else Modifier,
            placeholder = placeholder,
            color = color,
            size = size,
            disabled = disabled,
            invalid = invalid,
            accessibleLabel = "Шрифт",
            ui = if (tagged) USelectUi(list = Modifier.testTag("list")) else USelectUi(),
        )
        Label(
            "Обрано: ${value ?: "нічого"}",
            align = 0f,
            modifier = if (tagged) Modifier.testTag("result") else Modifier,
        )
    }
}

val uSelectStories = group("USelect", category = "Form") {
    docs(
        "Вибір одного значення зі списку, що відкривається. Enter, Space, Down або Up відкривають; " +
            "літери переходять за назвою; Enter або Space вибирають; Tab вибирає й іде далі; Escape закриває.",
    )

    val placeholder by string("Оберіть шрифт")
    val color by enum(UColor.Primary)
    val size by enum(USize.Md)
    val disabled by boolean(false)
    val invalid by boolean(false)

    render { v ->
        SampleSelect(
            placeholder = v[placeholder],
            color = v[color],
            size = v[size],
            disabled = v[disabled],
            invalid = v[invalid],
        )
    }

    story("Playground")

    variants("Sizes") { axis(size) }

    story("States", states = listOf(InteractionState.Hover, InteractionState.Focus), stateTarget = "trigger") {
        Samples {
            SampleRow("вибрано, порожнє, disabled, invalid") {
                SampleSelect(initial = "JetBrains Mono")
                SampleSelect(tagged = false)
                SampleSelect(disabled = true, tagged = false)
                SampleSelect(invalid = true, tagged = false)
            }
        }
    }

    story(
        "Keyboard",
        play = Play {
            focus("trigger")
            press(Key.Down)
            expect("list").role("listbox")
            snapshot("open", "list")
            // The list opens with the first option focused.
            press(Key.Down)
            press(Key.Enter)
            expectAbsent("list")
            expect("result").text("Обрано: JetBrains Mono")
            expect("trigger").focused()
            // Typing moves to the option whose name starts with the letter.
            press(Key.Enter)
            type("n")
            press(Key.Tab)
            expectAbsent("list")
            expect("result").text("Обрано: Noto Sans")
            // Escape closes the list without a change.
            press(Key.Enter)
            type("a")
            press(Key.Escape)
            expectAbsent("list")
            expect("result").text("Обрано: Noto Sans")
        },
    ) {
        SampleSelect()
    }

    story(
        "Accessibility",
        play = Play {
            expect("trigger").role("combobox").name("Шрифт").expanded(false)
            click("trigger")
            expect("trigger").expanded(true)
            expect("list").role("listbox").name("Шрифт")
            press(Key.Escape)
            expect("trigger").expanded(false)
        },
    ) {
        SampleSelect()
    }

    story("Examples") {
        Samples {
            SampleRow("стандарти з теми: UThemeConfig(USelectDefaults(…))") {
                UTheme(config = UThemeConfig(USelectDefaults(color = UColor.Success, size = USize.Sm))) {
                    SampleSelect(initial = "Noto Sans", tagged = false)
                }
            }
        }
    }
}
