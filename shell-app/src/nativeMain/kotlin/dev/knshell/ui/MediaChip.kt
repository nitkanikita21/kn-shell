package dev.knshell.ui

import androidx.compose.runtime.Composable

/**
 * Bar-level wrapper around [PlayerChip].
 *
 * Keeps the island code independent from the chip component, so the chip can be swapped
 * (or configured differently per island) without touching the layout.
 */
@Composable
fun MediaChip() {
    PlayerChip()
}
