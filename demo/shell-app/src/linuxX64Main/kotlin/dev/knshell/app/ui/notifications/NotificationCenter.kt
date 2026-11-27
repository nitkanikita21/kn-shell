// ---------------------------------------------------------------------------
// An example of building a shell surface from the kit: a notification card on `UToastCard` and a
// list of them. The data is made up; there is no notification service behind it.
// ---------------------------------------------------------------------------

package dev.knshell.app.ui.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.Column
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.align
import dev.knshell.compose.Align
import dev.knshell.compose.hExpand
import dev.knshell.compose.semantics
import dev.knshell.compose.semiBold
import dev.knshell.uikit.components.element.UAvatar
import dev.knshell.uikit.components.element.UBadge
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.components.feedback.UToastCard
import dev.knshell.uikit.components.feedback.UToastReply
import dev.knshell.uikit.core.UAction
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.UVariant
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.theme.UTheme

data class SampleNotification(
    val id: Int,
    val app: String,
    val title: String,
    val body: String,
    val time: String,
    val canReply: Boolean = false,
    val actions: List<String> = emptyList(),
)

private val samples = listOf(
    SampleNotification(
        id = 1,
        app = "Чат",
        title = "Дизайн: новий фон",
        body = "Подивись, як тема підлаштувалась під нові шпалери, і скажи, чи не забагато кольору.",
        time = "14:02",
        canReply = true,
    ),
    SampleNotification(2, "Пошта", "Три нові листи", "Звіт за тиждень і ще два.", "13:48", actions = listOf("Відкрити", "Прочитано")),
    SampleNotification(3, "Система", "Оновлення готове", "Перезапуск займе близько хвилини.", "12:30", actions = listOf("Перезапустити")),
)

/** A notification of an application as a card: who sent it, what it says and what can be done. */
@Composable
fun NotificationCard(
    notification: SampleNotification,
    onDismiss: () -> Unit,
    onReply: (String) -> Unit,
    onAction: (String) -> Unit,
) {
    UToastCard(
        title = notification.title,
        modifier = Modifier.hExpand().semantics(Semantics(role = Role.ListItem)),
        description = notification.body,
        leading = { UAvatar(notification.app, size = USize.Sm, modifier = Modifier.align(v = Align.Start)) },
        meta = {
            Label(
                notification.time,
                modifier = Modifier.uFont(size = 11).uTextColor(UTheme.tokens.colors.mutedForeground),
            )
        },
        reply = if (notification.canReply) UToastReply(onSend = onReply) else null,
        actions = notification.actions.map { name -> UAction(name) { onAction(name) } },
        onClose = onDismiss,
    )
}

/** The list of notifications with its heading. */
@Composable
fun NotificationCenter(modifier: Modifier = Modifier) {
    var notifications by remember { mutableStateOf(samples) }
    var last by remember { mutableStateOf<String?>(null) }
    val tokens = UTheme.tokens

    Column(spacing = tokens.spacing.sm, modifier = modifier) {
        Row(spacing = tokens.spacing.sm, modifier = Modifier.hExpand()) {
            Label("Сповіщення", align = 0f, modifier = Modifier.uFont(size = 15).semiBold())
            UBadge(notifications.size.toString(), color = UColor.Neutral)
            Row(modifier = Modifier.hExpand().align(h = Align.End)) {
                if (notifications.isEmpty()) {
                    UButton("Повернути приклади", onClick = { notifications = samples }, variant = UVariant.Soft, size = USize.Sm)
                } else {
                    UButton(
                        "Очистити все",
                        onClick = { notifications = emptyList() },
                        color = UColor.Neutral,
                        variant = UVariant.Ghost,
                        size = USize.Sm,
                    )
                }
            }
        }
        last?.let {
            Label(it, align = 0f, modifier = Modifier.uTextColor(tokens.colors.mutedForeground))
        }
        Column(
            spacing = tokens.spacing.sm,
            modifier = Modifier.hExpand().semantics(Semantics(role = Role.List, label = "Сповіщення")),
        ) {
            notifications.forEach { notification ->
                key(notification.id) {
                    NotificationCard(
                        notification = notification,
                        onDismiss = { notifications = notifications.filterNot { it.id == notification.id } },
                        onReply = { last = "Відповідь для «${notification.app}»: $it" },
                        onAction = { last = "«${notification.app}»: $it" },
                    )
                }
            }
        }
    }
}
