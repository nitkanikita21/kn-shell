package dev.knshell.ui.islands

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.AppShell
import dev.knshell.compose.AnimatedBox
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.Row
import dev.knshell.compose.onClick
import dev.knshell.ui.BAR_FONT
import dev.knshell.ui.BAR_FONT_SIZE
import dev.knshell.ui.island
import dev.knshell.ui.modules.Clock
import dev.knshell.ui.modules.Notification
import dev.knshell.ui.modules.NotificationCenter
import dev.knshell.ui.modules.Workspaces
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlin.time.TimeSource
import platform.posix.getenv

/**
 * Centre island — its **own** layer-shell surface, exactly as wide as the island.
 *
 * There is no full-width bar hack: the surface is the island, so growing it downwards never
 * creates transparent click-blocking areas, and the side islands (separate bar surface) are
 * not affected at all. The island animates its own height via [AnimatedBox]; the window is
 * resized to fit (collapsed: bar height, expanded: [AppShell.EXPANDED_HEIGHT]).
 *
 * `KN_ISLAND_AUTO=1` opens it once after start; `KN_ISLAND_DEBUG=1` starts expanded.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
fun CenterIsland() {
    val debugOpen = remember { getenv("KN_ISLAND_DEBUG")?.toKString() == "1" }
    var isExpanded by remember { mutableStateOf(debugOpen) }

    LaunchedEffect(Unit) {
        val auto = getenv("KN_ISLAND_AUTO")?.toKString() == "1"
        if (auto) {
            delay(2000)
            isExpanded = true
        }
    }

    // Resize the island's surface *with* the animation: one jump at the end (or a delayed
    // shrink) leaves the old surface buffer visible as a lingering rectangle.
    LaunchedEffect(isExpanded) {
        val center = AppShell.center ?: return@LaunchedEffect
        val target = if (isExpanded) AppShell.EXPANDED_HEIGHT else AppShell.BAR_HEIGHT
        val from = center.height.coerceAtLeast(AppShell.BAR_HEIGHT)
        if (from == target) return@LaunchedEffect
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            val progress = (start.elapsedNow().inWholeMilliseconds.toFloat() / AppShell.EXPAND_MS)
                .coerceIn(0f, 1f)
            val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
            center.resize(height = (from + (target - from) * eased).toInt())
            if (progress >= 1f) break
            delay(16)
        }
        center.resize(height = target)
    }

    AnimatedBox(
        vertical = true,
        durationMs = AppShell.EXPAND_MS,
        mainSize = if (isExpanded) AppShell.EXPANDED_HEIGHT else AppShell.BAR_HEIGHT,
        crossSize = AppShell.ISLAND_WIDTH,
        modifier = island(bottomRight = 16, bottomLeft = 16)
            // Same typography as the bar surface (this island has its own window/composition).
            .fontFamily(BAR_FONT)
            .fontSize(BAR_FONT_SIZE)
            .onClick { isExpanded = !isExpanded },
    ) {
        Row(16) {
            Notification()
            Workspaces()
            Clock()
        }

        NotificationCenter(width = AppShell.ISLAND_WIDTH - 48)
    }
}
