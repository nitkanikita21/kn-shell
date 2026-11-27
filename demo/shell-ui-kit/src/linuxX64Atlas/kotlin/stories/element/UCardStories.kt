package stories.element

import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.semiBold
import dev.knshell.compose.testTag
import dev.knshell.compose.width
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.components.element.UCard
import dev.knshell.uikit.components.element.UCardDefaults
import dev.knshell.uikit.components.element.UCardVariant
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.UVariant
import dev.knshell.uikit.theme.UTheme
import dev.knshell.uikit.theme.UThemeConfig
import stories.support.SampleRow
import stories.support.Samples

val uCardStories = group("UCard", category = "Element") {
    docs(
        "Піднята поверхня для пов'язаного вмісту, з необов'язковими шапкою й підвалом. " +
            "Не інтерактивна; з назвою стає іменованою групою.",
    )

    val text by string("Підключено до «home-5G».")
    val variant by enum(UCardVariant.Outline)
    val named by boolean(false)

    render { v ->
        UCard(
            modifier = Modifier.width(260).testTag("target"),
            variant = v[variant],
            accessibleLabel = if (v[named]) "Мережа" else null,
        ) {
            Label(v[text], wrap = true, align = 0f)
        }
    }

    story("Playground")

    variants("Variants") { axis(variant) }

    story(
        "Accessibility",
        play = Play {
            expect("named").role("group").name("Мережа")
        },
    ) {
        SampleRow("з назвою — іменована група") {
            UCard(modifier = Modifier.width(260).testTag("named"), accessibleLabel = "Мережа") {
                Label("Підключено до «home-5G».", wrap = true, align = 0f)
            }
        }
    }

    story("Examples") {
        Samples {
            SampleRow("шапка, вміст і підвал") {
                UCard(
                    modifier = Modifier.width(260),
                    header = { Label("Мережа", align = 0f, modifier = Modifier.semiBold()) },
                    footer = { UButton("Налаштувати", onClick = {}, color = UColor.Neutral, variant = UVariant.Soft, size = USize.Sm) },
                ) {
                    Label("Підключено до «home-5G».", wrap = true, align = 0f)
                }
            }
            SampleRow("варіанти: outline, soft, subtle") {
                UCard(modifier = Modifier.width(160), variant = UCardVariant.Outline) { Label("Outline", wrap = true, align = 0f) }
                UCard(modifier = Modifier.width(160), variant = UCardVariant.Soft) { Label("Soft", wrap = true, align = 0f) }
                UCard(modifier = Modifier.width(160), variant = UCardVariant.Subtle) { Label("Subtle", wrap = true, align = 0f) }
            }
            SampleRow("стандарти з теми: UThemeConfig(UCardDefaults(…))") {
                UTheme(config = UThemeConfig(UCardDefaults(variant = UCardVariant.Subtle))) {
                    UCard(modifier = Modifier.width(160)) { Label("За замовчуванням", wrap = true, align = 0f) }
                }
            }
        }
    }
}
