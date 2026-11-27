package stories.element

import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.size
import dev.knshell.compose.testTag
import dev.knshell.compose.width
import dev.knshell.uikit.components.element.USeparator
import dev.knshell.uikit.components.element.USeparatorDefaults
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.UOrientation
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import stories.support.SampleRow
import stories.support.Samples

val uSeparatorStories = group("USeparator", category = "Element") {
    docs("Лінія між блоками, горизонтальна чи вертикальна, з необов'язковим підписом. Не інтерактивна; повідомляє орієнтацію.")

    val label by string("або")
    val orientation by enum(UOrientation.Horizontal)
    val color by enum(UColor.Neutral)

    render { v ->
        Box(modifier = Modifier.size(width = 260, height = 60)) {
            USeparator(
                modifier = Modifier.testTag("target"),
                orientation = v[orientation],
                label = v[label].ifEmpty { null },
                color = v[color],
            )
        }
    }

    story("Playground")

    variants("Colors") { axis(color) }

    story(
        "Accessibility",
        play = Play {
            expect("plain").role("separator")
            expect("labelled").role("separator")
        },
    ) {
        SampleRow("роль separator, з підписом і без") {
            Column(spacing = 8, modifier = Modifier.width(260)) {
                Label("Вище", align = 0f)
                USeparator(modifier = Modifier.testTag("plain"))
                USeparator(modifier = Modifier.testTag("labelled"), label = "або")
                Label("Нижче", align = 0f)
            }
        }
    }

    story("Examples") {
        Samples {
            SampleRow("з підписом і кольором") {
                Column(spacing = 8, modifier = Modifier.width(260)) {
                    Label("Вхід через пошту", align = 0f)
                    USeparator(label = "або")
                    Label("Вхід через застосунок", align = 0f)
                    USeparator(color = UColor.Primary)
                }
            }
            SampleRow("стандарти з теми: UThemeConfig(USeparatorDefaults(…))") {
                UTheme(config = UThemeConfig(USeparatorDefaults(color = UColor.Error))) {
                    Column(spacing = 8, modifier = Modifier.width(260)) {
                        Label("Небезпечна зона", align = 0f)
                        USeparator()
                    }
                }
            }
        }
    }
}
