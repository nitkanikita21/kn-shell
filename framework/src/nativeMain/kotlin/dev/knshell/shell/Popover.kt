// Public popover API: `PopoverController`, the `Popover` composable (hover / click / manual)
// and the `HoverPopover` shortcut. The GTK surface is in PopoverNode.kt, its reset CSS in
// PopoverCss.kt.

package dev.knshell.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import dev.knshell.compose.GtkApplier
import dev.knshell.compose.Modifier

/** Imperative handle for opening/closing a [Popover] (manual mode). */
class PopoverController {
    internal var target: GtkHoverPopoverNode? = null

    /** True while the popover is visible. */
    val isOpen: Boolean get() = target?.isShown == true

    /** Shows the popover now, without waiting for hover or click. */
    fun open() {
        target?.showNow()
    }

    /** Hides the popover now. */
    fun close() {
        target?.hideNow()
    }

    /** Opens the popover if it is closed, closes it otherwise. */
    fun toggle() {
        if (isOpen) close() else open()
    }

    internal fun attach(node: GtkHoverPopoverNode) {
        target = node
    }

    internal fun detach() {
        target = null
    }
}

/**
 * Popover anchored to its trigger, with three ways to open it:
 *
 * * **hover** (`openOnHover = true`, default) — shown after [delayMs], hidden after the
 *   pointer leaves both trigger and popover (tooltips);
 * * **click** (`openOnClick = true`) — toggled by clicking the trigger, closed by clicking
 *   outside (notification centre);
 * * **manual** — pass a [PopoverController] and call `open()`/`close()`/`toggle()`.
 *
 * Hover and click can be combined. The content is a **separate Compose composition**, so it
 * recomposes with services like any window. The popover surface is transparent (reset CSS),
 * so the content root owns background/padding/radius.
 *
 * ```kotlin
 * Popover(openOnClick = true, content = { NotificationList() }) { Icon(Lucide.Bell) }
 * ```
 */
@Composable
fun Popover(
    modifier: Modifier = Modifier,
    openOnHover: Boolean = true,
    openOnClick: Boolean = false,
    delayMs: Int = 120,
    hideDelayMs: Int = 250,
    controller: PopoverController? = null,
    content: @Composable () -> Unit,
    anchor: @Composable () -> Unit,
) {
    ComposeNode<GtkHoverPopoverNode, GtkApplier>(
        factory = {
            GtkHoverPopoverNode(delayMs, hideDelayMs, openOnHover, openOnClick).also { node ->
                node.contentState.value = content
                node.controller = controller
                controller?.attach(node)
            }
        },
        update = {
            set(modifier) { value -> applyModifier(value) }
            set(content) { value -> contentState.value = value }
        },
    ) {
        anchor()
    }
}

/** Back-compat wrapper: a [Popover] that opens on hover. */
@Composable
fun HoverPopover(
    modifier: Modifier = Modifier,
    delayMs: Int = 120,
    hideDelayMs: Int = 250,
    content: @Composable () -> Unit,
    trigger: @Composable () -> Unit,
) = Popover(
    modifier = modifier,
    openOnHover = true,
    openOnClick = false,
    delayMs = delayMs,
    hideDelayMs = hideDelayMs,
    content = content,
    anchor = trigger,
)
