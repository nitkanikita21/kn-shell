package dev.knshell.atlas.runner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.entry.StoryEntry
import dev.knshell.compose.FocusRequester

/** What the user did with the tree: the search, where the keyboard is in it, and the closed groups. */
class SidebarState(private val groups: List<StoryGroup>) {
    var query by mutableStateOf("")
        private set

    /** The story the arrows are on, as `Group/Entry`; `null` before the keyboard is used. */
    private var cursor by mutableStateOf<String?>(null)

    private val closed = mutableStateListOf<String>()

    /** Moves the focus to the search field. */
    val searchFocus = FocusRequester()

    val words: List<String> get() = searchWords(query)

    val searching: Boolean get() = words.isNotEmpty()

    fun search(text: String) {
        query = text
        cursor = null
    }

    /** A search opens every group, and clearing it brings the closed ones back. */
    fun isClosed(group: StoryGroup): Boolean = !searching && group.title in closed

    fun toggle(group: StoryGroup) {
        if (!closed.remove(group.title)) closed.add(group.title)
    }

    val closedGroups: List<String> get() = closed.toList()

    fun close(titles: Collection<String>) {
        closed.clear()
        closed.addAll(titles.filter { title -> groups.any { it.title == title } })
    }

    val allClosed: Boolean get() = groups.all { it.title in closed }

    /** Closes every group, or opens every one when all are closed. */
    fun toggleAll() {
        val open = allClosed
        closed.clear()
        if (!open) closed.addAll(groups.map { it.title })
    }

    /** The stories on screen, top to bottom, which the arrows walk. */
    private fun reachable(): List<Pair<StoryGroup, StoryEntry>> =
        sidebarSections(groups, words).flatMap { it.groups }
            .filterNot { isClosed(it.group) }
            .flatMap { listed -> listed.entries.map { listed.group to it } }

    private fun Pair<StoryGroup, StoryEntry>.key() = "${first.title}/${second.name}"

    /** The story Enter opens: where the arrows are, else the first match of a search. */
    val cursorKey: String?
        get() {
            val visible = reachable().map { it.key() }
            return cursor?.takeIf { it in visible } ?: visible.firstOrNull()?.takeIf { searching }
        }

    /** Moves the cursor [step] stories down, around the ends. */
    fun move(step: Int) {
        val visible = reachable().map { it.key() }
        if (visible.isEmpty()) return
        val at = visible.indexOf(cursorKey)
        cursor = when {
            at >= 0 -> visible[(at + step).mod(visible.size)]
            step > 0 -> visible.first()
            else -> visible.last()
        }
    }

    /** The story under the cursor. */
    fun atCursor(): Pair<StoryGroup, StoryEntry>? {
        val key = cursorKey ?: return null
        return reachable().firstOrNull { it.key() == key }
    }
}
