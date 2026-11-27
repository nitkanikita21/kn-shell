package stories.element

import dev.knshell.atlas.core.entry.MatrixView
import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.compose.Modifier
import dev.knshell.compose.testTag
import dev.knshell.icons.lucide.Lucide
import dev.knshell.uikit.components.element.UBadge
import dev.knshell.uikit.components.element.UBadgeDefaults
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.UVariant
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import stories.support.SampleRow
import stories.support.Samples

val uBadgeStories = group("UBadge", category = "Element") {
    docs("Коротка позначка статусу чи лічильник. Не інтерактивна; назва для скрінрідера — її текст.")

    val label by string("Нове")
    val color by enum(UColor.Primary)
    val variant by enum(UVariant.Soft)
    val size by enum(USize.Md)

    render { v ->
        UBadge(
            v[label],
            modifier = Modifier.testTag("target"),
            color = v[color],
            variant = v[variant],
            size = v[size],
        )
    }

    story("Playground")

    variants("Variants", view = MatrixView.Table) { axis(color); axis(variant) }

    variants("Sizes") { axis(size) }

    story(
        "Accessibility",
        play = Play {
            expect("new").name("Нове")
            expect("count").name("12")
        },
    ) {
        SampleRow("назва = текст позначки") {
            UBadge("Нове", modifier = Modifier.testTag("new"))
            UBadge("12", modifier = Modifier.testTag("count"), color = UColor.Error, variant = UVariant.Solid)
        }
    }

    story("Examples") {
        Samples {
            SampleRow("з іконкою") {
                UBadge("Онлайн", color = UColor.Success, leadingIcon = Lucide.Check)
                UBadge("Увага", color = UColor.Warning, variant = UVariant.Outline, leadingIcon = Lucide.Info)
            }
            SampleRow("стандарти з теми: UThemeConfig(UBadgeDefaults(…))") {
                UTheme(config = UThemeConfig(UBadgeDefaults(variant = UVariant.Solid))) {
                    UBadge("Нове")
                    UBadge("Важливо", color = UColor.Warning)
                }
            }
        }
    }
}
