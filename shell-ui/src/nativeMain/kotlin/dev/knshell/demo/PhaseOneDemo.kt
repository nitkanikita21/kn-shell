package dev.knshell.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.Button
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import kotlinx.coroutines.delay

/**
 * Phase 1 success criterion: a static window rendered from Compose syntax —
 * a Column with a Label and a Button — where the button click updates Compose
 * state and the new text is applied to the native GtkLabel.
 *
 * A self-test tick also proves the recomposition loop is alive: Compose state is
 * mutated from a coroutine and the GtkLabel is updated through the applier.
 */
@Composable
fun PhaseOneDemo() {
    var clicks by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            clicks += 1
            println("[demo] recomposition tick — clicks=$clicks")
        }
    }

    Column(spacing = 12) {
        Label("KN Shell — Compose Runtime driving native GTK4")
        Label("Clicks: $clicks")
        Button("Click me") { clicks += 1 }
    }
}
