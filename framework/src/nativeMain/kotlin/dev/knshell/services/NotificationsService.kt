// Notification service: bounded history (newest first), do-not-disturb and server-mode
// close/action calls.

package dev.knshell.services

import dev.knshell.core.NotificationInfo
import dev.knshell.core.NotificationListener
import dev.knshell.core.notificationsClose
import dev.knshell.core.notificationsInvokeAction
import dev.knshell.core.notificationsIsServer
import dev.knshell.core.notificationsSetDnd
import dev.knshell.core.subscribeNotifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop notifications observed on the session bus (history kept here, newest first).
 *
 * The shell does not replace the user's notification daemon: it only watches `Notify`
 * calls through the Rust service.
 */
class NotificationsService() : ShellService {

    private val mutable = MutableStateFlow<List<NotificationInfo>>(emptyList())

    /** Notification history, newest first (bounded). */
    val state: StateFlow<List<NotificationInfo>> = mutable.asStateFlow()

    private val dnd = MutableStateFlow(false)

    /** Do-not-disturb flag, toggled from the bar; purely a UI preference for now. */
    val doNotDisturb: StateFlow<Boolean> = dnd.asStateFlow()

    private val retained = mutableListOf<Any>()
    private var started = false

    override fun start() {
        if (started) return
        started = true
        val listener = object : NotificationListener {
            override fun onNotification(notification: NotificationInfo) {
                val current = mutable.value
                val withoutReplaced = if (notification.id != 0uL) {
                    current.filterNot { it.id == notification.id }
                } else {
                    current
                }
                mutable.value = (listOf(notification) + withoutReplaced).take(MAX_HISTORY)
            }

            override fun onNotificationClosed(id: ULong, reason: UInt) {
                mutable.value = mutable.value.filterNot { it.id == id }
            }
        }
        retained += listener
        subscribeNotifications(listener)
    }

    /** True when the shell owns `org.freedesktop.Notifications` (control available). */
    val isServer: Boolean get() = notificationsIsServer()

    /** Toggles do-not-disturb; the server then suppresses new notifications while on. */
    fun toggleDoNotDisturb() {
        val next = !dnd.value
        dnd.value = next
        notificationsSetDnd(next)
    }

    /**
     * Closes one notification: through the server (emits `NotificationClosed` and notifies
     * clients) when we own the bus name, otherwise just drops it from the local history.
     */
    fun dismiss(id: ULong) {
        if (!notificationsClose(id)) {
            mutable.value = mutable.value.filterNot { it.id == id }
        }
    }

    /** Invokes an action on a notification (server mode only). */
    fun invokeAction(id: ULong, action: String) {
        notificationsInvokeAction(id, action)
    }

    /** Clears the local history. */
    fun clear() {
        mutable.value = emptyList()
    }

    private companion object {
        const val MAX_HISTORY = 50
    }
}
