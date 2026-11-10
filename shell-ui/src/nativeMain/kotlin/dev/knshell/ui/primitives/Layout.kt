@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui.primitives

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.knshell.compose.Box
import dev.knshell.compose.Button
import dev.knshell.compose.CenterBox
import dev.knshell.compose.Color
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.fillMaxWidth
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.height
import dev.knshell.compose.size
import dev.knshell.compose.textColor
import dev.knshell.core.MediaState
import dev.knshell.core.SystemStats
import dev.knshell.core.WorkspaceInfo
import dev.knshell.services.ShellServices
import dev.knshell.ui.currentClockParts
import dev.knshell.ui.style.BarTheme
import dev.knshell.ui.style.pill
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reactive state of the bar, fed from the Rust services.
 *
 * [Bar] subscribes to every service flow once and writes the snapshots here, so custom
 * modules only read `BarState.workspaces.value` / `.media` / `.volume` / `.stats` /
 * `.clock`. Marked as Compose state, so reads inside a composable are tracked.
 */
object BarState {
    val volume = mutableStateOf(0)
    val media = mutableStateOf(MediaState("", "", false, "", false))
    val workspaces = mutableStateOf<List<WorkspaceInfo>>(emptyList())
    val stats = mutableStateOf(SystemStats(0, 0))
    val clock = mutableStateOf(currentClockParts())
}

/**
 * Three-region bar layout (start / center / end) with the theme font applied.
 *
 * Slots are wrapped in rows, so a slot may emit several children. The composition
 * subscribes to the shell services and keeps [BarState] up to date.
 */
@Composable
fun Bar(
    theme: BarTheme = BarTheme.Default,
    start: @Composable () -> Unit,
    center: @Composable () -> Unit,
    end: @Composable () -> Unit,
) {
    LaunchedEffect(Unit) {
        launch { ShellServices.volume.collect { BarState.volume.value = it } }
        launch { ShellServices.media.collect { BarState.media.value = it } }
        launch { ShellServices.workspaces.collect { BarState.workspaces.value = it } }
        launch { ShellServices.system.collect { BarState.stats.value = it } }
        launch {
            while (true) {
                delay(1_000)
                BarState.clock.value = currentClockParts()
            }
        }
    }

    CenterBox(
        modifier = Modifier
            .fillMaxWidth()
            .height(theme.barHeight)
            .fontFamily(theme.fontFamily)
            .fontSize(theme.fontSize),
        start = { Row(spacing = 0) { start() } },
        center = { Row(spacing = 0) { center() } },
        end = { Row(spacing = 0) { end() } },
    )
}

/**
 * Generic rounded container ("pill") with themed padding, background, margin and shadow.
 *
 * Pass [onClick] to make it interactive without changing its look.
 */
@Composable
fun Pill(
    theme: BarTheme = BarTheme.Default,
    color: Color? = null,
    spacing: Int = 0,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val modifier = Modifier.pill(theme, color = color ?: theme.surface)
    if (onClick == null) {
        Row(spacing = spacing, modifier = modifier) { content() }
    } else {
        Button(modifier = modifier, onClick = onClick) {
            Row(spacing = spacing) { content() }
        }
    }
}

/** An empty box used as spacing. Prefer the [Space] helper. */
@Composable
fun Spacer(modifier: Modifier = Modifier) {
    Box(modifier)
}

/** Horizontal/vertical spacing: `Space(8)`, `Space(width = 6, height = 4)`. */
@Composable
fun Space(width: Int = 0, height: Int = 0) {
    Box(Modifier.size(width = width, height = height))
}

/** Horizontal spacer: `HSpace(8)`. */
@Composable
fun HSpace(width: Int) = Space(width = width, height = 0)

/** Vertical spacer: `VSpace(6)`. */
@Composable
fun VSpace(height: Int) = Space(width = 0, height = height)

/** A Nerd Font glyph rendered at [size] pixels; optionally coloured. */
@Composable
fun Icon(glyph: String, size: Int = 13, color: Color? = null) {
    val modifier = if (color != null) Modifier.fontSize(size).textColor(color) else Modifier.fontSize(size)
    Label(glyph, modifier)
}

/** Plain themed label, the workhorse for values inside a pill. */
@Composable
fun Text(
    text: String,
    theme: BarTheme = BarTheme.Default,
    color: Color = theme.text,
    size: Int = theme.fontSize,
) {
    Label(text, Modifier.fontSize(size).textColor(color))
}
