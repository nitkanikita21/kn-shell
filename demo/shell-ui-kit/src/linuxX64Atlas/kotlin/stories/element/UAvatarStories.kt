package stories.element

import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.compose.Modifier
import dev.knshell.compose.testTag
import dev.knshell.icons.lucide.Lucide
import dev.knshell.uikit.components.element.UAvatar
import dev.knshell.uikit.components.element.UAvatarDefaults
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import stories.support.SampleRow
import stories.support.Samples

val uAvatarStories = group("UAvatar", category = "Element") {
    docs("Зображення людини чи застосунку. Без зображення показує іконку або ініціали. Не інтерактивна; назва — ім'я.")

    val name by string("Анна Білик")
    val size by enum(USize.Md)

    render { v ->
        UAvatar(v[name], modifier = Modifier.testTag("target"), size = v[size])
    }

    story("Playground")

    variants("Sizes") { axis(size) }

    story(
        "Accessibility",
        play = Play {
            expect("initials").role("img").name("Анна Білик")
            expect("icon").role("img").name("Гість")
        },
    ) {
        SampleRow("роль img, назва = ім'я") {
            UAvatar("Анна Білик", modifier = Modifier.testTag("initials"))
            UAvatar("Гість", modifier = Modifier.testTag("icon"), icon = Lucide.User)
        }
    }

    story("Examples") {
        Samples {
            SampleRow("ініціали: максимум дві літери") {
                UAvatar("Анна Білик")
                UAvatar("Олександр Петрович Коваленко")
            }
            SampleRow("іконка замість ініціалів") {
                UAvatar("Гість", icon = Lucide.User, size = USize.Lg)
            }
            SampleRow("стандарти з теми: UThemeConfig(UAvatarDefaults(…))") {
                UTheme(config = UThemeConfig(UAvatarDefaults(size = USize.Lg))) {
                    UAvatar("Анна Білик")
                }
            }
        }
    }
}
