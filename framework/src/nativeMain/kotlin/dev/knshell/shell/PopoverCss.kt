// Reset stylesheet for the popover surface: strips GTK's frame, shadow, padding and arrow,
// so the content composable can own the whole look.

package dev.knshell.shell

/** CSS class added to the popover widget so the reset rules below can target it. */
internal const val POPOVER_CLASS = "kn-popover"

/**
 * The popover decoration lives on GTK's `contents` node, not on the widget itself, so the
 * plain reset (which targets the widget) leaves the Adwaita frame, shadow and padding
 * behind. Transparent background + no border/shadow on all popover nodes makes the popover
 * a bare surface: the content composable owns the whole look.
 */
internal val POPOVER_CSS = """
popover.$POPOVER_CLASS {
    background: transparent;
    border: none;
    box-shadow: none;
    padding: 0;
    margin: 0;
    border-radius: 0;
}
popover.$POPOVER_CLASS > contents,
popover.$POPOVER_CLASS contents {
    background: transparent;
    border: none;
    box-shadow: none;
    padding: 0;
    margin: 0;
    border-radius: 0;
}
popover.$POPOVER_CLASS > arrow,
popover.$POPOVER_CLASS arrow {
    background: transparent;
    border: none;
    box-shadow: none;
    padding: 0;
    margin: 0;
}
""".trimIndent()
