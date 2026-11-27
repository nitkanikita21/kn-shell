// ---------------------------------------------------------------------------
// A popup whose visibility is state of the caller: the surface of menus, select lists and popovers.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCompositionContext
import dev.knshell.compose.GtkApplier
import dev.knshell.compose.GtkNode
import dev.knshell.compose.Modifier
import dev.knshell.compose.containsFocus
import dev.knshell.compose.focusFirstNow
import dev.knshell.compose.retryUntilOnScreen
import dev.knshell.compose.PLAIN_CLASS
import dev.knshell.compose.PreviousFocus
import dev.knshell.compose.launchOnUiThread
import dev.knshell.compose.plain
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.delay
import kn.gtk.GTK_ALIGN_CENTER
import kn.gtk.GTK_ALIGN_END
import kn.gtk.GTK_ALIGN_START
import kn.gtk.GtkOrientation
import kn.gtk.GtkPopover
import kn.gtk.GtkPositionType
import kn.gtk.GtkWidget
import kn.gtk.g_signal_connect_data
import kn.gtk.gtk_box_append
import kn.gtk.gtk_box_insert_child_after
import kn.gtk.gtk_box_new
import kn.gtk.gtk_box_remove
import kn.gtk.gtk_popover_new
import kn.gtk.gtk_popover_popdown
import kn.gtk.gtk_popover_popup
import kn.gtk.gtk_popover_set_autohide
import kn.gtk.gtk_popover_set_child
import kn.gtk.gtk_popover_set_has_arrow
import kn.gtk.gtk_popover_set_position
import kn.gtk.gtk_widget_add_css_class
import kn.gtk.gtk_widget_set_halign
import kn.gtk.gtk_widget_set_parent
import kn.gtk.gtk_widget_set_valign
import kn.gtk.gtk_widget_unparent

/** On which side of the anchor a popup opens. */
enum class PopupSide { Top, Bottom, Left, Right }

/** Which edge of the popup lines up with the anchor. */
enum class PopupAlign { Start, Center, End }

/**
 * [content] in a popup surface next to [anchor], shown while [open]. The surface may reach beyond
 * the window, so it works from a panel. Its content sees the composition locals of this place.
 *
 * A [dismissible] popup takes the keyboard; Escape or a click outside closes it, which calls
 * [onDismiss] so the caller can set [open] to false. The focus then returns to where it was.
 * [autoFocus] focuses the first focusable widget of the content when it opens.
 */
@Composable
fun Popup(
    open: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    side: PopupSide = PopupSide.Bottom,
    align: PopupAlign = PopupAlign.Start,
    autoFocus: Boolean = true,
    dismissible: Boolean = true,
    content: @Composable () -> Unit,
    anchor: @Composable () -> Unit,
) {
    val context = rememberCompositionContext()
    ComposeNode<GtkPopupNode, GtkApplier>(
        factory = { GtkPopupNode().plain() },
        update = {
            set(context) { parentContext = it }
            set(content) { contentState.value = it }
            set(onDismiss) { this.onDismiss = it }
            set(side) { place(it, popupAlign) }
            set(align) { place(popupSide, it) }
            set(autoFocus) { focusesContent = it }
            set(dismissible) { setDismissible(it) }
            set(modifier) { applyModifier(it) }
            // Last: it uses everything above.
            set(open) { setOpen(it) }
        },
        content = anchor,
    )
}

internal class GtkPopupNode : GtkNode(gtk_box_new(GtkOrientation.GTK_ORIENTATION_HORIZONTAL, 0)!!) {
    var parentContext: CompositionContext? = null
    var onDismiss: () -> Unit = {}
    var focusesContent = true
    val contentState = mutableStateOf<@Composable () -> Unit>({})
    var popupSide = PopupSide.Bottom
        private set
    var popupAlign = PopupAlign.Start
        private set

    private val popover: CPointer<GtkWidget> = checkNotNull(gtk_popover_new())
    private val contentBox: CPointer<GtkWidget> = checkNotNull(gtk_box_new(GtkOrientation.GTK_ORIENTATION_VERTICAL, 0))
    private var composition: Composition? = null
    private var contentRoot: GtkNode? = null
    private var isOpen = false
    private var released = false

    /** Where the focus was when the popup opened, to give it back on close. */
    private var previousFocus: PreviousFocus? = null
    private val selfRef = StableRef.create(this)

