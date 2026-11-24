import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.knshell.app.di.initKoin
import dev.knshell.app.theme.ThemeMode
import dev.knshell.app.theme.ThemeService
import dev.knshell.app.ui.bar.Workspaces
import dev.knshell.app.ui.bar.Island
import dev.knshell.app.ui.TestUi
import dev.knshell.app.ui.helpers.injectDi
import dev.knshell.compose.CenterBox
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.centerV
import dev.knshell.compose.fillWidth
import dev.knshell.compose.inputRegion
import dev.knshell.compose.radius
import dev.knshell.services.ShellService
import dev.knshell.shell.BarAnchor
import dev.knshell.shell.BarWindowSpec
import dev.knshell.shell.ExclusiveZone
import dev.knshell.shell.Margins
import dev.knshell.shell.Shell
import dev.knshell.shell.WindowSpec
import dev.knshell.ui.primitives.Spacer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import org.koin.mp.KoinPlatform
import platform.posix._IONBF
import platform.posix.setvbuf
import platform.posix.stdout

@OptIn(ExperimentalForeignApi::class)
fun main() {
    // Line/time-accurate logs: without this, stdout is fully buffered when piped and the
    // startup timings (and crash output) arrive in chunks.
    setvbuf(stdout, null, _IONBF, 0u)

    println("[shell-app] hello world")

    initKoin()
    KoinPlatform.getKoin().getAll<ShellService>().forEach(ShellService::start)

    // Opt-in theme playground; see the window declaration below.
    val demo = platform.posix.getenv("KN_SHELL_DEMO") != null

    // `Shell.run` declares the windows and then blocks in the GLib main loop. It is a
    // plain (non-suspend) function, so `main` must not be suspend — see AppRoot below for
    // where the suspend `ThemeService.reloadTheme()` is driven.
    Shell.run {
        // ONE bar surface, three islands. Three separate bar windows would each claim their own
        // exclusive zone, so the compositor stacks them — one under another instead of
        // left / center / right. `CenterBox` places the islands on one surface instead.
        barWindow(
            BarWindowSpec(
                width = -1,
                height = -1,
                anchors = setOf(BarAnchor.Top, BarAnchor.Left, BarAnchor.Right),
                margins = Margins(0, 0, 0, 4),
                exclusiveZone = ExclusiveZone.Auto
            )
        ) {
            // Exactly one surface drives the theme: three bars polling in parallel would
            // extract the wallpaper three times a tick.
            WallpaperThemeDriver()
            CenterBox(
                modifier = Modifier.fillWidth(),
                start = { Spacer() },
                center = {
                    Island(Modifier.inputRegion().radius(0, 0, null, null)) {
                        Row(4, modifier = Modifier.centerV()) {
                            Workspaces()
                        }
                    }
                },
                end = { Spacer() },
            )
        }
        if (demo) {
            window(WindowSpec(title = "KN Shell — theme", width = 900, height = 620)) {
                TestUi()
            }
        }
    }
}

/**
 * Keeps [ThemeService] fed from the active wallpaper.
 *
 * Two jobs in one loop: the first `reloadTheme` is the startup load (before it the tokens are
 * the achromatic default), and the 3s poll is what repaints the shell when the wallpaper
 * changes — `awww` has no change event we subscribe to, and reading a 64px thumbnail every
 * three seconds is cheaper than holding an IPC connection open just for this.
 *
 * Mounted from exactly one surface: `reloadTheme` is a full extraction, so letting every bar
 * run its own loop would triple the work for one palette.
 */
@Composable
private fun WallpaperThemeDriver() {
    val themeService by injectDi<ThemeService>()
    LaunchedEffect(themeService) {
        themeService.setMode(ThemeMode.Dark)
        while (true) {
            themeService.reloadTheme()
            delay(WALLPAPER_POLL_MS)
        }
    }
}

/** How often the active wallpaper is re-read (ms). */
private const val WALLPAPER_POLL_MS = 3_000L