// ---------------------------------------------------------------------------
// Typing the start of an item's name jumps to it, in menus and select lists.
// ---------------------------------------------------------------------------

package dev.knshell.uikit.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Collects the characters typed in quick succession and finds the item they name. */
class UTypeahead(
    private val timeout: Duration = 500.milliseconds,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    private var typed = ""
    private var last: TimeMark? = null

    /**
     * The index in [labels] that [char] leads to from [current] (-1 for none), or `null` when
     * nothing starts with what was typed. Repeating one letter walks through its items.
     */
    fun type(char: Char, labels: List<String>, current: Int): Int? {
        if (last.let { it == null || it.elapsedNow() > timeout }) typed = ""
        last = time.markNow()
        typed += char

        val repeated = typed.all { it.equals(typed.first(), ignoreCase = true) }
        val wanted = if (repeated) typed.take(1) else typed
        // A longer search may stay where it is; a single letter moves on.
        val start = if (repeated) current + 1 else current.coerceAtLeast(0)
        return labels.indices
            .map { (start + it).mod(labels.size) }
            .firstOrNull { labels[it].startsWith(wanted, ignoreCase = true) }
    }
}

@Composable
fun rememberUTypeahead(): UTypeahead = remember { UTypeahead() }
