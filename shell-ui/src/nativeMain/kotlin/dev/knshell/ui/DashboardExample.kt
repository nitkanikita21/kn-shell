@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.Button
import dev.knshell.compose.Catppuccin
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.bold
import dev.knshell.compose.fontSize
import dev.knshell.compose.padding
import dev.knshell.compose.textColor
import dev.knshell.core.MediaState
import dev.knshell.core.WindowInfo
import dev.knshell.core.WorkspaceInfo
import dev.knshell.services.ShellServices
import dev.knshell.shell.Shell
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.launch

/**
 * Small dashboard used by `KN_SHELL_MODE=multiwindow`.
 *
 * It is deliberately independent from [TopBar]: both are mounted as separate windows on
 * the same runtime and both consume the same reactive [ShellServices] flows, which is the
 * proof that multiple windows share one Recomposer and one GLib loop.
 */
@Composable
fun DashboardExample() {
    var volume by remember { mutableStateOf(0) }
    var mediaTitle by remember { mutableStateOf("nothing playing") }
    var playing by remember { mutableStateOf(false) }
    var windows by remember { mutableStateOf<List<WindowInfo>>(emptyList()) }
    var workspaces by remember { mutableStateOf<List<WorkspaceInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        launch { ShellServices.volume.collect { volume = it } }
        launch {
            ShellServices.media.collect { state: MediaState ->
                mediaTitle = state.title.ifBlank { state.player.ifBlank { "nothing playing" } }
                playing = state.playing
            }
        }
        launch { ShellServices.windows.collect { list -> windows = list } }
        launch { ShellServices.workspaces.collect { list -> workspaces = list } }
    }

    Column(spacing = 8, modifier = Modifier.padding(all = 16)) {
        Label(
            "KN Shell — Dashboard",
            Modifier.fontSize(18).bold().textColor(Catppuccin.Text),
        )

        Label(
            "Workspaces: " + workspaces.joinToString("  ") { if (it.active) "[${it.name}]" else it.name },
            Modifier.fontSize(12).textColor(Catppuccin.Subtext),
        )

        Label(
            "${if (playing) "\u25B6" else "\u23F8"} ${mediaTitle.take(38)}",
            Modifier.fontSize(13).textColor(if (playing) Catppuccin.Blue else Catppuccin.Overlay),
        )

        Row(spacing = 8) {
            Button(if (playing) "Pause" else "Play") { ShellServices.togglePlayPause() }
            Button("-5%") { ShellServices.setVolume((volume - 5).coerceAtLeast(0)) }
            Label("$volume%", Modifier.fontSize(14).bold().textColor(Catppuccin.Green))
            Button("+5%") { ShellServices.setVolume((volume + 5).coerceAtMost(150)) }
        }

        Label(
            "Windows (${windows.size})",
            Modifier.fontSize(13).bold().textColor(Catppuccin.Text),
        )
        windows.take(4).forEach { window ->
            Label(
                "  ${window.appId} :: ${window.title}".take(48),
                Modifier.fontSize(12).textColor(Catppuccin.Subtext),
            )
        }

        Row(spacing = 8) {
            Button("Spawn terminal") { Shell.Hyprland.spawn("foot") }
            Button("Workspace 1") { Shell.Hyprland.focusWorkspace("1") }
            Button("Dispatch noop") { Shell.Hyprland.dispatch("hl.dsp.exec_cmd(\"true\")") }
        }
    }
}
