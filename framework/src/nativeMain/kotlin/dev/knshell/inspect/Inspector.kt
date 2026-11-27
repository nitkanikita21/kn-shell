// ---------------------------------------------------------------------------
// The inspector: reads the widget tree of the shell's first window, forces interaction on its
// nodes and renders them to PNG. Every action goes through the same GTK signal a user input would
// emit, so the Compose callbacks fire; the node's own `setText`/`setChecked`/`setSelected` wrappers
// are bypassed on purpose, since they suppress those callbacks. Must run on the GTK main thread.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.inspect

import kn.gtk.cairo_version_string
import kn.gtk.g_type_name_from_instance
import kn.gtk.gtk_get_major_version
import kn.gtk.gtk_get_micro_version
import kn.gtk.gtk_get_minor_version
import kn.gtk.gtk_native_get_renderer
import kn.gtk.gtk_widget_get_native
import kn.gtk.pango_version_string
import kotlinx.cinterop.toKString
import dev.knshell.compose.GtkButtonNode
import dev.knshell.compose.GtkButtonContainerNode
import dev.knshell.compose.GtkDropDownNode
import dev.knshell.compose.GtkEntryNode
import dev.knshell.compose.GtkNode
import dev.knshell.compose.GtkSliderNode
import dev.knshell.compose.GtkSwitchNode
import dev.knshell.compose.testTagName
import dev.knshell.shell.ShellRuntime
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kn.gtk.GValue
import kn.gtk.GtkRange
import kn.gtk.GTK_STATE_FLAG_ACTIVE
import kn.gtk.GTK_STATE_FLAG_FOCUSED
import kn.gtk.GTK_STATE_FLAG_FOCUS_VISIBLE
import kn.gtk.GTK_STATE_FLAG_PRELIGHT
import kn.gtk.GtkWidget
import kn.gtk.g_object_get_type
import kn.gtk.g_signal_emitv
import kn.gtk.g_signal_lookup
import kn.gtk.g_value_init
import kn.gtk.g_value_set_object
import kn.gtk.g_value_unset
import kn.gtk.gtk_adjustment_get_lower
import kn.gtk.gtk_adjustment_get_upper
import kn.gtk.gtk_button_get_type
import kn.gtk.gtk_drop_down_set_selected
import kn.gtk.gtk_editable_set_text
import kn.gtk.gtk_range_get_adjustment
import kn.gtk.gtk_range_set_value
import kn.gtk.gtk_switch_set_active
import kn.gtk.gtk_widget_get_height
import kn.gtk.gtk_widget_get_root
import kn.gtk.gtk_widget_get_width
import kn.gtk.gtk_widget_set_state_flags
import kn.gtk.gtk_widget_unset_state_flags

/**
 * Drives one window's widget tree: reads it, acts on it, renders it.
 *
 * [root] yields the composition root of the window (`null` while none is mounted) and [idle] waits
 * until the runtime has settled. Both are lambdas so that creating the inspector never touches GTK.
 */
