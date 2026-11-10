@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.knshell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.animation.animateFloatAsState
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
import dev.knshell.compose.bold
import dev.knshell.compose.fillMaxWidth
import dev.knshell.compose.fontFamily
import dev.knshell.compose.height
import dev.knshell.compose.fontSize
import dev.knshell.compose.hoverBackground
import dev.knshell.compose.margin
import dev.knshell.compose.opacity
import dev.knshell.compose.padding
import dev.knshell.compose.rawStyle
import dev.knshell.compose.size
import dev.knshell.compose.textColor
import dev.knshell.compose.width
import dev.knshell.core.MediaState
import dev.knshell.core.SystemStats
import dev.knshell.core.WorkspaceInfo
import dev.knshell.services.ShellServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Bar state is hoisted outside the composition: the values are written by coroutines that
 * collect the Rust service flows, and keeping them in one process-global holder makes the
 * producer/consumer relationship explicit.
 */
internal object BarState {
    val volume = mutableStateOf(0)
    val media = mutableStateOf(MediaState("", "", false, "", false))
    val workspaces = mutableStateOf<List<WorkspaceInfo>>(emptyList())
    val stats = mutableStateOf(SystemStats(0, 0))
    val clock = mutableStateOf(currentClockParts())
}

/** Reusable "pill" used by all bar modules — styles are expressed as modifiers, not CSS. */
private fun Modifier.module(color: Color? = null, horizontalPadding: Int = 14): Modifier {
    var modifier = this
        .padding(horizontal = horizontalPadding, vertical = 5)
        .background(Catppuccin.Surface0, radius = 16, shadow = true, transitionMs = 200)
        .margin(horizontal = 5)
    if (color != null) modifier = modifier.textColor(color)
    return modifier
}

private fun Modifier.sliderTheme(): Modifier = this
    .rawStyle(" trough", "min-height: 6px; border-radius: 3px; background-color: #45475A;")
    .rawStyle(" highlight", "background-color: #A6E3A1; border-radius: 3px;")
    .rawStyle(" slider", "background-color: #CDD6F4; border: none; box-shadow: none;")

/**
 * Waybar-like top bar, laid out and styled entirely with modifiers:
 *
 *   start : music pill (equalizer) + updates
 *   center: workspace chips
 *   end   : network, volume, CPU, memory, clock, power
 *
 * Animations: equalizer springs, smoothed CPU/memory/volume numbers, spring-animated
 * workspace chip width, fading clock colon, GTK CSS transitions for hover states.
 */
