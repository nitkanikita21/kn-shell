package dev.knshell.ui.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.knshell.compose.Box
import dev.knshell.compose.Color
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Row
import dev.knshell.compose.Align
import dev.knshell.compose.align
import dev.knshell.compose.background
import dev.knshell.compose.fontWeight
import dev.knshell.compose.hExpand
import dev.knshell.compose.onClick
import dev.knshell.compose.padding
import dev.knshell.compose.pointerCursor
import dev.knshell.compose.radius
import dev.knshell.compose.size
import dev.knshell.compose.textColor
import dev.knshell.compose.width
import dev.knshell.core.NotificationInfo
import dev.knshell.services.Services
import dev.knshell.shell.Popover
import dev.knshell.ui.Grayscale
import dev.knshell.ui.primitives.Icon
import dev.knshell.ui.primitives.Image

private const val ICON_EMPTY = "\uDB80\uDC9A"
private const val ICON_HAS_NEW = "\uDB84\uDD6B"
private const val ICON_DND = "\uDB80\uDC9B"

/** Close button glyph shown on every card. */
private const val ICON_CLOSE = "\uF00D"

/**
 * Notification centre: a **click** popover (not hover, unlike tooltips).
 *
 * Each notification is a card: icon (file paths from `image-path`/`app-icon` are rendered,
 * icon names fall back to a glyph), app + summary, body, action buttons (click invokes the
 * action through the server) and a close button. Urgent notifications use the accent colour.
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
        Icon(icon, size = 16, modifier = Modifier.pointerCursor())
    }
}

@Composable
private fun NotificationPanel(notifications: List<NotificationInfo>, dnd: Boolean) {
    Box(
        modifier = Modifier
            .padding(horizontal = 14, vertical = 10)
            .background(Grayscale.Surface, radius = 10),
    ) {
        Column(spacing = 8) {
            Row(
                spacing = 10,
                modifier = Modifier
                    .onClick { Services.notifications.toggleDoNotDisturb() }
                    .pointerCursor(),
            ) {
                Icon(
                    if (dnd) ICON_DND else ICON_EMPTY,
                    size = 14,
                    color = if (dnd) Grayscale.Accent else Grayscale.Foreground,
                )
                Label(
                    text = if (dnd) "Do not disturb: on" else "Do not disturb: off",
                    modifier = Modifier.textColor(Grayscale.Accent),
                )
            }

            if (notifications.isEmpty()) {
                Label(
                    text = "No notifications",
                    modifier = Modifier.textColor(Grayscale.Foreground),
                )
            } else {
                notifications.take(6).forEach { notification ->
                    NotificationCard(notification)
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

/**
 * Notification centre inside the expanded centre island: DND row, cards and "clear all".
 * Fills the island width; the island itself provides the background.
 */
@Composable
fun NotificationCenter(width: Int) {
    val notifications by Services.notifications.state.collectAsState()
    val dnd by Services.notifications.doNotDisturb.collectAsState()

    Box(modifier = Modifier.width(width).padding(horizontal = 24, vertical = 4)) {
        Column(spacing = 8) {
            Row(
                spacing = 10,
                modifier = Modifier
                    .onClick { Services.notifications.toggleDoNotDisturb() }
                    .pointerCursor(),
            ) {
                Icon(
                    if (dnd) ICON_DND else ICON_EMPTY,
                    size = 14,
                    color = if (dnd) Grayscale.Accent else Grayscale.Foreground,
                )
                Label(
                    text = if (dnd) "Do not disturb: on" else "Do not disturb: off",
                    modifier = Modifier.textColor(Grayscale.Accent),
                )
            }

            if (notifications.isEmpty()) {
                Label("No notifications", modifier = Modifier.textColor(Grayscale.Foreground))
            } else {
                notifications.take(4).forEach { notification ->
                    NotificationCard(notification, width = width - 48)
                }
                if (notifications.size > 4) {
                    Label(
                        "+${notifications.size - 4} more",
                        modifier = Modifier.textColor(Grayscale.Accent),
                    )
                }
                Label(
                    "clear all",
                    modifier = Modifier
                        .textColor(Grayscale.Accent)
                        .onClick { Services.notifications.clear() }
                        .pointerCursor(),
                )
            }
        }
    }
}

/** One notification card: icon, text, actions and a close button. */
@Composable
private fun NotificationCard(notification: NotificationInfo, width: Int = 340) {
    val urgent = notification.urgency >= 2u.toUByte()
    Box(
        modifier = Modifier
            .width(width)
            .padding(8)
            .background(Grayscale.Background, radius = 8),
    ) {
        Row(spacing = 10) {
            NotificationIcon(notification)

            Column(spacing = 3, modifier = Modifier.hExpand()) {
                Label(
                    text = "${notification.app}  ${notification.summary}",
                    modifier = Modifier
                        .textColor(if (urgent) Grayscale.Accent else Grayscale.Foreground)
                        .fontWeight(600),
                    ellipsize = true,
                    // Clamp the natural width so a long summary cannot widen the island.
                    maxWidthChars = ((width - 130) / 7).coerceAtLeast(8),
                )
                if (notification.body.isNotBlank()) {
                    Label(
                        text = notification.body,
                        modifier = Modifier.textColor(Grayscale.Accent),
                        wrap = true,
                        maxWidthChars = ((width - 130) / 7).coerceAtLeast(8),
                    )
                }
                if (notification.actions.isNotEmpty()) {
                    Row(spacing = 10) {
                        notification.actions.forEach { action ->
                            Label(
                                text = action.label,
                                modifier = Modifier
                                    .textColor(ACTION_COLOR)
                                    .onClick {
                                        Services.notifications.invokeAction(notification.id, action.key)
                                    }
                                    .pointerCursor(),
                            )
                        }
                    }
                }
            }

            Label(
                text = ICON_CLOSE,
                modifier = Modifier
                    .align(v = Align.Start)
                    .textColor(Grayscale.Accent)
                    .onClick { Services.notifications.dismiss(notification.id) }
                    .pointerCursor(),
            )
        }
    }
}

@Composable
private fun NotificationIcon(notification: NotificationInfo) {
    val icon = notification.icon
    if (icon.isNotBlank() && icon.startsWith("/")) {
        Image(icon, Modifier.radius(6), width = 28, height = 28)
    } else {
        Icon(ICON_HAS_NEW, size = 18, color = Grayscale.Accent)
    }
}

private val ACTION_COLOR = Color.hex(0x9ECBFF)