class Inspector internal constructor(
    private val root: () -> GtkNode?,
    private val idle: suspend () -> Unit,
) {
    /** The whole composition as a tree; ids and testTags are the handles the actions take. */
    fun tree(): InspectNode {
        val rootNode = requireRoot()
        return rootNode.describe(rootNode.widget)
    }

    /**
     * The node named by [target]: a decimal id, or a `testTag`. Throws when nothing matches or when
     * the name is shared by several nodes.
     */
    fun find(target: String): InspectNode {
        val rootNode = requireRoot()
        val matches = rootNode.allNodes().filter { it.isTarget(target) }
        return when (matches.size) {
            0 -> throw InspectException("no node matches \"$target\"")
            1 -> matches.single().describe(rootNode.widget)
            else -> throw InspectException("\"$target\" matches ${matches.size} nodes; use the numeric id")
        }
    }

    /** Clicks a button, or runs the `Modifier.onClick` handler of any other widget. */
    fun click(id: Int) {
        val node = actionable(id)
        when (node) {
            is GtkButtonNode, is GtkButtonContainerNode -> emitClicked(node.widget)
            else -> {
                if (!node.inlineStyle.interaction.hasClickHandler) {
                    throw InspectException("node #$id (${node.typeName()}) has no click handler")
                }
                node.inlineStyle.interaction.invokeClick()
            }
        }
    }

    /** Replaces the text of an Entry; the Entry's `onValueChange` fires as it does for typing. */
    fun setText(id: Int, text: String) {
        val node = typed<GtkEntryNode>(id, "Entry")
        gtk_editable_set_text(node.widget.reinterpret(), text)
    }

    /** Toggles a Switch; the Switch's `onCheckedChange` fires. */
    fun setChecked(id: Int, checked: Boolean) {
        val node = typed<GtkSwitchNode>(id, "Switch")
        gtk_switch_set_active(node.widget.reinterpret(), if (checked) 1 else 0)
    }

    /** Moves a Slider to [value]; values outside its range (or NaN) are rejected. */
    fun setValue(id: Int, value: Float) {
        val node = typed<GtkSliderNode>(id, "Slider")
        val range = node.widget.reinterpret<GtkRange>()
        val adjustment = gtk_range_get_adjustment(range)
        val lower = gtk_adjustment_get_lower(adjustment)
        val upper = gtk_adjustment_get_upper(adjustment)
        if (value.isNaN() || value < lower || value > upper) {
            throw InspectException("value $value is outside the slider range $lower..$upper")
        }
        gtk_range_set_value(range, value.toDouble())
    }

    /** Selects the item at [index] of a DropDown; indices outside its options are rejected. */
    fun select(id: Int, index: Int) {
        val node = typed<GtkDropDownNode>(id, "DropDown")
        if (index !in node.items.indices) {
            throw InspectException("index $index is outside the ${node.items.size} option(s) of node #$id")
        }
        gtk_drop_down_set_selected(node.widget.reinterpret(), index.toUInt())
    }

    /** Forces or releases a pseudo-state: hover (prelight), pressed (active) or keyboard focus. */
    fun setState(id: Int, state: InteractionState, on: Boolean) {
        val node = actionable(id)
        val flags = when (state) {
            InteractionState.Hover -> GTK_STATE_FLAG_PRELIGHT
            InteractionState.Pressed -> GTK_STATE_FLAG_ACTIVE
            InteractionState.Focus -> GTK_STATE_FLAG_FOCUSED or GTK_STATE_FLAG_FOCUS_VISIBLE
        }
        if (on) {
            gtk_widget_set_state_flags(node.widget, flags, 0)
        } else {
            gtk_widget_unset_state_flags(node.widget, flags)
        }
    }

    /**
     * PNG of the window area behind [id], or of the composition root when [id] is null. The area
     * is cropped from the whole window's render, so backgrounds of the node and its neighbours are
     * included.
     */
    fun screenshot(id: Int? = null): ByteArray {
        val rootNode = requireRoot()
        val window = gtk_widget_get_root(rootNode.widget)?.reinterpret<GtkWidget>()
            ?: throw InspectException("the composition root is not inside a window")
        val target = if (id == null) rootNode else actionable(id)
        // A node in a popup lives on a surface of its own, rendered by itself.
        val surface = gtk_widget_get_native(target.widget)?.reinterpret<GtkWidget>() ?: window
        val width = gtk_widget_get_width(surface)
        val height = gtk_widget_get_height(surface)
        if (width <= 0 || height <= 0) throw InspectException("the window has no size yet (${width}x$height)")
        val area = boundsIn(target.widget, surface)
            ?: throw InspectException("node #${target.id} has no position in the window yet")
        if (area.width <= 0 || area.height <= 0) {
            throw InspectException("node #${target.id} has an empty size (${area.width}x${area.height})")
        }
        return renderPng(surface, width, height, area)
    }

    /**
     * What draws the window: the GTK, Pango and cairo versions and the GSK renderer. Pictures from
     * two different sets are not comparable pixel by pixel.
     */
    fun renderEnvironment(): List<Pair<String, String>> {
        val window = gtk_widget_get_root(requireRoot().widget)?.reinterpret<GtkWidget>()
            ?: throw InspectException("the composition root is not inside a window")
        val renderer = gtk_widget_get_native(window)?.let { gtk_native_get_renderer(it) }
            ?: throw InspectException("the window has no renderer yet")
        return listOf(
            "gtk" to "${gtk_get_major_version()}.${gtk_get_minor_version()}.${gtk_get_micro_version()}",
            "pango" to pango_version_string()?.toKString().orEmpty(),
            "cairo" to cairo_version_string()?.toKString().orEmpty(),
            "renderer" to g_type_name_from_instance(renderer.reinterpret())?.toKString().orEmpty(),
        )
    }

    /** Waits until the runtime has settled after an action (pending recomposition, layout, a few frames). */
    suspend fun awaitIdle() {
        idle()
    }

    private fun requireRoot(): GtkNode =
        root() ?: throw InspectException("no window is mounted")

    /** All nodes of the tree, depth first, root included. */
    private fun GtkNode.allNodes(): List<GtkNode> = listOf(this) + inspectableChildren.flatMap { it.allNodes() }

    private fun GtkNode.isTarget(target: String): Boolean =
        id == target.toIntOrNull() || modifier.testTagName() == target

    /** The node with [id], which must be visible and enabled to take an action. */
    private fun actionable(id: Int): GtkNode {
        val node = requireRoot().allNodes().firstOrNull { it.id == id }
            ?: throw InspectException("no node with id $id")
        if (!node.widget.isShown()) throw InspectException("node #$id (${node.typeName()}) is not visible")
        if (!node.widget.isEnabled()) throw InspectException("node #$id (${node.typeName()}) is insensitive")
        return node
    }

    private inline fun <reified T : GtkNode> typed(id: Int, expected: String): T {
        val node = actionable(id)
        return node as? T ?: throw InspectException("node #$id is ${node.typeName()}, not $expected")
    }
}

/** Emits the `clicked` signal on [widget] through `g_signal_emitv`; `g_signal_emit_by_name` is variadic and cannot be called from Kotlin/Native. */
private fun emitClicked(widget: CPointer<GtkWidget>) {
    memScoped {
        val params = alloc<GValue>()
        g_value_init(params.ptr, g_object_get_type())
        g_value_set_object(params.ptr, widget.reinterpret())
        g_signal_emitv(params.ptr, g_signal_lookup("clicked", gtk_button_get_type()), 0u, null)
        g_value_unset(params.ptr)
    }
}

/** The inspector of the process's first window. GTK is only initialised by the first call on the result. */
fun windowInspector(): Inspector = Inspector(
    root = { ShellRuntime.instance().firstWindowRoot() },
    idle = { ShellRuntime.instance().awaitIdle() },
)
