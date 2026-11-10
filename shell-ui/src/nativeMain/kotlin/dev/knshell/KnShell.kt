@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell

import dev.knshell.compose.runComposeWindow
import dev.knshell.demo.PhaseOneDemo
import dev.knshell.services.ShellServices
import dev.knshell.ui.CircleAnimationDemo
import dev.knshell.ui.KnPanel
import dev.knshell.ui.TOPBAR_CSS
import dev.knshell.ui.TopBar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Single entry point of the shell.
 *
 * The Compose composition is created *inside* this module so that no composable
 * lambda has to cross the shell-app <-> shell-ui klib boundary.
 *
 * Two run modes:
 *  - default      : a wlr-layer-shell bar (TOP layer, anchored bottom) — production mode.
 *  - `KN_SHELL_WINDOWED=1` : a normal GTK window — used for tests / development so that
 *    the running desktop session is not modified (no layer surface is created).
 */
fun startKnShell() {
    // Subscribe to the Rust services; callbacks are logged (and stored in Compose state).
    ShellServices.start()

    // Runtime mode selection:
    //   panel    (default) — layer-shell bar; production mode.
    //   windowed           — the same panel in a normal GTK window (safe for tests).
    //   demo               — Phase 4 expanding/collapsing circle window.
    //   phase1             — Phase 1 applier smoke test (static Column).
    val mode = getenv("KN_SHELL_MODE")?.toKString()
        ?: if (getenv("KN_SHELL_WINDOWED")?.toKString() == "1") "windowed" else "panel"

    when (mode) {
        "demo" -> {
            println("[shell-ui] animation demo window")
            runComposeWindow(
                title = "KN Shell — Circle Animation Demo",
                width = 480,
                height = 320,
                layerShell = false,
            ) {
                CircleAnimationDemo()
            }
        }

        "windowed" -> {
            println("[shell-ui] windowed top bar (no layer shell)")
            runComposeWindow(
                title = "KN Shell — Top Bar (windowed)",
                width = 1200,
                height = 56,
                css = TOPBAR_CSS,
                layerShell = false,
            ) {
                TopBar()
            }
        }

        "phase1" -> {
            runComposeWindow(
                title = "KN Shell — Phase 1",
                width = 480,
                height = 220,
                layerShell = false,
            ) {
                PhaseOneDemo()
            }
        }

        else -> {
            println("[shell-ui] layer-shell top bar mode")
            runComposeWindow(
                title = "KN Shell — Top Bar",
                width = 1280,
                height = 40,
                css = TOPBAR_CSS,
                layerShell = true,
                barAtTop = true,
            ) {
                TopBar()
            }
        }
    }
}
