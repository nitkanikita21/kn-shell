// ---------------------------------------------------------------------------
// The public data of the inspector: one `InspectNode` per widget node, its bounds, the interaction
// states it can be forced into, and the exception every rejected request throws. The conversion
// from a live `GtkNode` to an `InspectNode` lives here too; it reads GTK state only, never writes.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.inspect

import dev.knshell.compose.Semantics
import dev.knshell.compose.SemanticsRef
import dev.knshell.compose.mergedSemantics
import kn.gtk.g_type_name_from_instance
import kn.gtk.gtk_accessible_get_accessible_role
import kn.gtk.gtk_widget_get_first_child
import kn.gtk.gtk_widget_get_next_sibling
import kn.gtk.gtk_widget_get_root
import kn.gtk.gtk_window_get_focus
import kn.gtk.gtk_widget_is_ancestor
import dev.knshell.compose.GtkButtonNode
import dev.knshell.compose.GtkDropDownNode
import dev.knshell.compose.GtkEntryNode
import dev.knshell.compose.GtkLabelNode
import dev.knshell.compose.GtkNode
import dev.knshell.compose.GtkSliderNode
import dev.knshell.compose.GtkSwitchNode
import dev.knshell.compose.testTagName
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kn.gtk.GtkEditable
import kn.gtk.GtkLabel
import kn.gtk.GtkWidget
import kn.gtk.graphene_rect_t
import kn.gtk.g_strfreev
import kn.gtk.gtk_button_get_label
import kn.gtk.gtk_drop_down_get_selected
import kn.gtk.gtk_editable_get_text
import kn.gtk.gtk_label_get_text
import kn.gtk.gtk_range_get_value
import kn.gtk.gtk_switch_get_active
import kn.gtk.gtk_widget_compute_bounds
import kn.gtk.gtk_widget_get_css_classes
import kn.gtk.gtk_widget_is_sensitive
import kn.gtk.gtk_widget_is_visible
import kotlin.math.roundToInt

/** Rectangle of a node in pixels; `x`/`y` are relative to the frame the node was measured in. */
data class NodeBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * One widget of the composition as the inspector reports it.
 *
 * [type] is the node class name without the `Gtk` prefix and `Node` suffix (`Box`, `Button`,
 * `ButtonContainer`, `Icon`). The optional fields are filled only for the widgets they describe:
 * [text] for labels, buttons and entries; [checked] for switches; [value] for sliders; [selected]
 * and [options] for drop-downs. [bounds] is relative to the composition root.
 */
data class InspectNode(
    val id: Int,
    val type: String,
    val testTag: String?,
    val bounds: NodeBounds,
    val cssClasses: List<String>,
    val text: String?,
    val checked: Boolean?,
    val value: Float?,
    val selected: Int?,
    val options: List<String>?,
    val visible: Boolean,
    val sensitive: Boolean,
    val children: List<InspectNode>,
    /** The ARIA role GTK reports, such as `tab` or `menuitem`. */
    val role: String = "generic",
    /** The accessible name: what a screen reader calls the widget. */
    val name: String? = null,
    val description: String? = null,
    /** ARIA states and values the widget declares, such as `selected` to `true`. */
    val states: Map<String, String> = emptyMap(),
    /** Whether the keyboard focus is on this widget (or inside it, for a widget without child nodes). */
    val focused: Boolean = false,
)

/** The interaction states [Inspector.setState] can force on a widget. */
enum class InteractionState { Hover, Pressed, Focus }

/** A request the inspector refused: unknown or ambiguous target, wrong type, hidden node, bad value. */
class InspectException(message: String) : Exception(message)

/** Type name of the node: the class name without the `Gtk` prefix and the `Node` suffix. */
internal fun GtkNode.typeName(): String =
    this::class.simpleName.orEmpty().removePrefix("Gtk").removeSuffix("Node")

/**
 * Describes this node and its children; [root] is the composition root, the frame [NodeBounds] are
 * measured in. Bounds of a node GTK cannot measure (not mapped yet) are reported as zero.
 */
internal fun GtkNode.describe(root: CPointer<GtkWidget>): InspectNode = InspectNode(
    id = id,
    type = typeName(),
    testTag = modifier.testTagName(),
    bounds = boundsIn(widget, root) ?: NodeBounds(0, 0, 0, 0),
    cssClasses = cssClassesOf(widget),
    text = textOf(),
    checked = (this as? GtkSwitchNode)?.let { gtk_switch_get_active(it.widget.reinterpret()) != 0 },
    value = (this as? GtkSliderNode)?.let { gtk_range_get_value(it.widget.reinterpret()).toFloat() },
    selected = (this as? GtkDropDownNode)?.let { selectedIndex(it.widget) },
    options = (this as? GtkDropDownNode)?.items,
    visible = gtk_widget_is_visible(widget) != 0,
    sensitive = gtk_widget_is_sensitive(widget) != 0,
    children = inspectableChildren.map { it.describe(root) },
    role = roleOf(widget),
    name = accessibleName(),
    description = modifier.mergedSemantics()?.let { it.description ?: it.describedBy?.text() },
    states = modifier.mergedSemantics()?.states().orEmpty(),
    focused = isFocused(),
)

/** `GTK_ACCESSIBLE_ROLE_MENU_ITEM` as ARIA writes it: `menuitem`. */
private fun roleOf(widget: CPointer<GtkWidget>): String =
    gtk_accessible_get_accessible_role(widget.reinterpret()).name.removePrefix("GTK_ACCESSIBLE_ROLE_")
        .replace("_", "").lowercase()

