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
        sidebar.searchFocus.requestFocus()
    }

    fun select(group: StoryGroup, entry: StoryEntry) {
        _selection.value = Selection(group, entry)
    }

    fun argsFor(selection: Selection): LiveArgs =
        argsByEntry.getOrPut(selection.entry) { LiveArgs(selection.group.args) }
}
