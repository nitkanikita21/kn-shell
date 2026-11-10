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
import dev.knshell.shell.Shell
import dev.knshell.shell.WindowSpec
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
                    ShellSurface()
                }
            }
        }

        else -> {
            println("[shell] panel mode (layer-shell top bar)")
            Shell.run {
                barWindow {
                    ShellSurface()
                }
            }
        }
    }
}

/**
 * Empty top-level surface: three regions, theme-less font, no modules.
 * Replace the slot lambdas with your own `Row { … }` content.
 */
@Composable
private fun ShellSurface() {
    CenterBox(
        modifier = Modifier
            .fillMaxWidth()
            .height(40)
            .fontFamily("JetBrainsMono Nerd Font")
            .fontSize(13),
        start = {},
        center = {},
        end = {},
    )
}
