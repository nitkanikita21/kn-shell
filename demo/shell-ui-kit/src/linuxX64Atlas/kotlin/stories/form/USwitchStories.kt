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
import dev.knshell.uikit.components.form.USwitch
import dev.knshell.uikit.components.form.USwitchDefaults
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import stories.support.SampleRow
import stories.support.Samples

/** A switch that keeps its own state. */
@Composable
private fun SampleSwitch(
    initial: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    color: UColor = UColor.Primary,
    size: USize = USize.Md,
    loading: Boolean = false,
    disabled: Boolean = false,
) {
    var on by remember(initial) { mutableStateOf(initial) }
    USwitch(
        checked = on,
        onCheckedChange = { on = it },
        modifier = modifier,
        label = label,
        color = color,
        size = size,
        loading = loading,
        disabled = disabled,
    )
}

val uSwitchStories = group("USwitch", category = "Form") {
    docs(
        "Перемикач, що діє одразу. Роль switch. Space, Enter і клік перемикають. Під час loading " +
            "він зайнятий і не реагує на введення.",
    )

    val label by string("Wi-Fi")
    val checked by boolean(true)
    val color by enum(UColor.Primary)
    val size by enum(USize.Md)
    val loading by boolean(false)
    val disabled by boolean(false)

    render { v ->
        SampleSwitch(
            initial = v[checked],
            label = v[label],
            modifier = Modifier.testTag("target"),
            color = v[color],
            size = v[size],
            loading = v[loading],
            disabled = v[disabled],
        )
    }

    story("Playground")

    variants("Colors") { axis(color) }

    variants("Sizes") { axis(size) }

    story("States", states = listOf(InteractionState.Hover, InteractionState.Focus), stateTarget = "field") {
        Samples {
            SampleRow("вимкнено, увімкнено, фокус") {
                SampleSwitch(initial = false, label = "Bluetooth", modifier = Modifier.testTag("field"))
                SampleSwitch(initial = true, label = "Wi-Fi")
            }
            SampleRow("кольори, loading, disabled") {
                SampleSwitch(initial = true, label = "Успіх", color = UColor.Success)
                SampleSwitch(initial = true, label = "Зайнято", loading = true)
                SampleSwitch(initial = false, label = "Недоступно", disabled = true)
            }
        }
    }

    story(
        "Keyboard",
        play = Play {
            focus("wifi")
            expect("wifi").focused().checked("false")
            press(Key.Space)
            expect("wifi").checked("true")
            expect("echo").text("Wi-Fi: увімкнено")
            press(Key.Enter)
            expect("wifi").checked("false")
            expect("echo").text("Wi-Fi: вимкнено")
            // Loading ignores the keys and the click.
            press(Key.Tab)
            expect("busy").focused().checked("true")
            press(Key.Space)
            expect("busy").checked("true")
        },
    ) {
        var wifi by remember { mutableStateOf(false) }
        Samples {
            SampleRow("Space і Enter перемикають") {
                USwitch(
                    checked = wifi,
                    onCheckedChange = { wifi = it },
                    modifier = Modifier.testTag("wifi"),
                    label = "Wi-Fi",
                )
                USwitch(
                    checked = true,
                    onCheckedChange = {},
                    modifier = Modifier.testTag("busy"),
                    label = "Оновлення",
                    loading = true,
                )
            }
            Label("Wi-Fi: ${if (wifi) "увімкнено" else "вимкнено"}", align = 0f, modifier = Modifier.testTag("echo"))
        }
    }

    story(
        "Accessibility",
        play = Play {
            expect("on").role("switch").name("Wi-Fi").checked("true")
            expect("off").checked("false")
            expect("busy").state("busy", "true")
            expect("gone").state("disabled", "true").enabled(false)
        },
    ) {
        Samples {
            SampleRow("назва, стан, зайнятість, disabled") {
                USwitch(checked = true, onCheckedChange = {}, modifier = Modifier.testTag("on"), label = "Wi-Fi")
                USwitch(checked = false, onCheckedChange = {}, modifier = Modifier.testTag("off"), label = "Bluetooth")
                USwitch(
                    checked = true,
                    onCheckedChange = {},
                    modifier = Modifier.testTag("busy"),
                    label = "Оновлення",
                    loading = true,
                )
                USwitch(
                    checked = false,
                    onCheckedChange = {},
                    modifier = Modifier.testTag("gone"),
                    label = "Недоступно",
                    disabled = true,
                )
            }
        }
    }

    story("Examples") {
        Samples {
            SampleRow("стандарти з теми: UThemeConfig(USwitchDefaults(…))") {
                UTheme(config = UThemeConfig(USwitchDefaults(color = UColor.Info, size = USize.Lg))) {
                    SampleSwitch(initial = true, label = "Великий перемикач")
                }
            }
        }
    }
}
