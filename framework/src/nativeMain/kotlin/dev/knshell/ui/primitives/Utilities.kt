// Spacing primitives: `Spacer`, `Space`, `HSpace`, `VSpace`. The label primitive `Text` lives in
// Text.kt; icons live in `:icons:core` (`dev.knshell.compose.Icon` + an `IconRef`).
//
// Nothing here carries visual identity — no colours, radii or layout opinions.

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui.primitives

import androidx.compose.runtime.Composable
import dev.knshell.compose.Box
import dev.knshell.compose.Modifier
import dev.knshell.compose.size
import kotlinx.cinterop.ExperimentalForeignApi

/** An empty box used as spacing. Prefer [Space]. */
@Composable
fun Spacer(modifier: Modifier = Modifier) {
    Box(modifier)
}

/** Fixed-size empty space: `Space(8)`, `Space(width = 6, height = 4)`. */
@Composable
fun Space(width: Int = 0, height: Int = 0) {
    Box(Modifier.size(width = width, height = height))
}

/** Horizontal space: `HSpace(8)`. */
@Composable
fun HSpace(width: Int) = Space(width = width, height = 0)

/** Vertical space: `VSpace(6)`. */
@Composable
fun VSpace(height: Int) = Space(width = 0, height = height)
