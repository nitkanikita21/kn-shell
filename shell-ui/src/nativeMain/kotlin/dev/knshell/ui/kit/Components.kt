@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.animation.animateFloatAsState
import dev.knshell.compose.Box
import dev.knshell.compose.Button
import dev.knshell.compose.Canvas
import dev.knshell.compose.Catppuccin
import dev.knshell.compose.CenterBox
import dev.knshell.compose.Color
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.Slider
import dev.knshell.compose.background
import dev.knshell.compose.fillMaxWidth
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.height
import dev.knshell.compose.hoverBackground
import dev.knshell.compose.margin
import dev.knshell.compose.opacity
import dev.knshell.compose.padding
import dev.knshell.compose.rawStyle
import dev.knshell.compose.textColor
import dev.knshell.compose.width
import dev.knshell.core.MediaState
import dev.knshell.core.SystemStats
import dev.knshell.core.WorkspaceInfo
import dev.knshell.services.ShellServices
import dev.knshell.ui.currentClockParts
import dev.knshell.ui.drawEqualizer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Bar state is hoisted outside the composition: the values are written by coroutines
 * that collect the Rust service flows, and keeping them in one process-global holder
 * makes the producer/consumer relationship explicit.
 */
internal object BarState {
    val volume = mutableStateOf(0)
    val media = mutableStateOf(MediaState("", "", false, "", false))
    val workspaces = mutableStateOf<List<WorkspaceInfo>>(emptyList())
    val stats = mutableStateOf(SystemStats(0, 0))
    val clock = mutableStateOf(currentClockParts())
}

/**
 * The bar container: a three-region layout (start / center / end) that subscribes to the
 * shell services and adopts the theme's font.
 *
 * The slot lambdas are wrapped in rows so that a slot may emit several pills.
 */
@Composable
fun Bar(
    theme: BarTheme = BarTheme.Mocha,
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
            .fontFamily(theme.fontFamily)
            .fontSize(theme.fontSize),
        start = { Row(spacing = 0) { start() } },
        center = { Row(spacing = 0) { center() } },
        end = { Row(spacing = 0) { end() } },
    )
}

/**
 * The signature bar pill: content laid out in a row inside the themed padding,
 * background, margin and shadow.
 *
 * [color] overrides the pill background (defaults to the theme surface). An [onClick]
 * turns the pill into a button without changing its look.
 */
