import dev.knshell.core.hello
import dev.knshell.startKnShell

fun main() {
    val greeting = hello()
    println("[shell-app] Rust says: $greeting")

    startKnShell()
}
