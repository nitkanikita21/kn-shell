package stories.overlays

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
import dev.knshell.icons.lucide.Lucide
import dev.knshell.inspect.InteractionState
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.components.overlays.UDropdownMenu
import dev.knshell.uikit.components.overlays.UDropdownMenuDefaults
import dev.knshell.uikit.components.overlays.UDropdownMenuUi
import dev.knshell.uikit.components.overlays.uDropdownMenuDefaults
import dev.knshell.uikit.components.overlays.UMenuItem
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USide
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.UVariant
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import stories.support.OverlayStage
import stories.support.SampleRow
import stories.support.Samples

/** A menu of a profile: actions, a checkbox item and a destructive one. The last choice is shown under it. */
@Composable
private fun SampleMenu(
    label: String = "Профіль",
    size: USize = USize.Md,
    side: USide? = null,
) {
    var result by remember { mutableStateOf("—") }
    var quiet by remember { mutableStateOf(false) }
    val items = listOf(
        listOf(
            UMenuItem("Мій профіль", onSelect = { result = "профіль" }, icon = Lucide.User, testTag = "profile"),
            UMenuItem(
                "Редагувати",
                onSelect = { result = "редагування" },
                icon = Lucide.Pencil,
                kbds = listOf("Ctrl", "E"),
                testTag = "edit",
            ),
            UMenuItem("Копіювати ID", onSelect = {}, icon = Lucide.Copy, disabled = true, testTag = "copy"),
        ),
        listOf(
            UMenuItem(
                "Не турбувати",
                onSelect = { quiet = !quiet },
                checked = quiet,
                testTag = "quiet",
            ),
        ),
        listOf(
            UMenuItem(
                "Вийти",
                onSelect = { result = "вихід" },
                icon = Lucide.LogOut,
                color = UColor.Error,
                testTag = "logout",
            ),
        ),
    )
    OverlayStage {
        UDropdownMenu(
            items = items,
            // Without an explicit side the theme's defaults decide.
            side = side ?: uDropdownMenuDefaults().side,
            size = size,
            ui = UDropdownMenuUi(surface = Modifier.testTag("menu")),
            trigger = { trigger ->
                UButton(
                    label,
                    onClick = trigger.onClick,
                    modifier = Modifier.testTag("trigger").then(trigger.modifier),
                    color = UColor.Neutral,
                    variant = UVariant.Outline,
                    trailingIcon = Lucide.ChevronDown,
                )
            },
        )
        Label("Вибір: $result", align = 0f, modifier = Modifier.testTag("result"))
    }
}

val uDropdownMenuStories = group("UDropdownMenu", category = "Overlays") {
    docs(
        "Список дій біля кнопки. Стрілки рухають між пунктами по колу, Home і End — на початок і кінець, " +
            "літери — до пункту за назвою. Enter або Space виконують пункт і закривають меню; Escape закриває, " +
            "Tab закриває й іде далі від кнопки.",
    )

    val label by string("Профіль")
    val side by enum(USide.Bottom)
    val size by enum(USize.Md)

    render { v -> SampleMenu(label = v[label], size = v[size], side = v[side]) }

    story("Playground")

    variants("Sizes") { axis(size) }

    story("States", states = listOf(InteractionState.Hover, InteractionState.Focus), stateTarget = "trigger") {
        Samples {
            SampleRow("кнопка меню у фокусі та при наведенні") {
                SampleMenu()
            }
        }
    }

    story(
        "Keyboard",
        play = Play {
            focus("trigger")
            press(Key.Down)
            expect("profile").focused()
            snapshot("open", "menu")
            press(Key.Down)
            expect("edit").focused()
            // The disabled item is skipped.
            press(Key.Down)
            expect("quiet").focused()
            press(Key.End)
            expect("logout").focused()
            // Around the end.
            press(Key.Down)
            expect("profile").focused()
            press(Key.Up)
            expect("logout").focused()
            press(Key.Home)
            expect("profile").focused()
            // Escape closes and gives the focus back to the trigger.
            press(Key.Escape)
            expectAbsent("menu")
            expect("trigger").focused()
            // Up on the trigger opens on the last item; Tab closes and moves on.
            press(Key.Up)
            expect("logout").focused()
            press(Key.Tab)
            expectAbsent("menu")
            // Enter on the trigger opens on the first item; Enter on it chooses and closes.
            focus("trigger")
            press(Key.Enter)
            expect("profile").focused()
            press(Key.Enter)
            expectAbsent("menu")
            expect("result").text("Вибір: профіль")
        },
    ) {
        SampleMenu()
    }

    story(
        "Accessibility",
        play = Play {
            expect("trigger").expanded(false)
            click("trigger")
            expect("trigger").expanded()
            expect("menu")
            expect("profile").role("menuitem").name("Мій профіль")
            expect("copy").enabled(false)
            expect("quiet").role("menuitemcheckbox").checked("false")
            expect("logout").role("menuitem").name("Вийти")
        },
    ) {
        SampleMenu()
    }

    variants("Sides") { axis(side) }

    story("Examples") {
        Samples {
            SampleRow("стандарти з теми: UThemeConfig(UDropdownMenuDefaults(…))") {
                UTheme(config = UThemeConfig(UDropdownMenuDefaults(side = USide.Top))) {
                    SampleMenu(label = "Зверху")
                }
            }
        }
    }
}
