// ---------------------------------------------------------------------------
// Input nodes: the GTK widgets behind `Entry`, `Switch` and `DropDown`. Like the
// content nodes in ComposeBasicNodes.kt they are DSL internals and are only ever
// constructed by the composables in Widgets.kt.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kn.gtk.GtkDropDown
import kn.gtk.GtkEditable
import kn.gtk.GtkEntry
import kn.gtk.GtkSwitch
import kn.gtk.g_object_unref
import kn.gtk.g_signal_connect_data
import kn.gtk.gtk_drop_down_get_selected
import kn.gtk.gtk_drop_down_new
import kn.gtk.gtk_drop_down_set_model
import kn.gtk.gtk_drop_down_set_selected
import kn.gtk.gtk_editable_get_text
import kn.gtk.gtk_editable_set_text
import kn.gtk.gtk_entry_new
import kn.gtk.gtk_entry_set_placeholder_text
import kn.gtk.gtk_string_list_append
import kn.gtk.gtk_string_list_new
import kn.gtk.gtk_switch_get_active
import kn.gtk.gtk_switch_new
import kn.gtk.gtk_switch_set_active

/** `GTK_INVALID_LIST_POSITION` (`(guint) 0xffffffff`): "no item selected". */
private const val INVALID_LIST_POSITION: UInt = 0xffffffffu

/**
 * Single-line text field (`GtkEntry`). [onChange] receives the full text after every
 * edit made by the user; programmatic writes through [setText] never fire it.
 */
internal class GtkEntryNode : GtkNode(gtk_entry_new()!!) {

    var onChange: ((String) -> Unit)? = null

    private var programmaticUpdate = false

    @Suppress("unused")
    private val selfRef = StableRef.create(this)

    init {
        val self = selfRef
        val handler = staticCFunction { _: COpaquePointer?, data: COpaquePointer? ->
            val node = data?.asStableRef<GtkEntryNode>()?.get()
            if (node != null && !node.programmaticUpdate) {
                val text = gtk_editable_get_text(node.widget.reinterpret())?.toKString() ?: ""
                node.onChange?.invoke(text)
            }
            Unit
        }
        g_signal_connect_data(
            instance = widget,
            detailed_signal = "changed",
            c_handler = handler.reinterpret(),
            data = self.asCPointer(),
            destroy_data = null,
            connect_flags = 0u,
        )
    }

    /**
     * Writes [text] only when it differs from what the widget already shows, so that a
     * recomposition triggered by the user's own keystroke does not move the cursor.
     */
    fun setText(text: String) {
        val editable = widget.reinterpret<GtkEditable>()
        val current = gtk_editable_get_text(editable)?.toKString() ?: ""
        if (current == text) return
        programmaticUpdate = true
        gtk_editable_set_text(editable, text)
        programmaticUpdate = false
    }

    fun setPlaceholder(text: String) {
        gtk_entry_set_placeholder_text(widget.reinterpret<GtkEntry>(), text)
    }
}

/**
 * On/off switch (`GtkSwitch`). [onChange] fires on user toggles only; [setChecked]
 * updates the widget without firing it.
 */
internal class GtkSwitchNode : GtkNode(gtk_switch_new()!!) {

    var onChange: ((Boolean) -> Unit)? = null

    private var programmaticUpdate = false

    @Suppress("unused")
    private val selfRef = StableRef.create(this)

    init {
        val self = selfRef
        // notify:: handlers receive (instance, GParamSpec*, user_data): three arguments.
        val handler = staticCFunction { _: COpaquePointer?, _: COpaquePointer?, data: COpaquePointer? ->
            val node = data?.asStableRef<GtkSwitchNode>()?.get()
            if (node != null && !node.programmaticUpdate) {
                val active = gtk_switch_get_active(node.widget.reinterpret()) != 0
                node.onChange?.invoke(active)
            }
            Unit
        }
        g_signal_connect_data(
            instance = widget,
            detailed_signal = "notify::active",
            c_handler = handler.reinterpret(),
            data = self.asCPointer(),
            destroy_data = null,
            connect_flags = 0u,
        )
    }

    fun setChecked(checked: Boolean) {
        programmaticUpdate = true
        gtk_switch_set_active(widget.reinterpret<GtkSwitch>(), if (checked) 1 else 0)
        programmaticUpdate = false
    }
}

/**
 * Drop-down selector (`GtkDropDown`) over a `GtkStringList`. [onChange] receives the
 * index of the item the user picked; programmatic changes never fire it.
 *
 * Replacing the list resets GTK's selection, so [setItems] re-applies the last index
 * requested through [setSelected]. Indices outside the current list are ignored.
 */
internal class GtkDropDownNode : GtkNode(gtk_drop_down_new(null, null)!!) {

    var onChange: ((Int) -> Unit)? = null

    private var programmaticUpdate = false

    /** Items of the list currently set on the dropdown, in order; read by the inspector. */
    internal var items: List<String> = emptyList()
        private set

    /** The last index requested by the composition; re-applied after [setItems]. */
    private var requestedSelected = 0

    @Suppress("unused")
    private val selfRef = StableRef.create(this)

    init {
        val self = selfRef
        // notify:: handlers receive (instance, GParamSpec*, user_data): three arguments.
        val handler = staticCFunction { _: COpaquePointer?, _: COpaquePointer?, data: COpaquePointer? ->
            val node = data?.asStableRef<GtkDropDownNode>()?.get()
            if (node != null && !node.programmaticUpdate) {
                val position = gtk_drop_down_get_selected(node.widget.reinterpret())
                if (position != INVALID_LIST_POSITION) {
                    node.onChange?.invoke(position.toInt())
                }
            }
            Unit
        }
        g_signal_connect_data(
            instance = widget,
            detailed_signal = "notify::selected",
            c_handler = handler.reinterpret(),
            data = self.asCPointer(),
            destroy_data = null,
            connect_flags = 0u,
        )
    }

    fun setItems(items: List<String>) {
        programmaticUpdate = true
        val list = gtk_string_list_new(null)!!
        items.forEach { gtk_string_list_append(list, it) }
        gtk_drop_down_set_model(widget.reinterpret<GtkDropDown>(), list.reinterpret())
        // gtk_drop_down_set_model takes its own reference; release ours so the dropdown
        // is the only owner of the model.
        g_object_unref(list.reinterpret())
        this.items = items
        applySelected(requestedSelected)
        programmaticUpdate = false
    }

    fun setSelected(index: Int) {
        requestedSelected = index
        programmaticUpdate = true
        applySelected(index)
        programmaticUpdate = false
    }

    private fun applySelected(index: Int) {
        if (index < 0 || index >= items.size) return
        gtk_drop_down_set_selected(widget.reinterpret<GtkDropDown>(), index.toUInt())
    }
}
