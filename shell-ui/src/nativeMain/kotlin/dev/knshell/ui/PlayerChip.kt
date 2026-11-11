package dev.knshell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.Box
import dev.knshell.compose.Color
import dev.knshell.compose.Label
import dev.knshell.compose.Align
import dev.knshell.compose.Modifier
import dev.knshell.compose.align
import dev.knshell.compose.Row
import dev.knshell.compose.fontSize
import dev.knshell.compose.background
import dev.knshell.compose.gradientStops
import dev.knshell.compose.onClick
import dev.knshell.compose.padding
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.radius
import dev.knshell.compose.textColor
import dev.knshell.services.ShellServices
import kotlinx.coroutines.delay

// Lighter silver base with a white sheen band that fades out at both ends, so the loop
// restarts invisibly and the animation is seamless.
private val SheenBase = Color.hex(0x9A9AA2)   // slightly lighter grey

/** Fixed long title used while testing the layout (no dependency on the player). */
private const val TEST_TRACK =
    "SadSvit - Прощавай, це дуже довга назва треку яка точно не влізе в чип"

private fun smoothstep(edge: Float, value: Float): Float {
    val t = (value / edge).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Player chip: silver gradient with a rotating sheen and a black track title.
 *
 * Sizes itself: the width follows the text plus padding, the height fills the parent row
 * (`.align(v = Align.Fill)`), so there is nothing to hard-code. Clicking toggles play/pause.
 */
@Composable
fun PlayerChip(
    title: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val media by ShellServices.media.collectAsState()
    // Constant text on purpose: the layout must clamp it, independent of player metadata.
    val track = title ?: TEST_TRACK

    // Phase is read in composition, so each step re-emits the CSS gradient with the
    // highlight band at a new position. The band fades out near both ends of the cycle,
    // which makes the loop seamless instead of jumping edge to edge.
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(33)
            phase = (phase + 0.006f) % 1f
        }
    }

    val fade = smoothstep(0.10f, minOf(phase, 1f - phase))
    val band = Color(255, 255, 255, alpha = 0.85f * fade)
    val halo = Color(255, 255, 255, alpha = 0.30f * fade)
    val sheenStops = arrayOf(
        Color.Transparent to 0f,
        halo to phase - 0.14f,
        band to phase,
        halo to phase + 0.14f,
        Color.Transparent to 1f,
    )

    Row(
        modifier = Modifier
            .align(v = Align.Fill)
            .align(v = Align.Center)
            .radius(8)
            .background(SheenBase)
            .gradientStops(*sheenStops, angleDeg = 100f)
            .pointerCursor()
            .onClick { onClick?.invoke() ?: ShellServices.togglePlayPause() }
            .align(v = Align.Center)
            .padding(horizontal = 14, vertical = 2),
    ) {
        CavaEqualizer(
            width = 80,
            height = 20,
            bars = 6,
            color = Grayscale.Surface,
        )
        Label(
            text = "${media.artist ?: ""} - $track",
            modifier = Modifier.fontSize(12).textColor(Grayscale.Surface),
            align = 0f,
            ellipsize = true,
            maxWidthChars = 26,
        )
    }
}
