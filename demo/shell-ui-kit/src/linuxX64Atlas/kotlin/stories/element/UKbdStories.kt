package stories.element

import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.testTag
import dev.knshell.uikit.components.element.UKbd
import dev.knshell.uikit.components.element.UKbdDefaults
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import stories.support.SampleRow
import stories.support.Samples

val uKbdStories = group("UKbd", category = "Element") {
    docs("Клавіша або сполучення клавіш. Не інтерактивна; назва для скрінрідера — клавіші через «плюс».")

    val keys by string("Ctrl+K")
    val size by enum(USize.Md)

    render { v ->
        UKbd(v[keys].split("+"), modifier = Modifier.testTag("target"), size = v[size])
    }

    story("Playground")

    variants("Sizes") { axis(size) }

    story(
        "Accessibility",
        play = Play {
            expect("shortcut").name("Ctrl плюс K")
            expect("single").name("Esc")
        },
    ) {
        SampleRow("назва = клавіші через «плюс»") {
            UKbd(listOf("Ctrl", "K"), modifier = Modifier.testTag("shortcut"))
            UKbd(listOf("Esc"), modifier = Modifier.testTag("single"))
        }
    }

    story("Examples") {
        Samples {
            SampleRow("у тексті") {
                Label("Пошук", align = 0f)
                UKbd(listOf("Super"))
                UKbd(listOf("Space"))
            }
            SampleRow("стандарти з теми: UThemeConfig(UKbdDefaults(…))") {
                UTheme(config = UThemeConfig(UKbdDefaults(size = USize.Sm))) {
                    UKbd(listOf("Ctrl", "K"))
                }
            }
        }
    }
}
