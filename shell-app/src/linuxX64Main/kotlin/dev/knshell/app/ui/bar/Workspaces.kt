package dev.knshell.app.ui.bar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.knshell.animation.AnimationSpec
import dev.knshell.animation.Easing
import dev.knshell.app.ui.helpers.collectTheme
import dev.knshell.app.ui.helpers.injectDi
import dev.knshell.compose.Box
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.Stack
import dev.knshell.compose.animatedWidth
import dev.knshell.compose.background
import dev.knshell.compose.extraBold
import dev.knshell.compose.padding
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
        modifier = Modifier.background(theme.colors.background)
            .padding(8, 4)
    ) {
        workspaces.forEach { workspace ->
            Box(
                Modifier.extraBold()
                    .animatedWidth(target = if (workspace.active) 48 else 32, spec = AnimationSpec.Tween(200, Easing.OutCubic))
                    .radius(theme.radius.sm)
                    .background(if (workspace.active) theme.colors.accent else theme.colors.card)
                    .textColor(if (workspace.active) theme.colors.accentForeground else theme.colors.cardForeground)
            ) {
                Label(
                    if (workspace.active) "<${workspace.name}>" else workspace.name,
                    Modifier.then(if (workspace.active) Modifier.extraBold() else Modifier).weight(1.0)
                )
            }
        }
    }
}