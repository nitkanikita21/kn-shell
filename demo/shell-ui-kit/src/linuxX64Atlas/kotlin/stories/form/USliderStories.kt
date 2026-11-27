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
import dev.knshell.compose.width
import dev.knshell.inspect.InteractionState
import dev.knshell.uikit.components.form.USlider
import dev.knshell.uikit.components.form.USliderDefaults
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.theme.UThemeConfig
import dev.knshell.uikit.theme.UTheme
import stories.support.SampleRow
import stories.support.Samples

/**
 * A slider that keeps its own value, starting at [initial], with the value shown in a label. Only a
 * [tagged] slider carries test tags, which must be unique within a story.
 */
@Composable
private fun SampleSlider(
    initial: Float = 30f,
    color: UColor = UColor.Primary,
    size: USize = USize.Md,
    disabled: Boolean = false,
    tagged: Boolean = true,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    Samples {
        USlider(
            value = value,
            onValueChange = { value = it },
            accessibleLabel = "Гучність",
            modifier = Modifier.width(260).then(if (tagged) Modifier.testTag("slider") else Modifier),
            color = color,
            size = size,
            disabled = disabled,
            valueText = { "${it.toInt()} %" },
        )
        Label(
            "Значення: ${value.toInt()}",
            align = 0f,
            modifier = if (tagged) Modifier.testTag("readout") else Modifier,
        )
    }
}

val uSliderStories = group("USlider", category = "Form") {
    docs(
        "Число з діапазону. Стрілки крокують, Page Up і Page Down рухають на десятину діапазону, " +
            "Home і End ставлять мінімум і максимум.",
    )

    val value by float(30f, 0f..100f)
    val color by enum(UColor.Primary)
    val size by enum(USize.Md)
    val disabled by boolean(false)

    render { v ->
        SampleSlider(initial = v[value], color = v[color], size = v[size], disabled = v[disabled])
    }

    story("Playground")

    variants("Sizes") { axis(size) }

    variants("Colors") { axis(color) }

    story("States", states = listOf(InteractionState.Hover, InteractionState.Focus), stateTarget = "slider") {
        Samples {
            SampleRow("звичайний (ціль станів)") { SampleSlider() }
            SampleRow("success") { SampleSlider(initial = 85f, color = UColor.Success, tagged = false) }
            SampleRow("disabled") { SampleSlider(initial = 65f, disabled = true, tagged = false) }
        }
    }

    story(
        "Keyboard",
        play = Play {
            focus("slider")
            expect("slider").focused()
            press(Key.Right)
            expect("readout").text("Значення: 31")
            press(Key.PageUp)
            expect("readout").text("Значення: 41")
            press(Key.PageDown)
            expect("readout").text("Значення: 31")
            press(Key.Left)
            expect("readout").text("Значення: 30")
            press(Key.End)
            expect("readout").text("Значення: 100")
            press(Key.Home)
            expect("readout").text("Значення: 0")
            // At the end, an arrow goes no further.
            press(Key.Left)
            expect("readout").text("Значення: 0")
        },
    ) {
        SampleSlider()
    }

    story(
        "Accessibility",
        play = Play {
            expect("slider").role("slider").name("Гучність").state("valuenow", "30.0")
            expect("slider").state("valuemin", "0.0").state("valuemax", "100.0")
        },
    ) {
        SampleSlider()
    }

    story("Examples") {
        var level by remember { mutableStateOf(70f) }
        Samples {
            SampleRow("стандарти з теми: UThemeConfig(USliderDefaults(…))") {
                UTheme(config = UThemeConfig(USliderDefaults(color = UColor.Success, size = USize.Lg))) {
                    USlider(
                        value = level,
                        onValueChange = { level = it },
                        accessibleLabel = "Гучність",
                        modifier = Modifier.width(260),
                    )
                }
            }
        }
    }
}
