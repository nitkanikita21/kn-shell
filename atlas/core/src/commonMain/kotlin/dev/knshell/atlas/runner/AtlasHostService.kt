package dev.knshell.atlas.runner

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import dev.knshell.atlas.core.StoryGroup
import dev.knshell.atlas.core.entry.MatrixView
import dev.knshell.atlas.core.entry.Variants
import dev.knshell.atlas.core.arg.LiveArgs
import dev.knshell.atlas.core.entry.StoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AtlasHostService(
    val groups: List<StoryGroup>,
    val environments: List<AtlasEnvironment> = emptyList(),
) {
    val sidebar = SidebarState(groups)

    val view = ViewState()

    /** The environment the preview shows stories in; `null` when the catalog supplies none. */
    var environment by mutableStateOf(environments.firstOrNull())

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    // живі значення args памʼятаються окремо для кожної story до закриття атласу
    private val argsByEntry = mutableMapOf<StoryEntry, LiveArgs>()

    // вигляд кожної матриці та згорнуті групи — теж до закриття атласу
    private val viewByEntry = mutableStateMapOf<Variants, MatrixView>()
    private val collapsed = mutableStateMapOf<Pair<Variants, String>, Boolean>()

    fun viewOf(entry: Variants): MatrixView = viewByEntry[entry] ?: entry.defaultView

    fun setView(entry: Variants, view: MatrixView) {
        require(view in entry.views) { "\"${entry.name}\" has no ${view.title} view" }
        viewByEntry[entry] = view
    }

    fun isCollapsed(entry: Variants, group: String): Boolean = collapsed[entry to group] == true

    fun toggleGroup(entry: Variants, group: String) {
        collapsed[entry to group] = !isCollapsed(entry, group)
    }

    fun focusSearch() {
        view.sidebarHidden = false
        sidebar.searchFocus.requestFocus()
    }

    /** What to bring back next time, with [selected] open. */
    fun memory(selected: Selection?): AtlasMemory = AtlasMemory(
        story = selected?.let { "${it.group.title}/${it.entry.name}" },
        environment = environment?.name,
        views = matrices().filter { viewByEntry[it.second] != null }
            .associate { (name, matrix) -> name to viewOf(matrix).name },
        closedGroups = sidebar.closedGroups,
        closedCells = matrices().flatMap { (name, matrix) ->
            collapsed.filter { it.key.first === matrix && it.value }.map { "$name/${it.key.second}" }
        }.sorted(),
        sidebarHidden = view.sidebarHidden,
        argsHidden = view.argsHidden,
        width = view.width,
    )

    /** Puts [memory] back; what the catalog no longer has is skipped. The selection is the caller's. */
    fun restore(memory: AtlasMemory) {
        environments.firstOrNull { it.name == memory.environment }?.let { environment = it }
        matrices().forEach { (name, matrix) ->
            val kept = matrix.views.firstOrNull { it.name == memory.views[name] }
            if (kept != null) viewByEntry[matrix] = kept
            memory.closedCells.filter { it.startsWith("$name/") }
                .forEach { collapsed[matrix to it.removePrefix("$name/")] = true }
        }
        sidebar.close(memory.closedGroups)
        view.sidebarHidden = memory.sidebarHidden
        view.argsHidden = memory.argsHidden
        view.width = memory.width?.takeIf { it in PREVIEW_WIDTHS }
    }

    private fun matrices(): List<Pair<String, Variants>> = groups.flatMap { group ->
        group.entries.filterIsInstance<Variants>().map { "${group.title}/${it.name}" to it }
    }

    fun select(group: StoryGroup, entry: StoryEntry) {
        view.pointer = null
        _selection.value = Selection(group, entry)
    }

    fun argsFor(selection: Selection): LiveArgs =
        argsByEntry.getOrPut(selection.entry) { LiveArgs(selection.group.args) }
}
