package stories.navigation

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
import dev.knshell.icons.lucide.Lucide
import dev.knshell.inspect.InteractionState
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.components.navigation.UActivation
import dev.knshell.uikit.components.navigation.UTabItem
import dev.knshell.uikit.components.navigation.UTabs
import dev.knshell.uikit.components.navigation.UTabsVariant
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.UOrientation
import dev.knshell.uikit.core.USize
import stories.support.SampleRow
import stories.support.Samples

private val items = listOf(
    UTabItem("general", "Загальні", testTag = "general"),
    UTabItem("sound", "Звук", icon = Lucide.Volume2, testTag = "sound"),
    UTabItem("network", "Мережа", disabled = true, testTag = "network"),
    UTabItem("about", "Про систему", testTag = "about"),
)

/** Tabs that keep their own selection, starting at the first. */
@androidx.compose.runtime.Composable
private fun SampleTabs(
    activation: UActivation = UActivation.Automatic,
    orientation: UOrientation = UOrientation.Horizontal,
    variant: UTabsVariant = UTabsVariant.Pill,
    color: UColor = UColor.Primary,
    size: USize = USize.Md,
) {
    var selected by remember { mutableStateOf("general") }
    UTabs(
        items = items,
        selected = selected,
        onSelect = { selected = it },
        modifier = Modifier.width(420),
        orientation = orientation,
        activation = activation,
        color = color,
        variant = variant,
        size = size,
        accessibleLabel = "Налаштування",
        ui = dev.knshell.uikit.components.navigation.UTabsUi(panel = Modifier.testTag("panel")),
    ) { item ->
        Label("Розділ «${item.label}»", align = 0f, modifier = Modifier.testTag("content"))
    }
}

val uTabsStories = group("UTabs", category = "Navigation") {
    docs("Вкладки з панеллю. Одна зупинка Tab; стрілки, Home і End переходять між вкладками.")

    val color by enum(UColor.Primary)
    val variant by enum(UTabsVariant.Pill)
    val orientation by enum(UOrientation.Horizontal)
    val activation by enum(UActivation.Automatic)
    val size by enum(USize.Md)

    render { v ->
        SampleTabs(
            color = v[color],
            variant = v[variant],
            orientation = v[orientation],
            activation = v[activation],
            size = v[size],
        )
    }

    story("Playground")

    variants("Variants") { axis(variant); axis(orientation) }

    variants("Sizes") { axis(size) }

    story("States", states = listOf(InteractionState.Hover, InteractionState.Focus), stateTarget = "sound") {
        Samples {
            SampleRow("вибрана, звичайна, disabled") { SampleTabs() }
            SampleRow("Line, color = Success") { SampleTabs(variant = UTabsVariant.Line, color = UColor.Success) }
        }
    }

    story(
        "Keyboard",
        play = Play {
            focus("general")
            press(Key.Right)
            expect("sound").focused().selected()
            expect("content").text("Розділ «Звук»")
            // The disabled tab is skipped, and the ends meet.
            press(Key.Right)
            expect("about").focused().selected()
            press(Key.Right)
            expect("general").focused().selected()
            press(Key.Left)
            expect("about").selected()
            press(Key.Home)
            expect("general").selected()
            press(Key.End)
            expect("about").selected()
            // One Tab stop: from the list to the panel, and back to the selected tab.
            press(Key.Tab)
            expect("panel").focused()
            press(Key.Tab, shift = true)
            expect("about").focused()
            snapshot("keyboard")
        },
    ) {
        SampleTabs()
    }

    story(
        "Manual activation",
        play = Play {
            focus("general")
            press(Key.Right)
            expect("sound").focused().selected(false)
            expect("general").selected()
            press(Key.Enter)
            expect("sound").selected()
            press(Key.Right)
            press(Key.Space)
            expect("about").selected()
        },
    ) {
        SampleTabs(activation = UActivation.Manual)
    }

    story(
        "Accessibility",
        play = Play {
            expect("general").role("tab").name("Загальні").selected()
            expect("sound").selected(false)
            expect("network").state("disabled", "true")
            expect("panel").role("tabpanel").name("Загальні")
        },
    ) {
        SampleTabs()
    }

    story("Examples") {
        var selected by remember { mutableStateOf("general") }
        Samples {
            SampleRow("вибір ззовні") {
                UButton("Показати «Про систему»", onClick = { selected = "about" })
            }
            UTabs(items, selected, onSelect = { selected = it }, orientation = UOrientation.Vertical) { item ->
                Label(item.label, align = 0f)
            }
        }
    }
}
