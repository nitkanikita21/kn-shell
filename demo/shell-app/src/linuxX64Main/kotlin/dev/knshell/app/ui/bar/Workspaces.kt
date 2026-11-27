package dev.knshell.app.ui.bar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.knshell.animation.AnimationSpec
import dev.knshell.animation.Easing
import dev.knshell.app.ui.helpers.collectTheme
import dev.knshell.app.ui.helpers.injectDi
import dev.knshell.compose.Box
import dev.knshell.compose.Color
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.Stack
import dev.knshell.compose.animatedWidth
import dev.knshell.compose.background
import dev.knshell.compose.extraBold
import dev.knshell.compose.onClick
import dev.knshell.compose.padding
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.radius
import dev.knshell.compose.textColor
import dev.knshell.compose.weight
import dev.knshell.compose.width
import dev.knshell.services.WorkspacesService

@Composable
fun Workspaces() {
    val workspacesService by injectDi<WorkspacesService>()
    val theme by collectTheme()
    val workspaces by workspacesService.state.collectAsState()

    println(workspaces.size)

    Row(
        spacing = 0,
        modifier = Modifier
            .background(theme.colors.card)
            .radius(theme.radius.md)
    ) {
        workspaces.forEach { workspace ->
            Box(
                Modifier.extraBold()
                    .animatedWidth(target = if (workspace.active) 24+12 else 24, spec = AnimationSpec.Tween(200, Easing.OutCubic))
                    .radius(theme.radius.md)
                    .background(if (workspace.active) theme.colors.primary else Color.Transparent)
                    .textColor(if (workspace.active) theme.colors.primaryForeground else theme.colors.mutedForeground)
                    .pointerCursor()
                    .onClick { workspacesService.focus(workspace.name) }
            ) {
                Label(
                    workspace.name,
                    Modifier.then(if (workspace.active) Modifier.extraBold() else Modifier).weight(1.0)
                )
            }
        }
    }
}