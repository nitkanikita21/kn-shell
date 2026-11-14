package dev.knshell.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.CenterBox
import dev.knshell.compose.Color
import dev.knshell.compose.Label
import dev.knshell.compose.Align
import dev.knshell.compose.AnimatedWidth
import dev.knshell.compose.Modifier
import dev.knshell.compose.align
import dev.knshell.compose.Overlay
import dev.knshell.compose.Row
import dev.knshell.compose.background
import dev.knshell.compose.fillHeight
import dev.knshell.compose.fontFamily
import dev.knshell.compose.fontSize
import dev.knshell.compose.fontWeight
import dev.knshell.compose.height
import dev.knshell.compose.onClick
import dev.knshell.compose.radius
import dev.knshell.compose.size
import dev.knshell.compose.padding
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.textColor
import dev.knshell.compose.vExpand
import dev.knshell.compose.width
import dev.knshell.compose.widthBehavior
import dev.knshell.core.WorkspaceInfo
import dev.knshell.services.Services
import dev.knshell.ui.primitives.HSpace
import dev.knshell.ui.primitives.Icon
import dev.knshell.ui.primitives.Spacer
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.collections.indexOf
import kotlin.time.Duration.Companion.seconds

@Composable
fun TestBar() {
    CenterBox(
        modifier = Modifier
            .align(h = Align.Fill)
            .align(v = Align.Fill)
            .fontFamily("JetBrainsMono Nerd Font")
            .fontSize(12),
        start = { LeftIsland() },
        center = { CenterIsland() },
        end = { RightIsland() },
    )
}

@Composable
fun LeftIsland() {
    Row(
        16,
        Modifier
            .padding(24, 8)
            .background(Olive.Background)
            .radius(0, 0, 16, 0)
            .align(v = Align.Start)
    ) {
        PlayerChip()
    }
}

@Composable
fun RightIsland() {
    Row(
        16,
        Modifier
            .padding(24, 8)
            .background(Olive.Background)
            .radius(0, 0, 0, 16)
            .align(v = Align.Start)
    ) {
        PlayerChip()
    }
}

@Composable
fun CenterIsland() {
    Row(
        16,
        Modifier
            .padding(24, 8)
            .background(Olive.Background)
            .radius(0, 0, 16, 16)
            .align(v = Align.Start)
    ) {
        Notification()
        Workspaces()
        Clock()
    }
}

@Composable
fun Workspaces() {
    val workspaces by Services.workspaces.state.collectAsState()

    Row(
        spacing = 4,
        modifier = Modifier.background(Olive.Surface).radius(8),
    ) {
        workspaces.forEach { workspace ->
            key(workspace.name) { WorkspaceChip(workspace) }
        }
    }
}

@Composable
private fun WorkspaceChip(workspace: WorkspaceInfo) {
    Overlay(
        modifier = Modifier
            .size((if (workspace.active) 24 * 1.5 else 24).toInt(), 24)
            .widthBehavior()
            .radius(8)
            .background(
                if (workspace.active) Olive.Accent else Color.Transparent,
                transitionMs = 450
            ),
    ) {
        Label(
            if (workspace.active) "<${workspace.name}>" else workspace.name,
            modifier = Modifier
                .textColor(if (workspace.active) Olive.OnAccent else Olive.Foreground)
                .fontWeight(if (workspace.active) 800 else 400)
        )
    }
}

val shotClockFormat = LocalDateTime.Format {
    hour(); char(':'); minute()
}
val longClockFormat = LocalDateTime.Format {
    hour(); char(':'); minute(); chars("   ");

    dayOfMonth(); char(' '); dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED); chars(" ");
    monthName(MonthNames.ENGLISH_ABBREVIATED);


}

@Composable
private fun Clock() {
    var isShortFormat by remember { mutableStateOf(true) }

    val time by produceState(
        initialValue = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
    ) {
        while (true) {
            value = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            delay(1.seconds)
        }
    }

    AnimatedWidth(spacing = 10) {
        Icon("\uF43A")

        Label(
            time.format(if (isShortFormat) shotClockFormat else longClockFormat),
            modifier = Modifier
                .textColor(Grayscale.Accent)
                .onClick { isShortFormat = !isShortFormat }
                .pointerCursor()
                .widthBehavior()
        )
    }
}

enum class NotificationState(var icon: String) {
    EMPTY("\uDB80\uDC9A"),
    HAS_NEW("\uDB84\uDD6B"),
    DND("\uDB80\uDC9B")
}

@Composable
private fun Notification() {
    var state by remember { mutableStateOf(NotificationState.EMPTY) }

    Icon(
        state.icon,
        size = 16,
        modifier = Modifier.onClick {
            state = NotificationState.entries[(state.ordinal + 1) % NotificationState.entries.size]
        })
}