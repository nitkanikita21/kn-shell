import dev.knshell.core.hello
import dev.knshell.startKnShell
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix._IONBF
import platform.posix.setvbuf
import platform.posix.stdout

@OptIn(ExperimentalForeignApi::class)
fun main() {
    // Line/time-accurate logs: without this, stdout is fully buffered when piped and the
    // startup timings (and crash output) arrive in chunks.
    setvbuf(stdout, null, _IONBF, 0u)

    val greeting = hello()
    println("[shell-app] Rust says: $greeting")

    startKnShell()
}