    /** The anchor, and the content while it is shown, so that the inspector sees both. */
    override val inspectableChildren: List<GtkNode>
        get() = children + listOfNotNull(contentRoot.takeIf { isOpen })

    init {
        val typed = popover.reinterpret<GtkPopover>()
        gtk_widget_add_css_class(contentBox, PLAIN_CLASS)
        gtk_widget_add_css_class(popover, PLAIN_CLASS)
        gtk_widget_add_css_class(popover, POPOVER_CLASS)
        installPopoverCss(popover)
        gtk_popover_set_child(typed, contentBox)
        gtk_popover_set_has_arrow(typed, 0)
        gtk_widget_set_parent(popover, widget)
        val closed = staticCFunction { _: COpaquePointer?, data: COpaquePointer? ->
            data?.asStableRef<GtkPopupNode>()?.get()?.closedByGtk()
            Unit
        }
        g_signal_connect_data(popover, "closed", closed.reinterpret(), selfRef.asCPointer(), null, 0u)
        place(popupSide, popupAlign)
    }

    fun place(side: PopupSide, align: PopupAlign) {
        popupSide = side
        popupAlign = align
        val position = when (side) {
            PopupSide.Top -> GtkPositionType.GTK_POS_TOP
            PopupSide.Bottom -> GtkPositionType.GTK_POS_BOTTOM
            PopupSide.Left -> GtkPositionType.GTK_POS_LEFT
            PopupSide.Right -> GtkPositionType.GTK_POS_RIGHT
        }
        gtk_popover_set_position(popover.reinterpret(), position)
        val edge = when (align) {
            PopupAlign.Start -> GTK_ALIGN_START
            PopupAlign.Center -> GTK_ALIGN_CENTER
            PopupAlign.End -> GTK_ALIGN_END
        }
        val horizontal = side == PopupSide.Top || side == PopupSide.Bottom
        gtk_widget_set_halign(popover, if (horizontal) edge else GTK_ALIGN_CENTER)
        gtk_widget_set_valign(popover, if (horizontal) GTK_ALIGN_CENTER else edge)
    }

    fun setDismissible(dismissible: Boolean) {
        gtk_popover_set_autohide(popover.reinterpret(), if (dismissible) 1 else 0)
    }

    fun setOpen(open: Boolean) {
        if (open == isOpen || released) return
        isOpen = open
        if (!open) {
            // Closed by the caller: the focus goes back only if it is still inside the popup.
            val hadFocus = contentBox.containsFocus()
            gtk_popover_popdown(popover.reinterpret())
            disposeContent()
            previousFocus?.restore(hadFocus)
            previousFocus = null
            return
        }
        previousFocus = PreviousFocus()
        val mounted = ShellRuntime.instance().mountCompose(contentBox, parentContext) { contentState.value() }
        composition = mounted.composition
        contentRoot = mounted.root
        launchOnUiThread {
            // The popover sizes itself from its content, which needs a frame to exist.
            delay(FRAME_MS)
            if (!isOpen || released) return@launchOnUiThread
            gtk_popover_popup(popover.reinterpret())
            if (focusesContent) {
                retryUntilOnScreen { !isOpen || released || contentBox.containsFocus() || focusFirstNow(contentBox) }
            }
        }
    }

    private fun closedByGtk() {
        if (!isOpen) return
        isOpen = false
        disposeContent()
        // Escape or a click outside: GTK loses track once the content is gone.
        previousFocus?.restore(true)
        previousFocus = null
        onDismiss()
    }

    private fun disposeContent() {
        contentRoot?.cancelAnimations()
        contentRoot = null
        composition?.dispose()
        composition = null
    }

    override fun cancelAnimations() {
        if (!released) {
            released = true
            if (isOpen) {
                isOpen = false
                gtk_popover_popdown(popover.reinterpret())
            }
            disposeContent()
            previousFocus?.restore(false)
            previousFocus = null
            gtk_widget_unparent(popover)
        }
        super.cancelAnimations()
    }

    override fun attachChildAt(index: Int, child: CPointer<GtkWidget>) {
        gtk_box_insert_child_after(widget.reinterpret(), child, children.getOrNull(index - 1)?.widget)
    }

    override fun attachChild(child: CPointer<GtkWidget>) = gtk_box_append(widget.reinterpret(), child)

    override fun detachChild(child: CPointer<GtkWidget>) = gtk_box_remove(widget.reinterpret(), child)

    private companion object {
        const val FRAME_MS = 16L
    }
}
