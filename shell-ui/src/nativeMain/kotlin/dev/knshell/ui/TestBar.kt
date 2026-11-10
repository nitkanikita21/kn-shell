package dev.knshell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.animation.animateFloatAsState
import dev.knshell.compose.Box
import dev.knshell.compose.Button
import dev.knshell.compose.Catppuccin
import dev.knshell.compose.CenterBox
import dev.knshell.compose.Color
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.background
import dev.knshell.compose.fillMaxWidth
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.height
import dev.knshell.compose.hoverBackground
import dev.knshell.compose.margin
import dev.knshell.compose.padding
import dev.knshell.compose.size
import dev.knshell.compose.textColor
import dev.knshell.core.WorkspaceInfo
import dev.knshell.services.ShellServices
import dev.knshell.ui.primitives.HSpace
import dev.knshell.ui.primitives.Text
import kotlin.math.roundToInt

@Composable
fun TestBar() {
    CenterBox(
        modifier = Modifier
            .fillMaxWidth()
            .height(16)
            .fontFamily("JetBrainsMono Nerd Font")
            .fontSize(2),
        start = { HSpace(1) },
        center = { Workspaces() },
        end = { HSpace(1) },
    )
}

@Composable
private fun Workspaces() {
    var list by remember { mutableStateOf<List<WorkspaceInfo>>(emptyList()) }
    LaunchedEffect(Unit) { ShellServices.workspaces.collect { list = it } }

    Row(
        spacing = 4,
        modifier = Modifier
            .background(Catppuccin.Surface0, radius = 16, shadow = true)
            .margin(horizontal = 2),
    ) {
        list.forEach { workspace ->
            key(workspace.name) { WorkspaceChip(workspace) }
        }
    }
}

@Composable
private fun WorkspaceChip(workspace: WorkspaceInfo) {
    val chipWidth by animateFloatAsState(
        targetValue = if (workspace.active) 24f else 8f,
        stiffness = 120f,
        dampingRatio = 0.65f,
    )

    Box(
        modifier = Modifier
            .size(chipWidth.roundToInt() * 2, 8)
            .background(
                if (workspace.active) Catppuccin.Mauve else Catppuccin.Surface3,
                transitionMs = 1250,
            )
            .margin(0, 0)
            .padding(0)
            .textColor(if (workspace.active) Catppuccin.Crust else Catppuccin.Mauve)
            .hoverBackground(Catppuccin.Accent),
    ) {
        Text(chipWidth.toString().split(".")[0])
    }
}