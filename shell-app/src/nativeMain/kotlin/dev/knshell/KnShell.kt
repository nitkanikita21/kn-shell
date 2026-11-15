@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell

import androidx.compose.runtime.Composable
import dev.knshell.compose.CenterBox
import dev.knshell.compose.Color
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.height
import dev.knshell.shell.BarAnchor
import dev.knshell.shell.BarLayer
import dev.knshell.AppShell
import dev.knshell.shell.BarWindowSpec
import dev.knshell.shell.displayWidth
import dev.knshell.shell.ExclusiveZone
import dev.knshell.shell.Margins
import dev.knshell.shell.Shell
import dev.knshell.shell.WindowSpec
import dev.knshell.ui.KnBar
import dev.knshell.ui.islands.CenterIsland
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Entry point of the shell.
 *
 * Modes:
 *  - `panel` (default) — a wlr-layer-shell bar at the top of the output;
 *  - `windowed` — the same content inside a regular window (development/testing).
 *
 * The library intentionally provides no bar, no modules and no theme: compose them from
 * `dev.knshell.compose` layout, the Modifier DSL, `dev.knshell.ui.primitives` utilities
 * and the reactive `Shell.services` flows.
 */
fun startKnShell() {
    when (getenv("KN_SHELL_MODE")?.toKString() ?: "panel") {
        "windowed" -> {
            println("[shell] windowed mode")
            Shell.run {
                window(
                    WindowSpec(
                        title = "KN Shell",
                        width = 640,
                        height = 120,
                        background = Color.hex(0x1E1E2E),
                    ),
                ) {
                    KnBar()
                }
            }
        }

        else -> {
            // Development convenience: overlay the bar on top of waybar (which reserves the
            // top 40 px through its exclusive zone, hence the negative margin).
            val overlayDev = getenv("KN_SHELL_BAR_LAYER")?.toKString() == "overlay"
            println("[shell] panel mode (layer-shell " + (if (overlayDev) "overlay over waybar" else "top bar") + ")")
            Shell.run {
                // 1) The bar surface: side islands only; it reserves the top edge.
                val bar = barWindow(
                    BarWindowSpec(
                        title = "My Bar",
                        height = AppShell.BAR_HEIGHT,
                        layer = if (overlayDev) BarLayer.Overlay else BarLayer.Top,
                        anchors = setOf(BarAnchor.Top, BarAnchor.Left, BarAnchor.Right),
                        margins = Margins(top = 0, left = 0, right = 0, bottom = 30),
                        exclusiveZone = if (overlayDev) {
                            ExclusiveZone.None
                        } else {
                            ExclusiveZone.Fixed(AppShell.BAR_HEIGHT)
                        },
                        namespace = "my-bar",
                    ),
                ) { KnBar() }
                AppShell.bar = bar

                // 2) Centre island: its own surface, exactly island-sized, anchored under the
                //    bar and centred by its left margin. It reserves no space, so growing it
                //    cannot move windows and needs no input-region tricks.
                val center = barWindow(
                    BarWindowSpec(
                        title = "KN Center",
                        width = -1, // natural: exactly the island width
                        height = -1, // natural: bar height until expanded
                        layer = if (overlayDev) BarLayer.Overlay else BarLayer.Top,
                        anchors = setOf(BarAnchor.Top, BarAnchor.Left),
                        margins = Margins(
                            // Compositors place a top-anchored surface below the reserved
                            // (exclusive) area, i.e. below our own bar; shift it back up so
                            // the island sits in the same row as the side islands.
                            top = -AppShell.BAR_HEIGHT,
                            left = ((displayWidth() - AppShell.ISLAND_WIDTH) / 2).coerceAtLeast(0),
                            right = 0,
                            bottom = 0,
                        ),
                        exclusiveZone = ExclusiveZone.None,
                        namespace = "kn-center",
                    ),
                ) { CenterIsland() }
                AppShell.center = center
            }
        }
    }
}
