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
import dev.knshell.compose.AnimatedWidth
import dev.knshell.services.Services
import dev.knshell.ui.primitives.Icon
import dev.knshell.ui.primitives.Space
import kotlinx.coroutines.delay

// Lighter silver base with a white sheen band that fades out at both ends, so the loop
// restarts invisibly and the animation is seamless.
private val SheenBase = Color.hex(0x9A9AA2)   // slightly lighter grey

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
    equalizerWidth: Int = 80,
    equalizerHeight: Int = 20,
    equalizerBars: Int = 6,
    equalizerColor: Color = Grayscale.Surface,
    titleColor: Color? = null,
) {
    val media by Services.media.state.collectAsState()
    val track = title ?: media.title.ifBlank { "Nothing playing" }

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

    // Animated sheen only while there is something to play; otherwise a static silver base.
    val sheen = if (media.playing) {
        Modifier.gradientStops(*sheenStops, angleDeg = 100f)
    } else {
        Modifier.background(Grayscale.Surface)
    }

    // AnimatedWidth (not widthBehavior): a Modifier can only enforce a minimum size, so
    // growth would jump; the container disables natural-size propagation, which makes the
    // animated width authoritative in both directions.
    AnimatedWidth(
        durationMs = 200,
        spacing = 10,
        modifier = Modifier
            .align(v = Align.Center)
            .radius(8)
            .background(SheenBase)
            .then(sheen)
            .pointerCursor()
            .onClick { onClick?.invoke() ?: Services.media.togglePlayPause() }
            .padding(horizontal = 14, vertical = 2),
    ) {
        if (media.playing) {
            CavaEqualizer(
                width = equalizerWidth,
                height = equalizerHeight,
                bars = equalizerBars,
                color = equalizerColor,
            )
        } else {
            Icon("\uF04C")
        }
        Label(
            text = "${media.artist ?: ""} - $track",
            modifier = Modifier
                .fontSize(12)
                .textColor(titleColor ?: if (media.playing) Grayscale.Surface else Grayscale.Foreground),
            align = 0f,
            ellipsize = true,
            maxWidthChars = 32,
        )
    }
}
