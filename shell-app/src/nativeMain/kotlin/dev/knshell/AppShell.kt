package dev.knshell

import dev.knshell.shell.WindowHandle

/** Handles of the shell's own windows, reachable from composables. */
object AppShell {
    /** Collapsed bar height. */
    const val BAR_HEIGHT = 40

    /** Height of the bar surface while the centre island is expanded. */
    const val EXPANDED_HEIGHT = 400

    /** Animation duration of the island growth; the surface shrinks after it. */
    const val EXPAND_MS = 240

    /** The bar window handle, set by [startKnShell]. */
    var bar: WindowHandle? = null
}
