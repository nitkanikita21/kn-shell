import dev.knshell.compose.Color
import dev.knshell.compose.runComposeWindow
import dev.knshell.ui.primitives.Text
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

    runComposeWindow(
        title = "KN Shell",
        width = 640,
        height = 120,
    ) {
        Text(
            text = "Hello, KN Shell",
            size = 24,
            color = Color.hex(0xE8E8EE),
        )
    }
}
