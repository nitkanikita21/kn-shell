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
import dev.knshell.compose.radius
import dev.knshell.compose.testTag
import dev.knshell.icons.IconRef
import dev.knshell.icons.lucide.Lucide
import dev.knshell.inspect.InteractionState
import dev.knshell.uikit.components.form.UInput
import dev.knshell.uikit.components.form.UInputDefaults
import dev.knshell.uikit.components.form.UInputType
import dev.knshell.uikit.components.form.UInputUi
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import stories.support.SampleRow
import stories.support.Samples

/** A field that keeps the text typed into it. */
@Composable
private fun SampleInput(
    initial: String = "",
    modifier: Modifier = Modifier,
    placeholder: String = "",
    color: UColor = UColor.Primary,
    size: USize = USize.Md,
    type: UInputType = UInputType.Text,
    leadingIcon: IconRef? = null,
    trailingIcon: IconRef? = null,
    disabled: Boolean = false,
    invalid: Boolean = false,
    accessibleLabel: String = "Поле",
    ui: UInputUi = UInputUi(),
) {
    var text by remember(initial) { mutableStateOf(initial) }
    UInput(
        value = text,
        onValueChange = { text = it },
        modifier = modifier,
        placeholder = placeholder,
        type = type,
        color = color,
        size = size,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        disabled = disabled,
        invalid = invalid,
        accessibleLabel = accessibleLabel,
        ui = ui,
    )
}

val uInputStories = group("UInput", category = "Form") {
    docs(
        "Однорядкове поле. Роль textbox: текст уводиться з клавіатури, Tab виходить із поля. " +
            "Без UFormField потрібна accessibleLabel.",
    )

    val initial by string("")
    val placeholder by string("Пошук…")
    val color by enum(UColor.Primary)
    val size by enum(USize.Md)
    val type by enum(UInputType.Text)
    val disabled by boolean(false)
    val invalid by boolean(false)

    render { v ->
        SampleInput(
            initial = v[initial],
            modifier = Modifier.testTag("target"),
            placeholder = v[placeholder],
            color = v[color],
            size = v[size],
            type = v[type],
            disabled = v[disabled],
            invalid = v[invalid],
            accessibleLabel = "Пошук",
        )
    }

    story("Playground")

    variants("Colors") { axis(color) }

    variants("Sizes") { axis(size) }

    story("States", states = listOf(InteractionState.Hover, InteractionState.Focus), stateTarget = "field") {
        Samples {
            SampleRow("порожнє з іконкою, заповнене") {
                SampleInput(
                    modifier = Modifier.testTag("field"),
                    placeholder = "Пошук…",
                    leadingIcon = Lucide.Search,
                    accessibleLabel = "Пошук",
                )
                SampleInput(initial = "user", accessibleLabel = "Користувач")
            }
            SampleRow("invalid, disabled, пароль") {
                SampleInput(initial = "bad@", invalid = true, accessibleLabel = "Пошта")
                SampleInput(initial = "disabled", disabled = true, accessibleLabel = "Вимкнено")
                SampleInput(initial = "секрет", type = UInputType.Password, accessibleLabel = "Пароль")
            }
        }
    }

    story(
        "Keyboard",
        play = Play {
            focus("field")
            expect("field").focused()
            snapshot("focused")
            type("kn")
            expect("echo").text("Текст: kn")
            press(Key.Backspace)
            expect("echo").text("Текст: k")
            // Tab leaves the field: the focus goes on to the next one.
            press(Key.Tab)
            expect("next").focused()
        },
    ) {
        var text by remember { mutableStateOf("") }
        Samples {
            SampleRow("Tab переходить між полями") {
                UInput(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.testTag("field"),
                    placeholder = "Ім'я",
                    accessibleLabel = "Ім'я",
                )
                UInput(
                    value = "",
                    onValueChange = {},
                    modifier = Modifier.testTag("next"),
                    placeholder = "Місто",
                    accessibleLabel = "Місто",
                )
            }
            Label("Текст: $text", align = 0f, modifier = Modifier.testTag("echo"))
        }
    }

    story(
        "Accessibility",
        play = Play {
            expect("field").role("textbox").name("Ім'я")
            expect("bad").state("invalid", "true")
            expect("off").state("disabled", "true").enabled(false)
        },
    ) {
        SampleRow("назва з accessibleLabel, invalid, disabled") {
            UInput(
                value = "",
                onValueChange = {},
                modifier = Modifier.testTag("field"),
                accessibleLabel = "Ім'я",
            )
            UInput(
                value = "bad@",
                onValueChange = {},
                modifier = Modifier.testTag("bad"),
                accessibleLabel = "Пошта",
                invalid = true,
            )
            UInput(
                value = "",
                onValueChange = {},
                modifier = Modifier.testTag("off"),
                accessibleLabel = "Вимкнено",
                disabled = true,
            )
        }
    }

    story("Examples") {
        Samples {
            SampleRow("слоти: ui = UInputUi(…)") {
                SampleInput(
                    placeholder = "Пошук…",
                    leadingIcon = Lucide.Search,
                    accessibleLabel = "Пошук",
                    ui = UInputUi(base = Modifier.radius(999)),
                )
            }
            SampleRow("стандарти з теми: UThemeConfig(UInputDefaults(…))") {
                UTheme(config = UThemeConfig(UInputDefaults(color = UColor.Success, size = USize.Lg))) {
                    SampleInput(initial = "k-7F2A", accessibleLabel = "Код")
                }
            }
        }
    }
}
