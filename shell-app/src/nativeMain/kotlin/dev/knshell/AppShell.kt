package dev.knshell

import dev.knshell.shell.WindowHandle

/** Handles of the shell's own windows, reachable from composables. */
object AppShell {
    /** Collapsed bar height. */
    const val BAR_HEIGHT = 40

    /** Height of the centre island surface while expanded. */
    const val EXPANDED_HEIGHT = 400

    /** Animation duration of the island growth; the surface shrinks after it. */
    const val EXPAND_MS = 240

    /** Width of the centre island (also the width of its own layer surface). */
    const val ISLAND_WIDTH = 245

    /** The bar surface that hosts the left/right islands (reserves the top edge). */
    var bar: WindowHandle? = null

    /** The centre island's own layer surface. */
    var center: WindowHandle? = null
}
