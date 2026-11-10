@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell

import androidx.compose.runtime.Composable
import dev.knshell.compose.Color
import dev.knshell.shell.Shell
import dev.knshell.shell.WindowSpec
import dev.knshell.ui.primitives.Bar
import dev.knshell.ui.style.BarTheme
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Entry point of the shell.
 *
 * Modes:
 *  - `panel` (default) — a wlr-layer-shell bar at the top of the output;
 *  - `windowed` — the same bar content inside a regular window (development/testing).
 *
 * The bar intentionally ships **without modules**: compose your own inside the [Bar]
 * slots using the primitives from `dev.knshell.ui.primitives`, the styling helpers from
 * `dev.knshell.ui.style`, the Modifier DSL and the reactive services (`Shell.services`).
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
                    ShellBar()
                }
            }
        }

        else -> {
            println("[shell] panel mode (layer-shell top bar)")
            Shell.run {
                barWindow {
                    ShellBar()
                }
            }
        }
    }
}

/** Empty bar skeleton — add your own modules to the slots. */
@Composable
private fun ShellBar() {
    Bar(
        theme = BarTheme.Mocha,
        start = {},
        center = {},
        end = {},
    )
}
