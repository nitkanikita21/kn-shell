package dev.knshell.ui.islands

import androidx.compose.runtime.Composable
import dev.knshell.compose.Align
import dev.knshell.compose.Row
import dev.knshell.compose.align
import dev.knshell.ui.island
import dev.knshell.ui.modules.Network

/** Right island: live network throughput. */
@Composable
fun RightIsland() {
    Row(16, island(bottomLeft = 16).align(v = Align.Start)) {
        Network()
    }
}
