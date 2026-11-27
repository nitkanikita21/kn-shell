// ---------------------------------------------------------------------------
// Roving focus: a composite widget (tabs, radio group, menu) is one Tab stop, and the arrow keys
// move between its items.
// ---------------------------------------------------------------------------

package dev.knshell.uikit.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.FocusRequester
import dev.knshell.compose.Key
import dev.knshell.compose.Modifier
import dev.knshell.compose.focusRequester
import dev.knshell.compose.focusable
import dev.knshell.compose.onFocusChange
import dev.knshell.compose.onKey
import dev.knshell.uikit.core.UOrientation

/** Which item of one composite widget has the focus. */
class URovingState<K : Any> internal constructor() {
    private val requesters = mutableMapOf<K, FocusRequester>()

    /** The focused item, or `null` while the focus is elsewhere. */
    var focused: K? by mutableStateOf(null)
        private set

    internal fun requester(key: K): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** Moves the focus to [key]. */
    fun focus(key: K) {
        focused = key
        requester(key).requestFocus()
    }

    internal fun focusChanged(key: K, hasFocus: Boolean) {
        if (hasFocus) {
            focused = key
        } else if (focused == key) {
            focused = null
        }
    }
}

@Composable
fun <K : Any> rememberURoving(): URovingState<K> = remember { URovingState() }

/**
 * Makes the widget the item [key] of a roving group. [keys] are the items that can take the focus,
 * in order. Tab reaches one of them: the focused one, else [selected], else the first.
 *
 * Arrows along [orientation] (both axes when `null`) move to the neighbour, around the ends when
 * [loop]; Home and End go to the first and last. [onMove] hears every move, for widgets where
 * moving also selects.
 */
fun <K : Any> Modifier.uRovingItem(
    state: URovingState<K>,
    key: K,
    keys: List<K>,
    selected: K? = null,
    orientation: UOrientation? = null,
    loop: Boolean = true,
    onMove: (K) -> Unit = {},
): Modifier {
    val stop = state.focused?.takeIf { it in keys } ?: selected?.takeIf { it in keys } ?: keys.firstOrNull()
    return focusRequester(state.requester(key))
        .focusable(key == stop)
        .onFocusChange { state.focusChanged(key, it) }
        .onKey { event ->
            if (event.ctrl || event.alt || event.meta) return@onKey false
            val target = rovingTarget(keys, key, event.key, orientation, loop) ?: return@onKey false
            if (target != key) {
                state.focus(target)
                onMove(target)
            }
            true
        }
}

/** The item [pressed] leads to from [current], or `null` when the key does not move in this group. */
internal fun <K> rovingTarget(
    keys: List<K>,
    current: K,
    pressed: Key,
    orientation: UOrientation?,
    loop: Boolean,
): K? {
    if (keys.isEmpty()) return null
    val vertical = orientation != UOrientation.Horizontal
    val horizontal = orientation != UOrientation.Vertical
    val step = when {
        pressed == Key.Down && vertical || pressed == Key.Right && horizontal -> 1
        pressed == Key.Up && vertical || pressed == Key.Left && horizontal -> -1
        pressed == Key.Home -> return keys.first()
        pressed == Key.End -> return keys.last()
        else -> return null
    }
    val index = keys.indexOf(current)
    if (index < 0) return if (step > 0) keys.first() else keys.last()
    val next = index + step
    return if (loop) keys[(next + keys.size) % keys.size] else keys[next.coerceIn(keys.indices)]
}