/** The roles that take their name from the text inside them. */
private val NAMED_BY_CONTENT = setOf(
    "button", "tab", "menuitem", "menuitemcheckbox", "menuitemradio", "checkbox", "switch", "radio",
    "option", "link", "tooltip", "heading", "togglebutton",
)

/** `aria-label`, else the labelling widget's text, else the widget's own or contained text. */
private fun GtkNode.accessibleName(): String? {
    val semantics = modifier.mergedSemantics()
    semantics?.label?.let { return it }
    semantics?.labelledBy?.text()?.let { return it }
    textOf()?.takeUnless { this is GtkEntryNode }?.let { return it }
    if (roleOf(widget) !in NAMED_BY_CONTENT) return null
    return contentText().takeIf { it.isNotEmpty() }
}

private fun GtkNode.contentText(): String =
    (listOfNotNull(textOf()) + children.map { it.contentText() }).filter { it.isNotEmpty() }.joinToString(" ")

/** The text of the label behind a handle, or of the first label inside it. */
private fun SemanticsRef.text(): String? = widget?.let(::labelText)

private fun labelText(widget: CPointer<GtkWidget>): String? {
    val type = typeNameOf(widget)
    if (type == "GtkLabel") return gtk_label_get_text(widget.reinterpret<GtkLabel>())?.toKString()
    var child = gtk_widget_get_first_child(widget)
    while (child != null) {
        labelText(child)?.let { return it }
        child = gtk_widget_get_next_sibling(child)
    }
    return null
}

private fun typeNameOf(widget: CPointer<GtkWidget>): String? =
    g_type_name_from_instance(widget.reinterpret())?.toKString()

private fun Semantics.states(): Map<String, String> = listOfNotNull(
    checked?.let { "checked" to it.name.lowercase() },
    pressed?.let { "pressed" to it.name.lowercase() },
    expanded?.let { "expanded" to it.toString() },
    selected?.let { "selected" to it.toString() },
    disabled?.let { "disabled" to it.toString() },
    busy?.let { "busy" to it.toString() },
    hidden?.let { "hidden" to it.toString() },
    invalid?.let { "invalid" to it.toString() },
    required?.let { "required" to it.toString() },
    modal?.let { "modal" to it.toString() },
    hasPopup?.let { "haspopup" to it.toString() },
    orientation?.let { "orientation" to it.name.lowercase() },
    valueNow?.let { "valuenow" to it.toString() },
    valueMin?.let { "valuemin" to it.toString() },
    valueMax?.let { "valuemax" to it.toString() },
    valueText?.let { "valuetext" to it },
).toMap()

private fun GtkNode.isFocused(): Boolean {
    val window = gtk_widget_get_root(widget) ?: return false
    val focus = gtk_window_get_focus(window.reinterpret()) ?: return false
    return focus == widget || (inspectableChildren.isEmpty() && gtk_widget_is_ancestor(focus, widget) != 0)
}

/** Whether GTK shows the widget: it and every ancestor are visible. */
internal fun CPointer<GtkWidget>.isShown(): Boolean = gtk_widget_is_visible(this) != 0

/** Whether the widget and its ancestors accept input. */
internal fun CPointer<GtkWidget>.isEnabled(): Boolean = gtk_widget_is_sensitive(this) != 0

/**
 * Rectangle of [widget] in the coordinate space of [relativeTo], or `null` when GTK cannot measure
 * it (the widget is not in the same tree or has not been allocated).
 */
internal fun boundsIn(widget: CPointer<GtkWidget>, relativeTo: CPointer<GtkWidget>): NodeBounds? = memScoped {
    val rect = alloc<graphene_rect_t>()
    if (gtk_widget_compute_bounds(widget, relativeTo, rect.ptr) == 0) return@memScoped null
    NodeBounds(
        x = rect.origin.x.roundToInt(),
        y = rect.origin.y.roundToInt(),
        width = rect.size.width.roundToInt(),
        height = rect.size.height.roundToInt(),
    )
}

/** CSS classes of the widget; GTK hands over a freshly allocated, NULL-terminated array. */
private fun cssClassesOf(widget: CPointer<GtkWidget>): List<String> {
    val raw = gtk_widget_get_css_classes(widget) ?: return emptyList()
    try {
        val classes = mutableListOf<String>()
        var index = 0
        while (true) {
            val entry = raw[index] ?: break
            classes += entry.toKString()
            index++
        }
        return classes
    } finally {
        g_strfreev(raw)
    }
}

/** The text a node shows, for the widget kinds that have one. */
private fun GtkNode.textOf(): String? = when (this) {
    is GtkLabelNode -> gtk_label_get_text(widget.reinterpret<GtkLabel>())?.toKString()
    is GtkButtonNode -> gtk_button_get_label(widget.reinterpret())?.toKString()
    is GtkEntryNode -> gtk_editable_get_text(widget.reinterpret<GtkEditable>())?.toKString()
    else -> null
}

/** Selected index of a drop-down, or `null` when GTK reports "no item selected". */
private fun selectedIndex(widget: CPointer<GtkWidget>): Int? {
    val position = gtk_drop_down_get_selected(widget.reinterpret())
    return if (position == NO_SELECTION) null else position.toInt()
}

/** `GTK_INVALID_LIST_POSITION`: `(guint) -1`. */
private const val NO_SELECTION: UInt = 0xffffffffu
