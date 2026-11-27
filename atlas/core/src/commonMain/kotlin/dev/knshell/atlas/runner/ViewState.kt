package dev.knshell.atlas.runner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the area beside the tree shows. */
enum class MainTab { Preview, Script, Diff }

/** The widths the preview can be held to, in pixels. */
val PREVIEW_WIDTHS: List<Int> = listOf(320, 640)

/** How the window is arranged: panels, the tab, the width of the preview and the measuring tool. */
class ViewState {
    var sidebarHidden by mutableStateOf(false)
    var argsHidden by mutableStateOf(false)

    /** The tab asked for; a tab with nothing to show gives way to the preview. */
    var tab by mutableStateOf(MainTab.Preview)

    /** The width stories are held to; `null` leaves it to the window. */
    var width by mutableStateOf<Int?>(null)

    var measuring by mutableStateOf(false)
        private set

    /** Where the pointer is while measuring, in the coordinates of the preview. */
    var pointer by mutableStateOf<Pair<Int, Int>?>(null)

    fun measure(on: Boolean) {
        measuring = on
        pointer = null
    }
}
