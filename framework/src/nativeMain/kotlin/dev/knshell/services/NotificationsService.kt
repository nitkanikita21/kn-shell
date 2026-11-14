package dev.knshell.services

import dev.knshell.core.NotificationInfo
import dev.knshell.core.NotificationListener
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
class NotificationsService internal constructor() : ShellService {

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
        }
        retained += listener
        subscribeNotifications(listener)
    }

    /** Toggles do-not-disturb. */
    fun toggleDoNotDisturb() {
        dnd.value = !dnd.value
    }

    /** Removes one notification from the local history. */
    fun dismiss(id: ULong) {
        mutable.value = mutable.value.filterNot { it.id == id }
    }

    /** Clears the local history. */
    fun clear() {
        mutable.value = emptyList()
    }

    private companion object {
        const val MAX_HISTORY = 50
    }
}
