@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.animation.animateFloatAsState
import dev.knshell.compose.Button
import dev.knshell.compose.Canvas
import dev.knshell.compose.Label
import dev.knshell.compose.Row
import dev.knshell.compose.Slider
import dev.knshell.services.ShellServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 5 — the assembled shell panel.
 *
 * A single layer-shell bar containing:
 *  * a live clock (updated every second from a coroutine),
 *  * a volume slider bound to the Rust `AudioService` (changing it calls `set_volume`),
 *  * the current MPRIS title and a play/pause button (`toggle_play_pause`),
 *  * the number of toplevel windows reported by the Rust `WindowService`,
 *  * a small pulsing circle drawn by a `GtkDrawingArea` from Compose state (the
 *    animation bridge in action, in miniature).
 */
@Composable
fun KnPanel() {
    var volume by remember { mutableStateOf(0) }
    var mediaTitle by remember { mutableStateOf("no media") }
    var playing by remember { mutableStateOf(false) }
    var windowCount by remember { mutableStateOf(0) }
    var time by remember { mutableStateOf(currentTimeHm()) }
    var pulse by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch {
            ShellServices.volume.collect { volume = it }
        }
        launch {
            ShellServices.media.collect { state ->
                mediaTitle = state.title.ifBlank { state.player.ifBlank { "no media" } }
                playing = state.playing
            }
        }
        launch {
            ShellServices.windows.collect { windows -> windowCount = windows.size }
        }
        launch {
            while (true) {
                delay(1_000)
                time = currentTimeHm()
            }
        }
        launch {
            while (true) {
                delay(900)
                pulse = !pulse
            }
        }
    }

    val pulseRadius by animateFloatAsState(
        targetValue = if (pulse) 8f else 4f,
        stiffness = 300f,
        dampingRatio = 0.5f,
    )
    // Read during composition so recomposition follows the animation frames.
    val radius = pulseRadius

    Row(spacing = 14) {
        Label(time)
        Label("VOL")
        Slider(value = volume.toFloat(), min = 0f, max = 100f, step = 5f) { value ->
            ShellServices.setVolume(value.toInt())
        }
        Label("$volume%")
        Label(mediaTitle)
        Button(if (playing) "PAUSE" else "PLAY") { ShellServices.togglePlayPause() }
        Label("$windowCount win")
        Canvas(24, 24) { cr, width, height ->
            drawCircle(cr, width, height, radius, 0.35, 0.75, 1.0)
        }
    }
}
