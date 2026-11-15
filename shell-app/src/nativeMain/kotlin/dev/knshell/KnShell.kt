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
import dev.knshell.shell.BarWindowSpec
import dev.knshell.shell.ExclusiveZone
import dev.knshell.shell.Margins
import dev.knshell.shell.Shell
import dev.knshell.shell.WindowSpec
import dev.knshell.ui.KnBar
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
                val bar = barWindow(
                    BarWindowSpec(
                        title = "My Bar",
                        height = 40,
                        layer = if (overlayDev) BarLayer.Overlay else BarLayer.Top,
                        anchors = setOf(BarAnchor.Top, BarAnchor.Left, BarAnchor.Right),
                        margins = Margins(top = 0, left = 0, right = 0, bottom = 30),
                        exclusiveZone = if (overlayDev) ExclusiveZone.None else ExclusiveZone.Fixed(40),
                        namespace = "my-bar",
                    ),
                ) { KnBar() }
                AppShell.bar = bar
            }
        }
    }
}
