package stories.support

import androidx.compose.runtime.Composable
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.align
import dev.knshell.compose.size
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.theme.UTheme

/** Rows of samples, one under the other. */
@Composable
fun Samples(content: @Composable () -> Unit) {
    Column(spacing = 12) { content() }
}

/** A line of samples under its caption. */
@Composable
fun SampleRow(caption: String, content: @Composable () -> Unit) {
    Column(spacing = 4, modifier = Modifier.align(h = Align.Start)) {
        Label(
            caption,
            align = 0f,
            modifier = Modifier.uFont(size = 11).uTextColor(UTheme.tokens.colors.mutedForeground),
        )
        Row(spacing = 8, modifier = Modifier.align(h = Align.Start)) { content() }
    }
}

/**
 * Room of a fixed size for what floats over the story: dialogs and toasts cover the stage, which is
 * as large as the story, so a story with an overlay takes its space up front.
 */
@Composable
fun OverlayStage(width: Int = 520, height: Int = 320, content: @Composable () -> Unit) {
    Box(Modifier.size(width, height)) {
        Column(spacing = 8, modifier = Modifier.align(h = Align.Start, v = Align.Start)) { content() }
    }
}
