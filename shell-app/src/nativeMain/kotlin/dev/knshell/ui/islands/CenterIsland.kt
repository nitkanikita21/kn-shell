package dev.knshell.ui.islands

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.AppShell
import dev.knshell.compose.Align
import dev.knshell.compose.AnimatedBox
import dev.knshell.compose.Bounds
import dev.knshell.compose.BoundsObserver
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.align
import dev.knshell.compose.onClick
import dev.knshell.shell.Rect
import dev.knshell.shell.displayWidth
import dev.knshell.ui.island
import dev.knshell.ui.modules.Clock
import dev.knshell.ui.modules.Notification
import dev.knshell.ui.modules.NotificationCenter
import dev.knshell.ui.modules.Workspaces
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import platform.posix.getenv

/**
 * Centre island that grows **itself** downwards.
 *
 * The island is an [AnimatedBox] with an explicit animated height: the bar row stays on top
 * while the notification centre inside the same box is revealed as the island grows. The bar
 * surface is resized to fit; everything outside the island in that surface is excluded from
 * pointer input, so clicks fall through to the applications below.
 *
 * `KN_ISLAND_DEBUG=1` starts expanded (development aid).
 */
@Composable
fun CenterIsland() {
    @OptIn(ExperimentalForeignApi::class)
    val debugOpen = remember { getenv("KN_ISLAND_DEBUG")?.toKString() == "1" }
    var isExpanded by remember { mutableStateOf(debugOpen) }
    var islandBounds by remember { mutableStateOf<Bounds?>(null) }

    LaunchedEffect(isExpanded) {
        val bar = AppShell.bar ?: return@LaunchedEffect
        if (isExpanded) {
            bar.resize(height = AppShell.EXPANDED_HEIGHT)
        } else {
            delay((AppShell.EXPAND_MS + 60).toLong())
            if (!isExpanded) {
                bar.setInputRegion(null)
                bar.resize(height = AppShell.BAR_HEIGHT)
            }
        }
    }

    LaunchedEffect(isExpanded, islandBounds) {
        if (!isExpanded) return@LaunchedEffect
        val bounds = islandBounds ?: return@LaunchedEffect
        AppShell.bar?.setInputRegion(
            listOf(
                // The bar row stays clickable across the whole width.
                Rect(0, 0, displayWidth(), AppShell.BAR_HEIGHT),
                // Only the island area of the expanded surface takes input; the rest is
                // transparent and must not block the interface below.
                Rect(bounds.x, 0, bounds.width, AppShell.EXPANDED_HEIGHT),
            ),
        )
    }

    AnimatedBox(
        vertical = true,
        durationMs = AppShell.EXPAND_MS,
        mainSize = if (isExpanded) AppShell.EXPANDED_HEIGHT else AppShell.BAR_HEIGHT,
        modifier = island(bottomRight = 16, bottomLeft = 16)
            .onClick { isExpanded = !isExpanded }
            .align(v = Align.Start),
    ) {
        Row(16) {
            Notification()
            Workspaces()
            Clock()
        }

        NotificationCenter(
            width = ((islandBounds?.width ?: 265) - 48).coerceAtLeast(120),
        )

        BoundsObserver { islandBounds = it }
    }
}
