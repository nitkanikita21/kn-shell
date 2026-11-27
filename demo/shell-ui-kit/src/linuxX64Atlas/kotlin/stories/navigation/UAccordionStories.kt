package stories.navigation

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
import dev.knshell.compose.radius
import dev.knshell.compose.testTag
import dev.knshell.compose.width
import dev.knshell.icons.lucide.Lucide
import dev.knshell.inspect.InteractionState
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.components.navigation.UAccordion
import dev.knshell.uikit.components.navigation.UAccordionDefaults
import dev.knshell.uikit.components.navigation.UAccordionItem
import dev.knshell.uikit.components.navigation.UAccordionType
import dev.knshell.uikit.components.navigation.UAccordionUi
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import stories.support.SampleRow
import stories.support.Samples

private val sections = listOf(
    UAccordionItem("alerts", "Сповіщення", testTag = "alerts"),
    UAccordionItem("sound", "Звук", icon = Lucide.Volume2, testTag = "sound"),
    UAccordionItem("experimental", "Експериментальне", disabled = true, testTag = "experimental"),
)

/** Sections that keep their own open state, starting with [initial] open. */
@Composable
private fun SampleAccordion(
    type: UAccordionType = UAccordionType.Single,
    initial: Set<String> = emptySet(),
    tagged: Boolean = true,
) {
    var expanded by remember { mutableStateOf(initial) }
    UAccordion(
        items = if (tagged) sections else sections.map { it.copy(testTag = null) },
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.width(420),
        type = type,
        ui = if (tagged) UAccordionUi(panel = Modifier.testTag("panel")) else UAccordionUi(),
    ) { item ->
        Column(spacing = 8) {
            Label(
                "Вміст: ${item.label}",
                align = 0f,
                modifier = if (tagged) Modifier.testTag("content-${item.id}") else Modifier,
            )
            UButton(
                "Дія для «${item.label}»",
                onClick = {},
                modifier = if (tagged) Modifier.testTag("in-${item.id}") else Modifier,
            )
        }
    }
}

val uAccordionStories = group("UAccordion", category = "Navigation") {
    docs(
        "Розділи, що розгортаються. Кожен заголовок — кнопка й зупинка Tab; стрілки, Home і End " +
            "переходять між заголовками, Enter і Space розгортають або згортають.",
    )

    val type by enum(UAccordionType.Single)

    render { v -> SampleAccordion(type = v[type]) }

    story("Playground")

    variants("Types") { axis(type) }

    story("States", states = listOf(InteractionState.Hover, InteractionState.Focus), stateTarget = "sound") {
        SampleAccordion(initial = setOf("alerts"))
    }

    story(
        "Keyboard",
        play = Play {
            focus("alerts")
            press(Key.Enter)
            expect("alerts").expanded(true)
            expect("content-alerts").text("Вміст: Сповіщення")
            // Tab goes into the open section, and back to its header.
            press(Key.Tab)
            expect("in-alerts").focused()
            press(Key.Tab, shift = true)
            expect("alerts").focused()
            press(Key.Down)
            // The disabled section is skipped.
            expect("sound").focused()
            press(Key.End)
            expect("sound").focused()
            press(Key.Home)
            expect("alerts").focused()
            press(Key.Enter)
            expect("alerts").expanded(false)
            expectAbsent("in-alerts")
        },
    ) {
        SampleAccordion()
    }

    story(
        "Multiple",
        play = Play {
            focus("alerts")
            press(Key.Enter)
            press(Key.Down)
            press(Key.Enter)
            expect("alerts").expanded(true)
            expect("sound").expanded(true)
        },
    ) {
        SampleAccordion(type = UAccordionType.Multiple)
    }

    story(
        "Accessibility",
        play = Play {
            expect("alerts").role("button").name("Сповіщення").expanded(false)
            expect("experimental").state("disabled", "true")
            click("alerts")
            expect("alerts").expanded(true)
            expect("panel").role("region").name("Сповіщення")
        },
    ) {
        SampleAccordion()
    }

    story("Examples") {
        Samples {
            SampleRow("стандарти з теми: UThemeConfig(UAccordionDefaults(…))") {
                UTheme(
                    config = UThemeConfig(
                        UAccordionDefaults(ui = UAccordionUi(header = Modifier.radius(6))),
                    ),
                ) {
                    SampleAccordion(initial = setOf("sound"), tagged = false)
                }
            }
        }
    }
}
