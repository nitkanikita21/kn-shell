package dev.knshell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.knshell.compose.Box
import dev.knshell.compose.Color
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Overlay
import dev.knshell.compose.Row
import dev.knshell.compose.background
import dev.knshell.compose.fillMaxSize
import dev.knshell.compose.fontSize
import dev.knshell.compose.glow
import dev.knshell.compose.gradient
import dev.knshell.compose.onClick
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.radius
import dev.knshell.compose.size
import dev.knshell.compose.textColor
import dev.knshell.compose.width
import dev.knshell.services.ShellServices
import dev.knshell.ui.primitives.Icon

// Silver ramp: near-white -> grey; the white accent comes from the icon badge and glow.
private val SilverTop = Color.hex(0xF4F4F4)
private val SilverMid = Color.hex(0xD2D2D2)
private val SilverBottom = Color.hex(0x9C9C9C)
private val TrackText = Color.hex(0x111111)

/**
 * Player chip: grayscale gradient background with a white accent badge and black track
 * title. Clicking toggles play/pause; the title comes from the MPRIS service.
 *
 * ```kotlin
 * Row { PlayerChip(width = 260, height = 28) }
 * ```
 */
@Composable
fun PlayerChip(
    width: Int = 260,
    height: Int = 28,
    title: String? = null,
    playing: Boolean? = null,
    onClick: (() -> Unit)? = null,
) {
    val media by ShellServices.media.collectAsState()

    val track = title ?: media.title.ifBlank { "Nothing playing" }
    val isPlaying = playing ?: media.playing

    Overlay(
        modifier = Modifier
            .size(width, height)
            .radius(height / 2)
            .gradient(SilverTop, SilverMid, SilverBottom, angleDeg = 100f)
            .glow(Color.White, blur = 12, spread = 0)
            .pointerCursor()
            .onClick { onClick?.invoke() ?: ShellServices.togglePlayPause() },
    ) {
        Row(spacing = 8, modifier = Modifier.width(width - 24)) {
            // White accent badge with a dark glyph.
            Box(Modifier.size(height - 10, height - 10).radius((height - 10) / 2).background(Color.White)) {
                Overlay(Modifier.fillMaxSize()) {
                    Icon(
                        glyph = if (isPlaying) "\uF04C" else "\uF04B",
                        size = height - 16,
                        color = TrackText,
                    )
                }
            }

            Label(
                text = track,
                modifier = Modifier
                    .fontSize(12)
                    .textColor(TrackText)
                    .width(width - 76),
                ellipsize = true,
            )
        }
    }
}
