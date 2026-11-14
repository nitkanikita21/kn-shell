package dev.knshell.ui.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.Modifier
import dev.knshell.compose.onClick
import dev.knshell.ui.primitives.Icon

/** Visual state of the notification bell (placeholder until a notifications service exists). */
enum class NotificationState(val icon: String) {
    EMPTY("\uDB80\uDC9A"),
    HAS_NEW("\uDB84\uDD6B"),
    DND("\uDB80\uDC9B"),
}

/** Notification bell; clicking cycles through the states for now. */
@Composable
fun Notification() {
    var state by remember { mutableStateOf(NotificationState.EMPTY) }

    Icon(
        state.icon,
        size = 16,
        modifier = Modifier.onClick {
            state = NotificationState.entries[(state.ordinal + 1) % NotificationState.entries.size]
        },
    )
}
