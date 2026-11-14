package dev.knshell.ui.islands

import androidx.compose.runtime.Composable
import dev.knshell.compose.Row
import dev.knshell.ui.MediaChip
import dev.knshell.ui.island

/** Left island: media player chip. */
@Composable
fun LeftIsland() {
    Row(16, island(bottomRight = 16)) {
        MediaChip()
    }
}
