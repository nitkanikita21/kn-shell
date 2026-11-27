package dev.knshell.app.ui.bar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import dev.knshell.animation.AnimationSpec
import dev.knshell.animation.Easing
import dev.knshell.app.ui.helpers.injectDi
import dev.knshell.compose.Box
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.animatedWidth
import dev.knshell.compose.extraBold
import dev.knshell.compose.onClick
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.semantics
import dev.knshell.compose.weight
import dev.knshell.services.WorkspacesService
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.core.withAlpha
import dev.knshell.uikit.theme.UTheme

/**
 * The workspaces of the compositor as a row of tabs, the active one wider. The bar takes no keyboard,
 * so they are clicked; a screen reader still learns which one is selected.
 */
@Composable
fun Workspaces() {
    val workspacesService by injectDi<WorkspacesService>()
    val workspaces by workspacesService.state.collectAsState()
    val tokens = UTheme.tokens

    Row(
        spacing = 0,
        modifier = Modifier
            .uFill(tokens.colors.card, radius = tokens.radius.md)
            .semantics(Semantics(role = Role.TabList, label = "Робочі простори")),
    ) {
        workspaces.forEach { workspace ->
            key(workspace.name) {
                val active = workspace.active
                Box(
                    Modifier
                        .animatedWidth(target = if (active) 24 + 12 else 24, spec = AnimationSpec.Tween(200, Easing.OutCubic))
                        .uFill(
                            if (active) tokens.colors.primary else tokens.colors.primary.withAlpha(0f),
                            radius = tokens.radius.md,
                        )
                        .uTextColor(if (active) tokens.colors.primaryForeground else tokens.colors.mutedForeground)
                        .pointerCursor()
                        .onClick { workspacesService.focus(workspace.name) }
                        .semantics(Semantics(role = Role.Tab, label = "Простір ${workspace.name}", selected = active)),
                ) {
                    Label(
                        workspace.name,
                        Modifier.then(if (active) Modifier.extraBold() else Modifier).weight(1.0),
                    )
                }
            }
        }
    }
}
