package stories.feedback

import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.compose.Modifier
import dev.knshell.compose.testTag
import dev.knshell.uikit.components.feedback.UProgress
import dev.knshell.uikit.components.feedback.UProgressDefaults
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import stories.support.SampleRow
import stories.support.Samples

val uProgressStories = group("UProgress", category = "Feedback") {
    docs("Ступінь виконання. Роль progressbar з мінімумом, максимумом і значенням; без значення він невизначений і зайнятий.")

    val value by float(0.5f, 0f..1f)
    val indeterminate by boolean(false)
    val color by enum(UColor.Primary)
    val size by enum(USize.Md)

    render { v ->
        UProgress(
            value = if (v[indeterminate]) null else v[value],
            accessibleLabel = "Завантаження оновлень",
            modifier = Modifier.testTag("target"),
            color = v[color],
            size = v[size],
        )
    }

    story("Playground")

    variants("Colors") { axis(color) }

    variants("Sizes") { axis(size) }

    story(
        "Accessibility",
        play = Play {
            expect("bar").role("progressbar").name("Завантаження оновлень")
            expect("bar").state("valuenow", "0.5").state("valuemax", "1.0")
            expect("busy").state("busy", "true")
        },
    ) {
        Samples {
            SampleRow("визначений і невизначений") {
                UProgress(0.5f, accessibleLabel = "Завантаження оновлень", modifier = Modifier.testTag("bar"))
                UProgress(null, accessibleLabel = "Зачекайте", modifier = Modifier.testTag("busy"))
            }
        }
    }

    story("Examples") {
        Samples {
            SampleRow("стандарти з теми: UThemeConfig(UProgressDefaults(…))") {
                UTheme(config = UThemeConfig(UProgressDefaults(color = UColor.Success, size = USize.Lg))) {
                    UProgress(0.8f, accessibleLabel = "Копіювання")
                }
            }
        }
    }
}
