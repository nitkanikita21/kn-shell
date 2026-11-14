package dev.knshell.ui.islands

import androidx.compose.runtime.Composable
import dev.knshell.compose.Row
import dev.knshell.ui.MediaChip
import dev.knshell.ui.island

/**
 * Right island: media chip.
 *
 * TODO: this duplicates the left island; it is kept to preserve the current design while
 * the bar is being built. A volume/battery module pair would be a better fit here.
 */
@Composable
fun RightIsland() {
    Row(16, island(bottomLeft = 16)) {
        MediaChip()
    }
}
