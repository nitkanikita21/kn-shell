package dev.knshell.uikit.components.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.Align
import dev.knshell.compose.margin
import dev.knshell.compose.align
import dev.knshell.compose.Column
import dev.knshell.compose.FocusRequester
import dev.knshell.compose.Key
import dev.knshell.compose.Modifier
import dev.knshell.compose.Portal
import dev.knshell.compose.Role
import dev.knshell.compose.SemanticsRef
import dev.knshell.compose.inputRegion
import dev.knshell.compose.onFocusChange
import dev.knshell.compose.onHover
import dev.knshell.compose.onKey
import dev.knshell.compose.semanticsRef
import dev.knshell.compose.testTag
import dev.knshell.icons.IconRef
import dev.knshell.uikit.core.UAction
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.theme.UTheme
import kotlin.time.Duration

/** The corner, or the edge, of the window where a [UToasterHost] stacks its toasts. */
enum class UPosition { TopStart, TopCenter, TopEnd, BottomStart, BottomCenter, BottomEnd }

/** Names one toast of a [UToaster]. */
data class UToastId internal constructor(val value: Long)

/** A toast to show: what it says, how long it stays and what can be done about it. */
data class UToast(
    val title: String,
    val description: String? = null,
    val color: UColor = UColor.Neutral,
    /** The status icon; `null` takes the one that [color] means. */
    val icon: IconRef? = null,
    /**
     * How long it stays. `null` takes the toaster's duration, then the theme's. [Duration.INFINITE]
     * keeps it until it is closed. A [UColor.Error] toast without a duration of its own stays too.
     */
    val duration: Duration? = null,
    /** Buttons under the text. They do not close the toast by themselves. */
    val actions: List<UAction> = emptyList(),
    val image: UToastImage? = null,
    val reply: UToastReply? = null,
    /** Whether the countdown bar is drawn, for a toast that counts down. */
    val showProgress: Boolean = true,
)

/**
 * How long a toast stays: its own [own] duration; else forever for an [UColor.Error] toast, which
 * waits for the user; else the toaster's [toaster] duration; else the theme's [theme].
 */
internal fun resolveToastDuration(own: Duration?, color: UColor, toaster: Duration?, theme: Duration): Duration {
    if (own != null) return own
    if (color == UColor.Error) return Duration.INFINITE
    return toaster ?: theme
}

/** A toast in the queue. [revision] counts the updates, so that an update restarts its countdown. */
internal data class UToastEntry(val id: UToastId, val toast: UToast, val revision: Int)

/**
 * The queue of toasts that a [UToasterHost] shows, oldest first. [duration] is the default for its
 * toasts; `null` leaves the choice to the theme.
 */
class UToaster internal constructor(internal val duration: Duration?) {
    private var lastId = 0L
    internal val entries = mutableStateListOf<UToastEntry>()
    private val requesters = mutableMapOf<UToastId, FocusRequester>()

    /** The toasts in the queue, oldest first. */
    val toasts: List<UToast> get() = entries.map { it.toast }

    /** Adds [toast] at the end of the queue and returns its id. */
    fun add(toast: UToast): UToastId {
        val id = UToastId(++lastId)
        entries.add(UToastEntry(id, toast, revision = 0))
        return id
    }

    /** Replaces the toast [id] and restarts its countdown. Does nothing for an id that is gone. */
    fun update(id: UToastId, toast: UToast) {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = entries[index]
        entries[index] = old.copy(toast = toast, revision = old.revision + 1)
    }

    /** Takes the toast [id] away. */
    fun remove(id: UToastId) {
        entries.removeAll { it.id == id }
        requesters.remove(id)
    }

    /** Takes every toast away. */
    fun clear() {
        entries.clear()
        requesters.clear()
    }

    internal fun requesterOf(id: UToastId): FocusRequester = requesters.getOrPut(id) { FocusRequester() }

    /** Moves the focus to the newest toast on screen. */
    internal fun focusNewest() {
        entries.lastOrNull()?.let { requesters[it.id]?.requestFocus() }
    }
}

