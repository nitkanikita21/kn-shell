package stories.feedback

import dev.knshell.atlas.dsl.group
import dev.knshell.atlas.play.Play
import dev.knshell.compose.Column
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.testTag
import dev.knshell.compose.width
import dev.knshell.uikit.components.feedback.USkeleton
import stories.support.SampleRow
import stories.support.Samples

val uSkeletonStories = group("USkeleton", category = "Feedback") {
    docs("Місце під вміст, що ще завантажується. Пульсує, поки motion.animate увімкнено. Для скрінрідера прихований.")

    val fullWidth by boolean(true)
    val height by int(12, 4..48)

    render { v ->
        // A skeleton of full width takes the width of its place, here a column of 260.
        Column(modifier = Modifier.width(260)) {
            USkeleton(
                modifier = Modifier.testTag("target"),
                width = if (v[fullWidth]) null else 180,
                height = v[height],
            )
        }
    }

    story("Playground")

    story(
        "Accessibility",
        play = Play {
            expect("skeleton").state("hidden", "true")
        },
    ) {
        Samples {
            SampleRow("прихований від скрінрідера") {
                USkeleton(modifier = Modifier.testTag("skeleton"), width = 180)
            }
        }
    }

    story("Examples") {
        Samples {
            SampleRow("картка, що завантажується: аватар і два рядки") {
                Row(spacing = 12) {
                    USkeleton(width = 40, height = 40, radius = 99)
                    Column(spacing = 8) {
                        USkeleton(width = 180)
                        USkeleton(width = 120)
                    }
                }
            }
        }
    }
}
