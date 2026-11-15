package dev.knshell.ui.islands

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.Row
import dev.knshell.shell.AttachedPanel
import dev.knshell.ui.island
import dev.knshell.ui.modules.Clock
import dev.knshell.ui.modules.Notification
import dev.knshell.ui.modules.NotificationCenter
import dev.knshell.ui.modules.Workspaces

/**
 * Centre island: notifications, workspaces and the clock.
 *
 * Clicking the island expands **the island itself** downwards: an attached layer-shell
 * panel is created flush under it, exactly as wide as the island, with the same background
 * and square top edge. While expanded the island's bottom corners are squared so the bar
 * island and the panel read as one shape.
 */
@Composable
fun CenterIsland() {
    var expanded by remember { mutableStateOf(false) }

    AttachedPanel(
        width = 0, // match the island width
        height = 420,
        topOffset = 0, // flush under the bar
        alignToParent = true,
        onOpenChange = { expanded = it },
        content = { NotificationCenter() },
    ) {
        Row(
            16,
            island(
                bottomRight = if (expanded) 0 else 16,
                bottomLeft = if (expanded) 0 else 16,
            ),
        ) {
            Notification()
            Workspaces()
            Clock()
        }
    }
}