/**
 * A toaster with an empty queue. [duration] is the default for its toasts; `null` takes the theme's.
 * The value is read when the toaster is first remembered.
 */
@Composable
fun rememberUToaster(duration: Duration? = null): UToaster = remember { UToaster(duration) }

private val UPosition.isTop: Boolean
    get() = this == UPosition.TopStart || this == UPosition.TopCenter || this == UPosition.TopEnd

private fun UPosition.across(): Align = when (this) {
    UPosition.TopStart, UPosition.BottomStart -> Align.Start
    UPosition.TopCenter, UPosition.BottomCenter -> Align.Center
    UPosition.TopEnd, UPosition.BottomEnd -> Align.End
}

/**
 * Shows the queue of [toaster] as a stack in the [position] of the window, inside a Portal, so an
 * `OverlayHost` must be above it.
 *
 * Each toast is announced once: as a status, or as an alert for [UColor.Error]. Its countdown stops
 * while the pointer is over it, while the focus is in it, and while its reply has text. When the
 * countdown ends the toast is removed. With `motion.animate` off, nothing counts down.
 *
 * F8 moves the focus to the newest toast when [uToastShortcut] is on the root of the window.
 */
@Composable
fun UToasterHost(
    toaster: UToaster,
    position: UPosition = UPosition.BottomEnd,
    modifier: Modifier = Modifier,
) {
    Portal {
        val tokens = UTheme.tokens
        val side = position.across()
        // Only as large as the stack, so that the rest of the window stays clickable.
        Column(
            spacing = tokens.spacing.sm,
            modifier = Modifier
                .align(h = side, v = if (position.isTop) Align.Start else Align.End)
                .margin(horizontal = tokens.spacing.lg, vertical = tokens.spacing.lg)
                .inputRegion()
                .then(modifier),
        ) {
            toaster.entries.forEach { entry ->
                key(entry.id) { ToastSlot(toaster, entry) }
            }
        }
    }
}

/** One toast of the stack: its countdown, its hover and focus state, and its announcement. */
@Composable
private fun ToastSlot(toaster: UToaster, entry: UToastEntry) {
    val toast = entry.toast
    val duration = resolveToastDuration(
        own = toast.duration,
        color = toast.color,
        toaster = toaster.duration,
        theme = uToastDefaults().duration,
    )
    var hovered by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var drafting by remember { mutableStateOf(false) }
    val countdown = rememberUCountdown(
        duration = duration,
        paused = hovered || focused || drafting,
        onFinish = { toaster.remove(entry.id) },
    )
    LaunchedEffect(entry.revision) {
        if (entry.revision > 0) countdown.restart()
    }

    val announced = remember { SemanticsRef() }
    LaunchedEffect(entry.id) {
        val words = listOfNotNull(toast.title, toast.description).joinToString(". ")
        announced.announce(words, assertive = toast.color == UColor.Error)
    }

    val reply = toast.reply?.let { own ->
        own.copy(
            onDraftChange = { text ->
                drafting = text.isNotEmpty()
                own.onDraftChange(text)
            },
        )
    }
    ToastCardBody(
        focus = toaster.requesterOf(entry.id),
        title = toast.title,
        modifier = Modifier
            .testTag("uToast-${entry.id.value}")
            .semanticsRef(announced)
            .onHover(onEnter = { hovered = true }, onExit = { hovered = false })
            .onFocusChange { focused = it },
        role = if (toast.color == UColor.Error) Role.Alert else Role.Status,
        description = toast.description,
        color = toast.color,
        icon = toast.icon ?: toast.color.statusIcon(),
        leading = null,
        meta = null,
        image = toast.image,
        reply = reply,
        actions = toast.actions,
        progress = if (toast.showProgress && duration.isFinite()) countdown.progress else null,
        onClose = { toaster.remove(entry.id) },
        ui = UToastUi(),
        content = null,
    )
}

/** Takes F8 to the newest toast of [toaster], wherever the focus is in this window. Put it on the root. */
fun Modifier.uToastShortcut(toaster: UToaster): Modifier = onKey { event ->
    val wanted = event.key == Key.F8
    if (wanted) toaster.focusNewest()
    wanted
}
