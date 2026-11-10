@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell

import androidx.compose.runtime.Composable
import dev.knshell.compose.CenterBox
import dev.knshell.compose.Color
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.fillMaxWidth
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.height
import dev.knshell.shell.BarAnchor
import dev.knshell.shell.BarWindowSpec
import dev.knshell.shell.ExclusiveZone
import dev.knshell.shell.Margins
import dev.knshell.shell.Shell
import dev.knshell.shell.WindowSpec
import dev.knshell.ui.TestBar
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
                    TestBar()
                }
            }
        }

        else -> {
            println("[shell] panel mode (layer-shell top bar)")
            Shell.run {
                barWindow(
                    BarWindowSpec(
                        title = "My Bar",
                        height = 40,
                        anchors = setOf(BarAnchor.Top, BarAnchor.Left, BarAnchor.Right),
                        margins = Margins(top = 4, left = 10, right = 10, bottom = 4),
                        exclusiveZone = ExclusiveZone.Auto,
                        namespace = "my-bar",
                    ),
                ) { TestBar() }
            }
        }
    }
}
