@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.animation.animateFloatAsState
import dev.knshell.compose.Button
import dev.knshell.compose.Canvas
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Row
import dev.knshell.ui.drawCircle
import kotlinx.coroutines.delay

/**
 * Phase 4 test case: "expanding / collapsing circle".
 *
 * Two GtkDrawingAreas are painted through cairo with radii coming from Compose state:
 * one circle grows while the other shrinks, driven by [animateFloatAsState] (a spring
 * implemented on Compose Runtime's `withFrameNanos`, because the official animation
 * module is not published for linuxX64 — see README).
 *
 * The demo also toggles itself every 1.6 s so the animation can be observed without
 * interaction; the button toggles it manually as well.
 */
@Composable
fun CircleAnimationDemo() {
    var expanded by remember { mutableStateOf(false) }
    val drawnFrames = remember { intArrayOf(0) }

    val bigRadius by animateFloatAsState(
        targetValue = if (expanded) 92f else 26f,
        stiffness = 260f,
        dampingRatio = 0.55f,
    )
    val smallRadius by animateFloatAsState(
        targetValue = if (expanded) 26f else 92f,
        stiffness = 260f,
        dampingRatio = 0.55f,
    )

    // IMPORTANT: read the animated state *during composition* so that Compose
    // subscribes to it and recomposes on every animation frame.
    val big = bigRadius
    val small = smallRadius

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_600)
            expanded = !expanded
        }
    }

    Column(spacing = 14) {
        Label(if (expanded) "State: expanded" else "State: collapsed")
        Row(spacing = 26) {
            Canvas(200, 200) { cr, width, height ->
                drawCircle(cr, width, height, big, 0.35, 0.60, 1.00)
                drawnFrames[0] += 1
                if (drawnFrames[0] % 240 == 0) {
                    println("[demo/circle] frames=${drawnFrames[0]} big=${big.toInt()} small=${small.toInt()}")
                }
            }
            Canvas(200, 200) { cr, width, height ->
                drawCircle(cr, width, height, small, 1.00, 0.45, 0.40)
            }
        }
        Button("Toggle circles") { expanded = !expanded }
    }
}
