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
import dev.knshell.uikit.components.form.UCheckbox
import dev.knshell.uikit.components.form.UCheckboxDefaults
import dev.knshell.uikit.components.form.UChecked
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import stories.support.SampleRow
import stories.support.Samples

/** A checkbox that keeps its own state. */
@Composable
private fun SampleCheckbox(
    initial: UChecked,
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    color: UColor = UColor.Primary,
    size: USize = USize.Md,
    disabled: Boolean = false,
) {
    var state by remember(initial) { mutableStateOf(initial) }
    UCheckbox(
        checked = state,
        onCheckedChange = { state = if (it) UChecked.On else UChecked.Off },
        modifier = modifier,
        label = label,
        description = description,
        color = color,
        size = size,
        disabled = disabled,
    )
}

val uCheckboxStories = group("UCheckbox", category = "Form") {
    docs(
        "Незалежний вибір так чи ні, з проміжним станом. Роль checkbox. Space перемикає (Mixed і Off " +
            "стають увімкненими), Enter не чіпає. Tab іде по кожному прапорцю окремо.",
    )

    val label by string("Дозволити сповіщення")
    val description by string("")
    val checked by enum(UChecked.On)
    val color by enum(UColor.Primary)
    val size by enum(USize.Md)
    val disabled by boolean(false)

    render { v ->
        UCheckbox(
            checked = v[checked],
            onCheckedChange = {},
            modifier = Modifier.testTag("target"),
            label = v[label],
            description = v[description].ifEmpty { null },
            color = v[color],
            size = v[size],
            disabled = v[disabled],
        )
    }

    story("Playground")

    variants("Colors") { axis(color) }

    variants("Sizes") { axis(size) }

    story("States", states = listOf(InteractionState.Hover, InteractionState.Focus), stateTarget = "field") {
        Samples {
            SampleRow("вимкнено, увімкнено, частково") {
                SampleCheckbox(UChecked.Off, "вимкнено", modifier = Modifier.testTag("field"))
                UCheckbox(checked = UChecked.On, onCheckedChange = {}, label = "увімкнено")
                UCheckbox(checked = UChecked.Mixed, onCheckedChange = {}, label = "частково")
            }
            SampleRow("з описом, disabled") {
                UCheckbox(
                    checked = UChecked.On,
                    onCheckedChange = {},
                    label = "Оновлення",
                    description = "Встановлювати вночі.",
                )
                UCheckbox(checked = UChecked.Off, onCheckedChange = {}, label = "Вимкнено", disabled = true)
            }
        }
    }

    story(
        "Keyboard",
        play = Play {
            focus("terms")
            expect("terms").focused().checked("false")
            press(Key.Space)
            expect("terms").checked("true")
            expect("echo").text("Згода: так")
            // Enter is the form's: it does not toggle a checkbox.
            press(Key.Enter)
            expect("terms").checked("true")
            press(Key.Tab)
            expect("mixed").focused().checked("mixed")
            // Mixed turns on.
            press(Key.Space)
            expect("mixed").checked("true")
            // A disabled checkbox is not a Tab stop: the focus goes past it.
            press(Key.Tab)
            expect("off").enabled(false)
            expect("after").focused()
        },
    ) {
        var terms by remember { mutableStateOf(UChecked.Off) }
        var mixed by remember { mutableStateOf(UChecked.Mixed) }
        Samples {
            SampleRow("Space перемикає, Enter ні") {
                UCheckbox(
                    checked = terms,
                    onCheckedChange = { terms = if (it) UChecked.On else UChecked.Off },
                    modifier = Modifier.testTag("terms"),
                    label = "Згода з умовами",
                )
                UCheckbox(
                    checked = mixed,
                    onCheckedChange = { mixed = if (it) UChecked.On else UChecked.Off },
                    modifier = Modifier.testTag("mixed"),
                    label = "Усі пункти",
                )
                UCheckbox(
                    checked = UChecked.Off,
                    onCheckedChange = {},
                    modifier = Modifier.testTag("off"),
                    label = "Недоступно",
                    disabled = true,
                )
                UCheckbox(
                    checked = UChecked.Off,
                    onCheckedChange = {},
                    modifier = Modifier.testTag("after"),
                    label = "Далі",
                )
            }
            Label("Згода: ${if (terms == UChecked.On) "так" else "ні"}", align = 0f, modifier = Modifier.testTag("echo"))
        }
    }

    story(
        "Accessibility",
        play = Play {
            expect("plain").role("checkbox").name("Дозволити сповіщення").checked("false")
            expect("on").checked("true")
            expect("mixed").checked("mixed")
            expect("described").description("Встановлювати вночі.")
            expect("off").state("disabled", "true").enabled(false)
        },
    ) {
        Samples {
            SampleRow("назва, стани, опис") {
                UCheckbox(
                    checked = UChecked.Off,
                    onCheckedChange = {},
                    modifier = Modifier.testTag("plain"),
                    label = "Дозволити сповіщення",
                )
                UCheckbox(checked = UChecked.On, onCheckedChange = {}, modifier = Modifier.testTag("on"), label = "Увімкнено")
                UCheckbox(
                    checked = UChecked.Mixed,
                    onCheckedChange = {},
                    modifier = Modifier.testTag("mixed"),
                    label = "Частково",
                )
                UCheckbox(
                    checked = UChecked.On,
                    onCheckedChange = {},
                    modifier = Modifier.testTag("described"),
                    label = "Оновлення",
                    description = "Встановлювати вночі.",
                )
                UCheckbox(
                    checked = UChecked.Off,
                    onCheckedChange = {},
                    modifier = Modifier.testTag("off"),
                    label = "Недоступно",
                    disabled = true,
                )
            }
        }
    }

    story("Examples") {
        Samples {
            SampleRow("стандарти з теми: UThemeConfig(UCheckboxDefaults(…))") {
                UTheme(config = UThemeConfig(UCheckboxDefaults(color = UColor.Success))) {
                    SampleCheckbox(UChecked.On, "Зелений прапорець")
                }
            }
        }
    }
}
