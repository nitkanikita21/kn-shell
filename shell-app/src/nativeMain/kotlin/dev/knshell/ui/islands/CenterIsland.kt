package dev.knshell.ui.islands

import androidx.compose.runtime.Composable
import dev.knshell.compose.Row
import dev.knshell.ui.island
import dev.knshell.ui.modules.Clock
import dev.knshell.ui.modules.Notification
import dev.knshell.ui.modules.Workspaces

/** Center island: notifications, workspaces and the clock. */
@Composable
fun CenterIsland() {
    Row(16, island(bottomRight = 16, bottomLeft = 16)) {
        Notification()
        Workspaces()
        Clock()
    }
}
