package dev.knshell.ui

import androidx.compose.runtime.Composable
import dev.knshell.compose.Align
import dev.knshell.compose.CenterBox
import dev.knshell.compose.Modifier
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.padding
import dev.knshell.compose.radius
import dev.knshell.ui.islands.CenterIsland
import dev.knshell.ui.islands.LeftIsland
import dev.knshell.ui.islands.RightIsland

/** Font used by every module in the bar. */
internal const val BAR_FONT = "JetBrainsMono Nerd Font"

/** Bar font size in CSS pixels. */
internal const val BAR_FONT_SIZE = 12

/**
 * The bar itself: three islands around a `GtkCenterBox`.
 *
 * ```
 * ┌─────────────┬──────────────────────────┬─────────────┐
 * │ LeftIsland  │ CenterIsland             │ RightIsland │
 * │ media       │ notifications, workspaces│ media/views │
 * └─────────────┴──────────────────────────┴─────────────┘
 * ```
 *
 * Pure composition: no state lives here, every island owns its modules.
 */
@Composable
fun KnBar() {
    CenterBox(
        modifier = Modifier
            .align(Align.Fill)
            .fontFamily(BAR_FONT)
            .fontSize(BAR_FONT_SIZE),
        start = { LeftIsland() },
        center = { CenterIsland() },
        end = { RightIsland() },
    )
}

/**
 * Common island chrome: dark rounded surface with the bar's inner padding.
 * Corners are per-corner because the islands form one continuous strip.
 */
internal fun island(topLeft: Int = 0, topRight: Int = 0, bottomRight: Int = 0, bottomLeft: Int = 0): Modifier =
    Modifier
        .padding(horizontal = 24, vertical = 8)
        .background(Grayscale.Background)
        .radius(topLeft, topRight, bottomRight, bottomLeft)
