package dev.knshell.ui.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.background
import dev.knshell.compose.onClick
import dev.knshell.compose.padding
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.radius
import dev.knshell.compose.textColor
import dev.knshell.core.NotificationInfo
import dev.knshell.services.Services
import dev.knshell.shell.Popover
import dev.knshell.ui.Grayscale
import dev.knshell.ui.primitives.Icon

private const val ICON_EMPTY = "\uDB80\uDC9A"
private const val ICON_HAS_NEW = "\uDB84\uDD6B"
private const val ICON_DND = "\uDB80\uDC9B"

/**
 * Notification centre: a **click** popover (not hover, unlike tooltips).
 *
 * * click the bell — open/close the panel;
 * * click outside — close (GTK autohide);
 * * inside: DND toggle, newest notifications (click one to close it), "clear all".
 */
@Composable
fun Notification() {
    val notifications by Services.notifications.state.collectAsState()
    val dnd by Services.notifications.doNotDisturb.collectAsState()

    val icon = when {
        dnd -> ICON_DND
        notifications.isNotEmpty() -> ICON_HAS_NEW
        else -> ICON_EMPTY
    }

    Popover(
        openOnHover = false,
        openOnClick = true,
        content = { NotificationPanel(notifications, dnd) },
    ) {
        Icon(
            icon,
            size = 16,
            modifier = Modifier.pointerCursor(),
        )
    }
}

@Composable
private fun NotificationPanel(notifications: List<NotificationInfo>, dnd: Boolean) {
    Box(
        modifier = Modifier
            .padding(horizontal = 14, vertical = 10)
            .background(Grayscale.Surface, radius = 10),
    ) {
        Column(spacing = 6) {
            Label(
                text = if (dnd) "Do not disturb: on" else "Do not disturb: off",
                modifier = Modifier
                    .textColor(Grayscale.Accent)
                    .onClick { Services.notifications.toggleDoNotDisturb() }
                    .pointerCursor(),
            )

            if (notifications.isEmpty()) {
                Label(
                    text = "No notifications",
                    modifier = Modifier.textColor(Grayscale.Foreground),
                )
            } else {
                notifications.take(6).forEach { notification ->
                    Box(
                        modifier = Modifier
                            .onClick { Services.notifications.dismiss(notification.id) }
                            .pointerCursor(),
                    ) {
                        Column(spacing = 2) {
                            Label(
                                text = "${notification.app}  ${notification.summary}",
                                modifier = Modifier.textColor(Grayscale.Foreground),
                            )
                            if (notification.body.isNotBlank()) {
                                Label(
                                    text = notification.body,
                                    modifier = Modifier.textColor(Grayscale.Accent),
                                )
                            }
                        }
                    }
                }
                if (notifications.size > 6) {
                    Label(
                        text = "+${notifications.size - 6} more",
                        modifier = Modifier.textColor(Grayscale.Accent),
                    )
                }
                Label(
                    text = "clear all",
                    modifier = Modifier
                        .textColor(Grayscale.Accent)
                        .onClick { Services.notifications.clear() }
                        .pointerCursor(),
                )
            }
        }
    }
}
