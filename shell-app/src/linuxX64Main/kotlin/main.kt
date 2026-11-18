import dev.knshell.app.di.ProvideKoin
import dev.knshell.app.di.initKoin
import dev.knshell.app.theme.ThemeService
import dev.knshell.app.ui.helpers.injectDi
import dev.knshell.compose.Color
import dev.knshell.compose.runComposeWindow
import dev.knshell.ui.primitives.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import dev.knshell.app.ui.TestUi
import kotlinx.cinterop.ExperimentalForeignApi
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

    runComposeWindow(
        title = "KN Shell",
        width = 640,
        height = 120,
    ) {
        ProvideKoin {
            AppRoot()
        }
    }
}

@Composable
private fun AppRoot() {
    val themeService by injectDi<ThemeService>()
    LaunchedEffect(Unit) {
        // Reload the palette from the active wallpaper, then keep polling every
        // 3s so the theme stays in sync when the wallpaper changes. Cancelled
        // automatically when the composition leaves the tree.
        themeService.reloadTheme()
        while (true) {
            delay(3000)
            themeService.reloadTheme()
        }
    }
    TestUi()
}
