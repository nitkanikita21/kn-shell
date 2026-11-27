package stories.form

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.compose.Key
import dev.knshell.compose.Modifier
import dev.knshell.compose.padding
import dev.knshell.compose.testTag
import dev.knshell.inspect.InteractionState
import dev.knshell.uikit.components.form.UFormField
import dev.knshell.uikit.components.form.UFormFieldDefaults
import dev.knshell.uikit.components.form.UFormFieldUi
import dev.knshell.uikit.components.form.UInput
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import stories.support.SampleRow
import stories.support.Samples

val uFormFieldStories = group("UFormField", category = "Form") {
    docs(
        "Назва, підказка чи помилка і поле, до якого вони прив'язані. Клік по назві переносить фокус " +
            "у поле; помилка робить поле invalid.",
    )

    val label by string("Ім'я хоста")
    val description by string("Лише латиниця й дефіс.")
    val error by string("")
    val required by boolean(false)

    render { v ->
        UFormField(
            label = v[label],
            description = v[description].ifEmpty { null },
            error = v[error].ifEmpty { null },
            required = v[required],
        ) {
            UInput(value = "workstation", onValueChange = {}, modifier = Modifier.testTag("target"))
        }
    }

    story("Playground")

    story("States", states = listOf(InteractionState.Hover, InteractionState.Focus), stateTarget = "field") {
        Samples {
            SampleRow("підказка, обов'язкове") {
                UFormField(label = "Ім'я хоста", description = "Лише латиниця й дефіс.", required = true) {
                    UInput(
                        value = "workstation",
                        onValueChange = {},
                        modifier = Modifier.testTag("field"),
                        accessibleLabel = "Ім'я хоста",
                    )
                }
            }
            SampleRow("помилка замість підказки") {
                UFormField(label = "Пошта", error = "Адреса неповна: допишіть домен.") {
                    UInput(value = "bad@", onValueChange = {}, invalid = true, accessibleLabel = "Пошта")
                }
            }
        }
    }

    story(
        "Keyboard",
        play = Play {
            // A click on the name moves the focus into the field.
            click("name")
            expect("field").focused()
            press(Key.Tab)
            expect("after").focused()
            expect("name").text("Пошта")
        },
    ) {
        var mail by remember { mutableStateOf("") }
        Samples {
            UFormField(
                label = "Пошта",
                description = "Для відновлення доступу.",
                ui = UFormFieldUi(label = Modifier.testTag("name")),
            ) {
                UInput(
                    value = mail,
                    onValueChange = { mail = it },
                    modifier = Modifier.testTag("field"),
                    accessibleLabel = "Пошта",
                )
            }
            UInput(value = "", onValueChange = {}, modifier = Modifier.testTag("after"), accessibleLabel = "Після")
        }
    }

    story(
        "Accessibility",
        play = Play {
            expect("field").role("textbox").name("Ім'я хоста").description("Лише латиниця й дефіс.")
            expect("bad").role("textbox").state("invalid", "true").description("Адреса неповна: допишіть домен.")
        },
    ) {
        SampleRow("назва й підказка з'єднані з полем") {
            UFormField(label = "Ім'я хоста", description = "Лише латиниця й дефіс.") {
                UInput(value = "", onValueChange = {}, modifier = Modifier.testTag("field"))
            }
            UFormField(label = "Пошта", error = "Адреса неповна: допишіть домен.") {
                UInput(value = "bad@", onValueChange = {}, modifier = Modifier.testTag("bad"))
            }
        }
    }

    story("Examples") {
        Samples {
            SampleRow("стандарти з теми: UThemeConfig(UFormFieldDefaults(…))") {
                UTheme(config = UThemeConfig(UFormFieldDefaults(ui = UFormFieldUi(base = Modifier.padding(all = 8))))) {
                    UFormField(label = "Мова", description = "Для інтерфейсу.") {
                        UInput(value = "Українська", onValueChange = {}, accessibleLabel = "Мова")
                    }
                }
            }
        }
    }
}
