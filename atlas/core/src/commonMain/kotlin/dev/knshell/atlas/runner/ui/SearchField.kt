package dev.knshell.atlas.runner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.atlas.runner.SidebarState
import dev.knshell.compose.Align
import dev.knshell.compose.Entry
import dev.knshell.compose.Icon
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.border
import dev.knshell.compose.entryStyle
import dev.knshell.compose.focusRequester
import dev.knshell.compose.hExpand
import dev.knshell.compose.onFocusChange
import dev.knshell.compose.onKey
import dev.knshell.compose.padding
import dev.knshell.compose.testTag
import dev.knshell.compose.textColor
import dev.knshell.icons.lucide.Lucide

/**
 * The search over the tree. Arrows move through the matches, Enter calls [onOpen] for the one the
 * cursor is on, Escape clears the text and, with nothing to clear, calls [onLeave].
 */
@Composable
internal fun SearchField(state: SidebarState, onOpen: () -> Unit, onLeave: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    with(AtlasTheme) {
        Row(
            spacing = 6,
            modifier = Modifier
                .hExpand()
                .background(input, radius = RADIUS, transitionMs = 0)
                .border(if (focused) accent else line2)
                .padding(horizontal = 6, vertical = 2),
        ) {
            Icon(Lucide.Search, Modifier.align(v = Align.Center), size = 12, color = dim, transitionMs = 0)
            Entry(
                value = state.query,
                placeholder = "пошук stories",
                modifier = Modifier
                    .hExpand()
                    .themedText()
                    .entryStyle(
                        textColor = fg,
                        caretColor = accent,
                        selectionBackground = accent2,
                        selectionColor = accent,
                        placeholderColor = dim,
                    )
                    .focusRequester(state.searchFocus)
                    .onFocusChange { focused = it }
                    // Before the field's own handling, which would keep Enter and the arrows.
                    .onKey(capture = true) { event ->
                        when (event.key) {
                            Key.Down -> state.move(1)
                            Key.Up -> state.move(-1)
                            Key.Enter -> onOpen()
                            Key.Escape -> if (state.query.isEmpty()) onLeave() else state.search("")
                            else -> return@onKey false
                        }
                        true
                    }
                    .testTag("search"),
                onValueChange = { state.search(it) },
            )
            if (state.query.isEmpty()) {
                Label("Ctrl K", modifier = Modifier.align(v = Align.Center).themedText().textColor(dim, transitionMs = 0))
            } else {
                ToolButton("✕", "search:clear", color = dim) {
                    state.search("")
                    state.searchFocus.requestFocus()
                }
            }
        }
    }
}