@Composable
fun Pill(
    color: Color? = null,
    theme: BarTheme = BarTheme.Mocha,
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

/** An empty container whose only purpose is to take up space. */
@Composable
fun Spacer(modifier: Modifier = Modifier) {
    Box(modifier)
}

/** A Nerd Font glyph rendered at [size] pixels. */
@Composable
fun Icon(glyph: String, size: Int = 13) {
    Label(glyph, Modifier.fontSize(size))
}

/** Themed text; the workhorse for values inside a pill. */
@Composable
fun ValueText(
    text: String,
    theme: BarTheme = BarTheme.Mocha,
    color: Color = theme.text,
    size: Int = theme.fontSize,
    bold: Boolean = false,
) {
    Label(text, Modifier.pillText(theme, color = color, size = size, bold = bold))
}

/** Percentage value animated with the shell's spring implementation. */
@Composable
fun AnimatedPercent(
    target: Int,
    theme: BarTheme = BarTheme.Mocha,
    color: Color = theme.text,
    format: (Int) -> String = { it.toString() + "%" },
    size: Int = theme.fontSize,
    stiffness: Float = 200f,
    dampingRatio: Float = 0.9f,
) {
    val animated by animateFloatAsState(
        targetValue = target.toFloat(),
        stiffness = stiffness,
        dampingRatio = dampingRatio,
    )
    ValueText(format(animated.roundToInt()), theme, color, size)
}

/**
 * Mini equalizer. The bar heights are spring targets refreshed every 140 ms while
 * [playing]; when idle everything settles to a low baseline.
 */
@Composable
fun EqualizerBars(
    playing: Boolean,
    bars: Int = 5,
    width: Int = 24,
    height: Int = 14,
    activeColor: Color = Color.White,
    idleColor: Color = Color(245, 194, 230),
) {
    key(bars) {
        val barCount = bars.coerceAtLeast(1)
        var targets by remember { mutableStateOf(FloatArray(barCount) { 0.2f }) }
        LaunchedEffect(playing) {
            while (true) {
                delay(140)
                targets = if (playing) {
                    FloatArray(barCount) { 0.15f + Random.nextFloat() * 0.85f }
                } else {
                    FloatArray(barCount) { 0.12f }
                }
            }
        }

        val values = FloatArray(barCount)
        for (index in 0 until barCount) {
            val value by animateFloatAsState(
                targetValue = targets.getOrElse(index) { 0.2f },
                stiffness = 320f,
                dampingRatio = 0.55f,
            )
            values[index] = value
        }

        val color = if (playing) activeColor else idleColor
        Canvas(width, height) { cr, canvasWidth, canvasHeight ->
            drawEqualizer(
                cr,
                canvasWidth,
                canvasHeight,
                values,
                color.r / 255.0,
                color.g / 255.0,
                color.b / 255.0,
            )
        }
    }
}

/** Now-playing pill; clicking it toggles play/pause through the service layer. */
@Composable
fun MusicPill(theme: BarTheme = BarTheme.Mocha) {
    val media by BarState.media
    val playing = media.playing
    val musicTitle = media.title.ifBlank {
        if (media.player.isNotBlank()) media.player else "nothing playing"
    }.take(42)

    Pill(
        color = if (playing) theme.blue else theme.surface,
        theme = theme,
        onClick = { ShellServices.togglePlayPause() },
    ) {
        Row(
            spacing = 8,
            modifier = Modifier.textColor(if (playing) Color.White else theme.pink),
        ) {
            Icon("\uF001", size = 14)
            ValueText(
                musicTitle,
                theme,
                color = if (playing) Color.White else theme.pink,
                size = 12,
            )
            EqualizerBars(playing = playing)
        }
    }
}

/** Static updates counter. */
@Composable
fun UpdatesPill(theme: BarTheme = BarTheme.Mocha, count: Int = 0) {
    Pill(theme = theme) {
        Label("\uF019 $count", Modifier.pillText(theme, color = theme.sky, size = 12))
    }
}

/** Workspace chips; the active chip is wider and carries the workspace name. */
@Composable
fun WorkspaceStrip(theme: BarTheme = BarTheme.Mocha) {
    val workspaces by BarState.workspaces
    Row(
        spacing = 0,
        modifier = Modifier
            .padding(horizontal = 8, vertical = 3)
            .background(theme.surface, radius = theme.radius, shadow = true)
            .margin(horizontal = theme.gap),
    ) {
        workspaces.forEach { workspace ->
            key(workspace.name) {
                WorkspaceChip(workspace, theme)
            }
        }
    }
}

@Composable
private fun WorkspaceChip(workspace: WorkspaceInfo, theme: BarTheme) {
    val chipWidth by animateFloatAsState(
        targetValue = if (workspace.active) 28f else 16f,
        stiffness = 420f,
        dampingRatio = 0.65f,
    )

    Button(
        text = if (workspace.active) workspace.name else "",
        modifier = Modifier.chip(
            active = workspace.active,
            theme = theme,
            width = chipWidth.roundToInt().coerceAtLeast(12),
            height = if (workspace.active) 22 else 16,
        ),
        onClick = { ShellServices.focusWorkspace(workspace.name) },
    )
}

/** Volume slider (0-100 in steps of 5) plus an animated percentage. */
@Composable
fun VolumePill(theme: BarTheme = BarTheme.Mocha) {
    val volume by BarState.volume
    val volumeAnimated by animateFloatAsState(
        targetValue = volume.toFloat(),
        stiffness = 260f,
        dampingRatio = 0.85f,
    )

    Pill(theme = theme) {
        Row(spacing = 6, modifier = Modifier.textColor(theme.green)) {
            Icon("\uF028")
            Slider(
                value = volumeAnimated,
                min = 0f,
                max = 100f,
                step = 5f,
                modifier = Modifier.width(64).height(16).sliderTheme(),
            ) { ShellServices.setVolume(it.toInt()) }
            AnimatedPercent(volume, theme, theme.green, size = 12)
        }
    }
}

/** CPU / memory style module: icon + animated percentage in the module colour. */
@Composable
fun MetricPill(
    icon: String,
    value: Int,
    color: Color,
    theme: BarTheme = BarTheme.Mocha,
) {
    Pill(theme = theme) {
        Row(spacing = 4, modifier = Modifier.textColor(color)) {
            Icon(icon)
            AnimatedPercent(value, theme, color)
        }
    }
}

/** Clock: day/weekday, a blinking colon and the bold minutes. */
@Composable
fun ClockPill(theme: BarTheme = BarTheme.Mocha) {
    val clock by BarState.clock

    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            blink = !blink
        }
    }
    val colonOpacity by animateFloatAsState(
        targetValue = if (blink) 1f else 0.2f,
        stiffness = 900f,
        dampingRatio = 1f,
    )

    Pill(theme = theme) {
        Row(modifier = Modifier.textColor(theme.flamingo)) {
            Icon("\uF017")
            ValueText(clock.first, theme, theme.flamingo)
            Label(clock.second, Modifier.textColor(theme.flamingo).opacity(colonOpacity))
            ValueText(clock.third, theme, theme.flamingo, bold = true)
        }
    }
}

/** Power button; the callback is a no-op by default (power menu lands in a later phase). */
@Composable
fun PowerPill(theme: BarTheme = BarTheme.Mocha, onClick: () -> Unit = {}) {
    Button(
        text = "\uF011",
        modifier = Modifier
            .pill(theme, color = theme.surface)
            .textColor(theme.red)
            .hoverBackground(Catppuccin.Surface1),
        onClick = onClick,
    )
}

private fun Modifier.sliderTheme(): Modifier = this
    .rawStyle(" trough", "min-height: 6px; border-radius: 3px; background-color: #45475A;")
    .rawStyle(" highlight", "background-color: #A6E3A1; border-radius: 3px;")
    .rawStyle(" slider", "background-color: #CDD6F4; border: none; box-shadow: none;")