@Composable
fun TopBar() {
    val volume by BarState.volume
    val media by BarState.media
    val workspaces by BarState.workspaces
    val stats by BarState.stats
    val clock by BarState.clock

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

    val cpu by animateFloatAsState(stats.cpuPercent.toFloat(), stiffness = 200f, dampingRatio = 0.9f)
    val memory by animateFloatAsState(stats.memPercent.toFloat(), stiffness = 200f, dampingRatio = 0.9f)
    val volumeAnimated by animateFloatAsState(volume.toFloat(), stiffness = 260f, dampingRatio = 0.85f)

    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            blink = !blink
        }
    }
    val colonOpacity by animateFloatAsState(if (blink) 1f else 0.2f, stiffness = 900f, dampingRatio = 1f)

    var eqTargets by remember { mutableStateOf(FloatArray(5) { 0.2f }) }
    LaunchedEffect(media.playing) {
        while (true) {
            delay(140)
            eqTargets = if (media.playing) {
                FloatArray(5) { 0.15f + Random.nextFloat() * 0.85f }
            } else {
                FloatArray(5) { 0.12f }
            }
        }
    }
    val eq0 by animateFloatAsState(eqTargets[0], stiffness = 320f, dampingRatio = 0.55f)
    val eq1 by animateFloatAsState(eqTargets[1], stiffness = 320f, dampingRatio = 0.55f)
    val eq2 by animateFloatAsState(eqTargets[2], stiffness = 320f, dampingRatio = 0.55f)
    val eq3 by animateFloatAsState(eqTargets[3], stiffness = 320f, dampingRatio = 0.55f)
    val eq4 by animateFloatAsState(eqTargets[4], stiffness = 320f, dampingRatio = 0.55f)
    val equalizer = floatArrayOf(eq0, eq1, eq2, eq3, eq4)

    val musicTitle = media.title.ifBlank {
        if (media.player.isNotBlank()) media.player else "nothing playing"
    }.take(42)

    CenterBox(
        modifier = Modifier.fillMaxWidth().fontFamily("JetBrainsMono Nerd Font").fontSize(13),
        start = {
            Row(spacing = 0) {
                Row(
                    spacing = 8,
                    modifier = Modifier
                        .padding(horizontal = 14, vertical = 5)
                        .background(
                            if (media.playing) Catppuccin.Blue else Catppuccin.Surface0,
                            radius = 16,
                            shadow = true,
                            transitionMs = 250,
                        )
                        .margin(horizontal = 5)
                        .textColor(if (media.playing) Color.White else Catppuccin.Pink),
                ) {
                    Label("\uF001", Modifier.fontSize(14))
                    Label(musicTitle, Modifier.fontSize(12))
                    Canvas(24, 14) { cr, width, height ->
                        if (media.playing) {
                            drawEqualizer(cr, width, height, equalizer, 1.0, 1.0, 1.0)
                        } else {
                            drawEqualizer(cr, width, height, equalizer, 0.96, 0.76, 0.9)
                        }
                    }
                }
                Label("\uF019 0", Modifier.module(Catppuccin.Sky).fontSize(12))
            }
        },
        center = {
            Row(
                spacing = 0,
                modifier = Modifier
                    .padding(horizontal = 8, vertical = 3)
                    .background(Catppuccin.Surface0, radius = 16, shadow = true)
                    .margin(horizontal = 5),
            ) {
                workspaces.forEach { workspace ->
                    key(workspace.name) {
                        WorkspaceChip(workspace)
                    }
                }
            }
        },
        end = {
            Row(spacing = 0) {
                Label("\uF0AC", Modifier.module(Catppuccin.Blue))

                Row(spacing = 6, modifier = Modifier.module(Catppuccin.Green)) {
                    Label("\uF028")
                    Slider(
                        value = volumeAnimated,
                        min = 0f,
                        max = 100f,
                        step = 5f,
                        modifier = Modifier.width(64).height(16).sliderTheme(),
                    ) { ShellServices.setVolume(it.toInt()) }
                    Label("$volume%", Modifier.fontSize(12))
                }

                Label("\uF2DB ${cpu.roundToInt()}%", Modifier.module(Catppuccin.Blue))
                Label("\uF538 ${memory.roundToInt()}%", Modifier.module(Catppuccin.Sapphire))

                Row(spacing = 0, modifier = Modifier.module(Catppuccin.Flamingo)) {
                    Label("\uF017")
                    Label(clock.first)
                    Label(clock.second, Modifier.opacity(colonOpacity))
                    Label(clock.third, Modifier.bold())
                }

                // Placeholder for the power menu; intentionally a no-op in the MVP.
                Button(
                    text = "\uF011",
                    modifier = Modifier.module(Catppuccin.Red).hoverBackground(Catppuccin.Surface1),
                ) { }
            }
        },
    )
}

@Composable
private fun WorkspaceChip(workspace: WorkspaceInfo) {
    val chipWidth by animateFloatAsState(
        targetValue = if (workspace.active) 28f else 16f,
        stiffness = 420f,
        dampingRatio = 0.65f,
    )

    val chipModifier = Modifier
        .size(
            width = chipWidth.roundToInt().coerceAtLeast(12),
            height = if (workspace.active) 22 else 16,
        )
        .background(
            if (workspace.active) Catppuccin.Mauve else Catppuccin.Surface3,
            radius = if (workspace.active) 7 else 100,
            transitionMs = 250,
        )
        .margin(horizontal = 4)
        .textColor(if (workspace.active) Catppuccin.Crust else Catppuccin.Mauve)
        .hoverBackground(Catppuccin.Accent)
        .let { if (workspace.active) it.bold().fontSize(10) else it }

    Button(
        text = if (workspace.active) workspace.name else "",
        modifier = chipModifier,
        onClick = { ShellServices.focusWorkspace(workspace.name) },
    )
}
