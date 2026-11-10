package dev.knshell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.animation.Easing
import dev.knshell.animation.animateFloatAsState
import dev.knshell.compose.Box
import dev.knshell.compose.Catppuccin
import dev.knshell.compose.CenterBox
import dev.knshell.compose.Modifier
import dev.knshell.compose.Overlay
import dev.knshell.compose.Row
import dev.knshell.compose.background
import dev.knshell.compose.centerHorizontally
import dev.knshell.compose.centerVertically
import dev.knshell.compose.fillMaxHeight
import dev.knshell.compose.fillMaxWidth
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.height
import dev.knshell.compose.padding
import dev.knshell.compose.radius
import dev.knshell.compose.size
import dev.knshell.compose.textColor
import dev.knshell.compose.width
import dev.knshell.core.WorkspaceInfo
import dev.knshell.services.ShellServices
import dev.knshell.ui.primitives.HSpace
import dev.knshell.ui.primitives.Text

@Composable
fun TestBar() {
    CenterBox(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .fontFamily("JetBrainsMono Nerd Font")
            .fontSize(12),
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
        spacing = 0,
        modifier = Modifier
            .background(Catppuccin.Surface0, radius = 16, shadow = true)
            .centerVertically()
            .padding(horizontal = 8, vertical = 4),
    ) {
        list.forEach { workspace ->
            key(workspace.name) {
                // Fixed 48px slot: the chip animates inside, so this Row keeps a constant
                // width when the active workspace moves (width = slots * 48 + 16).
                Box(Modifier.width(48).height(28)) {
                    WorkspaceChip(workspace)
                }
            }
        }
    }
}

@Composable
private fun WorkspaceChip(workspace: WorkspaceInfo) {
    val chipWidth by animateFloatAsState(
        targetValue = if (workspace.active) 32f else 16f,
        durationMs = 180,
        easing = Easing.OutCubic,
    )

    Overlay(
        modifier = Modifier
            .size(chipWidth.toInt(), 16)
            .radius(999)
            .centerHorizontally()
            .background(
                if (workspace.active) Catppuccin.Mauve else Catppuccin.Surface3,
                transitionMs = 150,
            )
            .centerVertically()
            .textColor(if (workspace.active) Catppuccin.Crust else Catppuccin.Text),
    ) {
        if (workspace.active) Text(workspace.name)
    }
}
