// ---------------------------------------------------------------------------
// The controls of the sidebar's head: flat buttons that show their frame only when pointed at or
// switched on, and a segmented switch made of them.
// ---------------------------------------------------------------------------

package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import dev.knshell.compose.Align
import dev.knshell.compose.Button
import dev.knshell.compose.Color
import dev.knshell.compose.Icon
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.hoverBackground
import dev.knshell.compose.padding
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.radius
import dev.knshell.compose.testTag
import dev.knshell.compose.textColor
import dev.knshell.icons.IconRef

private const val ICON_SIZE = 12

/** A button without a frame. [active] marks a switch that is on. */
@Composable
internal fun ToolButton(
    text: String,
    tag: String,
    icon: IconRef? = null,
    enabled: Boolean = true,
    active: Boolean = false,
    color: Color = AtlasTheme.fg,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> AtlasTheme.dim
        active -> AtlasTheme.accent
        else -> color
    }
    val shape = Modifier.radius(AtlasTheme.RADIUS).padding(horizontal = 8, vertical = 3).align(v = Align.Center)
    val fill = when {
        active -> Modifier.background(AtlasTheme.accent2, radius = AtlasTheme.RADIUS, transitionMs = 0)
        enabled -> Modifier.hoverBackground(AtlasTheme.panel2).pointerCursor()
        else -> Modifier
    }
    Button(modifier = shape.then(fill).testTag(tag), onClick = { if (enabled) onClick() }) {
        Row(spacing = 6, modifier = Modifier.align(Align.Center)) {
            icon?.let { Icon(it, Modifier.align(v = Align.Center), size = ICON_SIZE, color = tint, transitionMs = 0) }
            if (text.isNotEmpty()) {
                Label(text, modifier = with(AtlasTheme) { Modifier.themedText().textColor(tint, transitionMs = 0) })
            }
        }
    }
}

/** One of a few [options], side by side in a well, the chosen one filled. */
@Composable
internal fun <T> Segmented(
    options: List<T>,
    selected: T?,
    title: (T) -> String,
    tag: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        spacing = 2,
        modifier = Modifier
            .background(AtlasTheme.input, radius = AtlasTheme.RADIUS, transitionMs = 0)
            .padding(all = 2)
            .align(v = Align.Center),
    ) {
        options.forEach { option ->
            key(tag(option)) {
                ToolButton(
                    title(option),
                    tag(option),
                    active = option == selected,
                    color = AtlasTheme.dim,
                ) { onSelect(option) }
            }
        }
    }
}
