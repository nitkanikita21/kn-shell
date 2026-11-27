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
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.components.form.URadioGroup
import dev.knshell.uikit.components.form.URadioGroupDefaults
import dev.knshell.uikit.components.form.URadioItem
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.UOrientation
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.UVariant
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import stories.support.SampleRow
import stories.support.Samples

private val positions = listOf(
    URadioItem("top", "Згори", description = "Бар над вікнами.", testTag = "top"),
    URadioItem("bottom", "Знизу", testTag = "bottom"),
    URadioItem("hidden", "Приховано", disabled = true, testTag = "hidden"),
)

private val untagged = positions.map { it.copy(testTag = null) }

/**
 * A group that keeps its own choice, starting at [initial], with the choice shown in a label. Only a
 * [tagged] group carries test tags, which must be unique within a story.
 */
@Composable
private fun SampleRadio(
    initial: String = "top",
    orientation: UOrientation = UOrientation.Vertical,
    color: UColor = UColor.Primary,
    size: USize = USize.Md,
    disabled: Boolean = false,
    legend: String = "Положення бару",
    tagged: Boolean = true,
) {
    var value by remember { mutableStateOf(initial) }
    val items = if (tagged) positions else untagged
    Samples {
        URadioGroup(
            items = items,
            value = value,
            onValueChange = { value = it },
            legend = legend,
            modifier = if (tagged) Modifier.testTag("group") else Modifier,
            orientation = orientation,
            color = color,
            size = size,
            disabled = disabled,
        )
        Label(
            "Вибрано: ${positions.first { it.value == value }.label}",
            align = 0f,
            modifier = if (tagged) Modifier.testTag("result") else Modifier,
        )
    }
}

val uRadioGroupStories = group("URadioGroup", category = "Form") {
    docs(
        "Один вибір із кількох видимих варіантів. Одна зупинка Tab; стрілки переходять і вибирають, " +
            "Space і клік вибирають. Вибраний пункт отримує фокус.",
    )

    val legend by string("Положення бару")
    val orientation by enum(UOrientation.Vertical)
    val color by enum(UColor.Primary)
    val size by enum(USize.Md)
    val disabled by boolean(false)

    render { v ->
        SampleRadio(
            legend = v[legend],
            orientation = v[orientation],
            color = v[color],
            size = v[size],
            disabled = v[disabled],
        )
    }

    story("Playground")

    variants("Sizes") { axis(size) }

    variants("Orientation") { axis(orientation) }

    story("States", states = listOf(InteractionState.Hover, InteractionState.Focus), stateTarget = "top") {
        Samples {
            SampleRow("вертикально, горизонтально, disabled") {
                SampleRadio()
                SampleRadio(orientation = UOrientation.Horizontal, initial = "bottom", tagged = false)
                SampleRadio(disabled = true, tagged = false)
            }
        }
    }

    story(
        "Keyboard",
        play = Play {
            focus("top")
            expect("top").focused().checked("true")
            press(Key.Down)
            expect("bottom").focused().checked("true")
            expect("result").text("Вибрано: Знизу")
            // The disabled item is skipped, and the ends meet.
            press(Key.Down)
            expect("top").focused().checked("true")
            press(Key.Up)
            expect("bottom").focused().checked("true")
            press(Key.Home)
            expect("top").focused().checked("true")
            press(Key.End)
            expect("bottom").focused().checked("true")
            press(Key.Space)
            expect("result").text("Вибрано: Знизу")
        },
    ) {
        SampleRadio()
    }

    story(
        "Accessibility",
        play = Play {
            expect("group").role("radiogroup").name("Положення бару")
            expect("top").role("radio").name("Згори").description("Бар над вікнами.").checked("true")
            expect("bottom").role("radio").name("Знизу").checked("false")
            expect("hidden").state("disabled", "true").checked("false")
        },
    ) {
        SampleRadio()
    }

    story("Examples") {
        var value by remember { mutableStateOf("top") }
        Samples {
            SampleRow("вибір ззовні") {
                UButton("Знизу", onClick = { value = "bottom" }, variant = UVariant.Outline)
            }
            URadioGroup(
                items = untagged,
                value = value,
                onValueChange = { value = it },
                legend = "Положення бару",
            )
            SampleRow("стандарти з теми: UThemeConfig(URadioGroupDefaults(…))") {
                UTheme(config = UThemeConfig(URadioGroupDefaults(color = UColor.Success))) {
                    URadioGroup(
                        items = untagged,
                        value = value,
                        onValueChange = { value = it },
                        legend = "Положення бару",
                    )
                }
            }
        }
    }
}
