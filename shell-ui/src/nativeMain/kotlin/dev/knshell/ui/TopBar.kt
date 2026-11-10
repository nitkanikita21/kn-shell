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
import dev.knshell.compose.CenterBox
import dev.knshell.compose.Label
import dev.knshell.compose.Row
import dev.knshell.compose.Slider
import dev.knshell.core.MediaState
import dev.knshell.core.SystemStats
import dev.knshell.core.WorkspaceInfo
import dev.knshell.services.ShellServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Waybar-like top bar, rendered by native GTK widgets from Compose state.
 *
 * Layout mirrors the reference waybar configuration:
 *
 *   start : music pill (with live equalizer) + updates
 *   center: workspace chips (animated width, active chip highlighted)
 *   end   : network, volume slider, CPU, memory, clock (blinking colon), power
 *
 * Animations are driven by Compose Runtime:
 *  * the equalizer bars follow spring-animated random targets while music plays,
 *  * CPU/memory/volume numbers smoothly animate towards the measured values,
 *  * the workspace chip width springs between dot and chip,
 *  * the clock colon fades in/out every 500 ms,
 *  * GTK CSS transitions handle hover states of the pills.
 */
@Composable
fun TopBar() {
    var volume by remember { mutableStateOf(0) }
    var media by remember { mutableStateOf(MediaState("", "", false, "", false)) }
    var workspaces by remember { mutableStateOf<List<WorkspaceInfo>>(emptyList()) }
    var stats by remember { mutableStateOf(SystemStats(0, 0)) }
    var clock by remember { mutableStateOf(currentClockParts()) }

    LaunchedEffect(Unit) {
        launch { ShellServices.volume.collect { volume = it } }
        launch { ShellServices.media.collect { media = it } }
        launch { ShellServices.workspaces.collect { workspaces = it } }
        launch { ShellServices.system.collect { stats = it } }
        launch {
            while (true) {
                delay(1_000)
                clock = currentClockParts()
            }
        }
    }

    // Smooth number transitions for the monitoring modules.
    val cpu by animateFloatAsState(stats.cpuPercent.toFloat(), stiffness = 200f, dampingRatio = 0.9f)
    val memory by animateFloatAsState(stats.memPercent.toFloat(), stiffness = 200f, dampingRatio = 0.9f)
    val volumeAnimated by animateFloatAsState(volume.toFloat(), stiffness = 260f, dampingRatio = 0.85f)

    // Blinking ":" of the clock.
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            blink = !blink
        }
    }
    val colonOpacity by animateFloatAsState(if (blink) 1f else 0.2f, stiffness = 900f, dampingRatio = 1f)

    // Equalizer: five bars with spring-animated random heights while playing.
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
        when {
            media.player.isNotBlank() -> media.player
            else -> "nothing playing"
        }
    }.take(42)

    CenterBox(
        start = {
            Row(spacing = 0) {
                Row(
                    spacing = 8,
                    cssClass = "module music",
                    stateClass = if (media.playing) "playing" else "paused",
                ) {
                    Label("\uF001", cssClass = "music-icon")
                    Label(musicTitle, cssClass = "music-title")
                    Canvas(24, 14) { cr, width, height ->
                        drawEqualizer(cr, width, height, equalizer, 1.0, 1.0, 1.0)
                    }
                }
                Label("\uF019 0", cssClass = "module updates")
            }
        },
        center = {
            Row(spacing = 0, cssClass = "workspaces") {
                workspaces.forEach { workspace ->
                    key(workspace.name) {
                        WorkspaceChip(workspace)
                    }
                }
            }
        },
        end = {
            Row(spacing = 0) {
                Label("\uF0AC", cssClass = "module network")
                Row(spacing = 6, cssClass = "module volume") {
                    Label("\uF028", cssClass = "volume-icon")
                    Slider(
                        value = volumeAnimated,
                        min = 0f,
                        max = 100f,
                        step = 5f,
                        cssClass = "mini-slider",
                    ) { ShellServices.setVolume(it.toInt()) }
                    Label("${volume}%", cssClass = "volume-value")
                }
                Label("\uF2DB ${cpu.roundToInt()}%", cssClass = "module cpu")
                Label("\uF538 ${memory.roundToInt()}%", cssClass = "module memory")
                Row(spacing = 0, cssClass = "module clock") {
                    Label("\uF017", cssClass = "clock-icon")
                    Label(clock.first, cssClass = "clock-date")
                    Label(clock.second, cssClass = "clock-colon", opacity = colonOpacity)
                    Label(clock.third, cssClass = "clock-time")
                }
                Button("\uF011", cssClass = "module power") {
                    println("[bar] power button pressed (not implemented in the MVP)")
                }
            }
        },
    )
}

