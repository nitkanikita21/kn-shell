package dev.knshell.ui.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import dev.knshell.compose.Align
import dev.knshell.compose.Color
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Overlay
import dev.knshell.compose.Row
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.fontWeight
import dev.knshell.compose.radius
import dev.knshell.compose.size
import dev.knshell.compose.textColor
import dev.knshell.compose.widthBehavior
import dev.knshell.core.WorkspaceInfo
import dev.knshell.services.Services
import dev.knshell.ui.Grayscale

/** Workspace strip: one animated chip per workspace, active one highlighted. */
@Composable
fun Workspaces() {
    val workspaces by Services.workspaces.state.collectAsState()

    Row(
        spacing = 4,
        modifier = Modifier.background(Grayscale.Surface).radius(8),
    ) {
        workspaces.forEach { workspace ->
            key(workspace.name) { WorkspaceChip(workspace) }
        }
    }
}

@Composable
private fun WorkspaceChip(workspace: WorkspaceInfo) {
    val active = workspace.active
    Overlay(
        modifier = Modifier
            .size(if (active) 36 else 24, 24)
            .widthBehavior()
            .radius(8)
            .background(
                if (active) Grayscale.Accent else Color.Transparent,
                transitionMs = 450,
            )
            .align(v = Align.Center),
    ) {
        Label(
            text = if (active) "<${workspace.name}>" else workspace.name,
            modifier = Modifier
                .textColor(if (active) Grayscale.OnAccent else Grayscale.Foreground)
                .fontWeight(if (active) 800 else 400),
        )
    }
}
