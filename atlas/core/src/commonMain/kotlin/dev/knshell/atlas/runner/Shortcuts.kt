// ---------------------------------------------------------------------------
// The keyboard of the atlas window. Shortcuts go by the physical key, so that they work whatever
// layout is switched on.
// ---------------------------------------------------------------------------

package dev.knshell.atlas.runner

import dev.knshell.compose.Key
import dev.knshell.compose.KeyEvent
import dev.knshell.compose.Modifier
import dev.knshell.compose.onKey

/** The modifier of the atlas window that carries its shortcuts. */
internal fun shortcuts(service: AtlasHostService): Modifier = Modifier
    // Chords come before whatever has the focus, a story included.
    .onKey(capture = true) { handleChord(service, it) }
    // A plain key is a shortcut only when nothing used it: a text field keeps what is typed.
    .onKey { handlePlainKey(service, it) }

private fun handleChord(service: AtlasHostService, event: KeyEvent): Boolean {
    if (!event.ctrl || event.alt || event.meta) return false
    when (event.latin) {
        'k' -> service.focusSearch()
        'b' -> service.view.sidebarHidden = !service.view.sidebarHidden
        'j' -> service.view.argsHidden = !service.view.argsHidden
        else -> return false
    }
    return true
}

private fun handlePlainKey(service: AtlasHostService, event: KeyEvent): Boolean {
    if (event.ctrl || event.alt || event.meta) return false
    val view = service.view
    when {
        event.latin == '/' -> service.focusSearch()
        event.latin == 'm' -> view.measure(!view.measuring)
        event.key == Key.Escape && view.measuring -> view.measure(false)
        else -> return false
    }
    return true
}