@Composable
private fun WorkspaceChip(workspace: WorkspaceInfo) {
    val width by animateFloatAsState(
        targetValue = if (workspace.active) 26f else 12f,
        stiffness = 420f,
        dampingRatio = 0.65f,
    )
    Button(
        text = workspace.name,
        cssClass = "ws",
        stateClass = if (workspace.active) "ws-active" else null,
        minWidth = width.roundToInt().coerceAtLeast(10),
        onClick = { ShellServices.focusWorkspace(workspace.name) },
    )
}

/** Catppuccin Mocha pills, closely following the reference waybar stylesheet. */
const val TOPBAR_CSS: String = """
window.kn-window {
    background-color: rgba(18, 18, 24, 0.85);
    font-family: "JetBrainsMono Nerd Font", "JetBrainsMono Nerd Font Mono", monospace;
    font-size: 13px;
}

window.kn-window.kn-bar {
    background-color: transparent;
}

.bar-root {
    padding: 0;
    margin: 0;
}

.module {
    background-color: rgba(22, 25, 37, 0.9);
    color: #cdd6f4;
    border-radius: 16px;
    padding: 5px 14px;
    margin: 0 5px;
    box-shadow: 2px 2px 5px 2px rgba(0, 0, 0, 0.12);
    transition: background-color 200ms ease, color 200ms ease, box-shadow 200ms ease;
}

.module:hover {
    background-color: rgba(40, 44, 66, 0.95);
    box-shadow: 2px 2px 8px 2px rgba(203, 166, 247, 0.25);
}

.music {
    color: #f5c2e7;
}

.music.playing {
    background-color: rgba(137, 180, 250, 0.92);
    color: #ffffff;
}

.music.paused {
    background-color: rgba(22, 25, 37, 0.9);
    color: #cba6f7;
}

.music-icon {
    color: inherit;
    font-size: 14px;
}

.music-title {
    color: inherit;
    font-size: 12px;
}

.updates {
    color: #89dceb;
}

.network {
    color: #89b4fa;
}

.volume {
    color: #a6e3a1;
}

.volume-value {
    color: #a6e3a1;
    font-size: 12px;
}

.cpu {
    color: #89b4fa;
}

.memory {
    color: #74c7ec;
}

.clock {
    color: #f2cdcd;
}

.clock-icon {
    color: inherit;
    font-size: 13px;
}

.clock-date {
    color: inherit;
}

.clock-colon {
    color: inherit;
}

.clock-time {
    color: inherit;
    font-weight: 700;
}

.power {
    background-color: rgba(22, 25, 37, 0.9);
    color: #f38ba8;
    border-radius: 16px;
    padding: 5px 14px;
    margin: 0 5px 0 5px;
    box-shadow: 2px 2px 5px 2px rgba(0, 0, 0, 0.12);
    transition: background-color 200ms ease, color 200ms ease;
}

.power:hover {
    background-color: rgba(243, 139, 168, 0.9);
    color: #ffffff;
}

.workspaces {
    background-color: rgba(22, 25, 37, 0.9);
    border-radius: 16px;
    padding: 3px 8px;
    margin: 0 5px;
    box-shadow: 2px 2px 5px 2px rgba(0, 0, 0, 0.12);
}

button.ws {
    background-color: #394161;
    background-image: none;
    color: #cba6f7;
    border: none;
    border-radius: 100px;
    min-width: 12px;
    min-height: 12px;
    padding: 0;
    margin: 0 4px;
    transition: background-color 250ms cubic-bezier(0.33, 1.0, 0.68, 1.0), border-radius 250ms ease;
}

button.ws label {
    font-size: 0px;
}

button.ws:hover {
    background-color: #cba6f7;
}

button.ws.ws-active {
    background-color: #cba6f7;
    border-radius: 7px;
}

button.ws.ws-active label {
    font-size: 10px;
    font-weight: 700;
    color: #11111b;
}

scale.mini-slider {
    min-width: 64px;
    background: transparent;
    border: none;
}

scale.mini-slider trough {
    min-height: 6px;
    border-radius: 3px;
    background-color: #45475a;
}

scale.mini-slider highlight {
    background-color: #a6e3a1;
    border-radius: 3px;
}

scale.mini-slider slider {
    background-color: #cdd6f4;
    border: none;
    box-shadow: none;
    padding: 0;
}
"""
