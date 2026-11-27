// ---------------------------------------------------------------------------
// Accessibility — the role, name, description, states and relations a widget tells assistive
// technology (GtkAccessible, modelled on WAI-ARIA, exported over AT-SPI).
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kn.gtk.GValue
import kn.gtk.GtkAccessible
import kn.gtk.GtkAccessibleAnnouncementPriority
import kn.gtk.GtkAccessibleInvalidState
import kn.gtk.GtkAccessibleProperty
import kn.gtk.GtkAccessibleRelation
import kn.gtk.GtkAccessibleRole
import kn.gtk.GtkAccessibleState
import kn.gtk.GtkAccessibleTristate
import kn.gtk.GtkOrientation
import kn.gtk.GtkWidget
import kn.gtk.g_object_set_property
import kn.gtk.g_type_from_name
import kn.gtk.g_type_fundamental
import kn.gtk.g_type_name
import kn.gtk.g_value_init
import kn.gtk.g_value_set_boolean
import kn.gtk.g_value_set_double
import kn.gtk.g_value_set_enum
import kn.gtk.g_value_set_int
import kn.gtk.g_value_set_string
import kn.gtk.g_value_unset
import kn.gtk.gtk_accessible_announce
import kn.gtk.gtk_accessible_property_init_value
import kn.gtk.gtk_accessible_reset_property
import kn.gtk.gtk_accessible_reset_relation
import kn.gtk.gtk_accessible_reset_state
import kn.gtk.gtk_accessible_role_get_type
import kn.gtk.gtk_accessible_state_init_value
import kn.gtk.gtk_accessible_update_property_value
import kn.gtk.gtk_accessible_update_relation
import kn.gtk.gtk_accessible_update_state_value

/** The WAI-ARIA roles a widget can take, named as in ARIA. */
enum class Role(internal val gtk: GtkAccessibleRole) {
    Alert(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_ALERT),
    AlertDialog(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_ALERT_DIALOG),
    Article(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_ARTICLE),
    Button(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_BUTTON),
    Checkbox(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_CHECKBOX),
    Combobox(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_COMBO_BOX),
    Dialog(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_DIALOG),
    Group(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_GROUP),
    Heading(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_HEADING),
    Img(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_IMG),
    Link(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_LINK),
    List(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_LIST),
    ListBox(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_LIST_BOX),
    ListItem(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_LIST_ITEM),
    Menu(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_MENU),
    MenuItem(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_MENU_ITEM),
    MenuItemCheckbox(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_MENU_ITEM_CHECKBOX),
    MenuItemRadio(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_MENU_ITEM_RADIO),
    Option(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_OPTION),
    Presentation(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_PRESENTATION),
    ProgressBar(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_PROGRESS_BAR),
    Radio(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_RADIO),
    RadioGroup(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_RADIO_GROUP),
    Region(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_REGION),
    Separator(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_SEPARATOR),
    Slider(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_SLIDER),
    Status(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_STATUS),
    Switch(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_SWITCH),
    Tab(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_TAB),
    TabList(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_TAB_LIST),
    TabPanel(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_TAB_PANEL),
    TextBox(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_TEXT_BOX),
    Tooltip(GtkAccessibleRole.GTK_ACCESSIBLE_ROLE_TOOLTIP),
}

/** `aria-checked` and `aria-pressed`: yes, no, or partly. */
enum class Tristate { False, True, Mixed }

enum class Orientation { Horizontal, Vertical }

/**
 * What a widget says about itself. Every field that is `null` is left as GTK has it (a native
 * widget keeps its own role and states). Names follow ARIA: [label] is `aria-label`,
 * [labelledBy] is `aria-labelledby` and so on.
 */
data class Semantics(
    val role: Role? = null,
    val label: String? = null,
    val description: String? = null,
    val checked: Tristate? = null,
    val pressed: Tristate? = null,
    val expanded: Boolean? = null,
    val selected: Boolean? = null,
    val disabled: Boolean? = null,
    val busy: Boolean? = null,
    val hidden: Boolean? = null,
    val invalid: Boolean? = null,
    val required: Boolean? = null,
    val modal: Boolean? = null,
    val hasPopup: Boolean? = null,
    val orientation: Orientation? = null,
    val valueNow: Double? = null,
    val valueMin: Double? = null,
    val valueMax: Double? = null,
    val valueText: String? = null,
    val labelledBy: SemanticsRef? = null,
    val describedBy: SemanticsRef? = null,
    val controls: SemanticsRef? = null,
    val activeDescendant: SemanticsRef? = null,
) {
    /** This with every field [other] sets taken from [other]. */
    internal fun mergedWith(other: Semantics) = Semantics(
        other.role ?: role, other.label ?: label, other.description ?: description,
        other.checked ?: checked, other.pressed ?: pressed, other.expanded ?: expanded,
        other.selected ?: selected, other.disabled ?: disabled, other.busy ?: busy, other.hidden ?: hidden,
        other.invalid ?: invalid, other.required ?: required, other.modal ?: modal, other.hasPopup ?: hasPopup,
        other.orientation ?: orientation, other.valueNow ?: valueNow, other.valueMin ?: valueMin,
        other.valueMax ?: valueMax, other.valueText ?: valueText, other.labelledBy ?: labelledBy,
        other.describedBy ?: describedBy, other.controls ?: controls,
        other.activeDescendant ?: activeDescendant,
    )
}

/**
 * A handle on one widget, so that others can name it in a relation (`labelledBy`, `controls`) and so
 * that it can [announce]. Bind it with `Modifier.semanticsRef`, one widget per handle.
 */
class SemanticsRef {
    internal var widget: CPointer<GtkWidget>? = null
        private set

    /** The widgets whose relations point here; told when the widget behind the handle changes. */
    private val dependents = mutableSetOf<SemanticsState>()

    internal fun bind(widget: CPointer<GtkWidget>?) {
        if (this.widget == widget) return
        this.widget = widget
        dependents.toList().forEach { it.applyRelations() }
    }

    internal fun addDependent(state: SemanticsState) {
        dependents += state
    }

    internal fun removeDependent(state: SemanticsState) {
        dependents -= state
    }

    /**
     * Has a screen reader speak [message] now. [assertive] interrupts what it is saying. Does nothing
     * while no widget is bound.
     */
    fun announce(message: String, assertive: Boolean = false) {
        val target = widget ?: return
        val priority = if (assertive) {
            GtkAccessibleAnnouncementPriority.GTK_ACCESSIBLE_ANNOUNCEMENT_PRIORITY_HIGH
        } else {
            GtkAccessibleAnnouncementPriority.GTK_ACCESSIBLE_ANNOUNCEMENT_PRIORITY_MEDIUM
        }
        gtk_accessible_announce(target.reinterpret(), message, priority)
    }
}

internal data class SemanticsElement(val semantics: Semantics) : ModifierElement

internal data class SemanticsRefElement(val ref: SemanticsRef) : ModifierElement

/** Tells assistive technology what this widget is. Later calls in a chain win field by field. */
fun Modifier.semantics(semantics: Semantics): Modifier = with(SemanticsElement(semantics))

/** Binds [ref] to this widget. */
fun Modifier.semanticsRef(ref: SemanticsRef): Modifier = with(SemanticsRefElement(ref))

/** Everything the chain says, merged; `null` when it says nothing. */
internal fun Modifier.mergedSemantics(): Semantics? =
    elements.filterIsInstance<SemanticsElement>().map { it.semantics }.reduceOrNull(Semantics::mergedWith)

private val INT_STATES = setOf(
    GtkAccessibleState.GTK_ACCESSIBLE_STATE_CHECKED,
    GtkAccessibleState.GTK_ACCESSIBLE_STATE_PRESSED,
    GtkAccessibleState.GTK_ACCESSIBLE_STATE_INVALID,
)

/** Writes the semantics of one widget into GTK and takes back what a later chain no longer says. */
internal class SemanticsState(private val widget: CPointer<GtkWidget>) {
    private var applied: Semantics = Semantics()
    private var ref: SemanticsRef? = null

    private val accessible: CPointer<GtkAccessible> get() = widget.reinterpret()

    fun bind(semantics: Semantics?, newRef: SemanticsRef?) {
        if (newRef !== ref) {
            ref?.bind(null)
            ref = newRef
        }
        newRef?.bind(widget)
        val now = semantics ?: Semantics()
        if (now == applied) return
        val before = applied
        applied = now

        if (now.role != null && now.role != before.role) setRole(now.role)

        text(GtkAccessibleProperty.GTK_ACCESSIBLE_PROPERTY_LABEL, before.label, now.label)
        text(GtkAccessibleProperty.GTK_ACCESSIBLE_PROPERTY_DESCRIPTION, before.description, now.description)
        text(GtkAccessibleProperty.GTK_ACCESSIBLE_PROPERTY_VALUE_TEXT, before.valueText, now.valueText)
        number(GtkAccessibleProperty.GTK_ACCESSIBLE_PROPERTY_VALUE_NOW, before.valueNow, now.valueNow)
        number(GtkAccessibleProperty.GTK_ACCESSIBLE_PROPERTY_VALUE_MIN, before.valueMin, now.valueMin)
        number(GtkAccessibleProperty.GTK_ACCESSIBLE_PROPERTY_VALUE_MAX, before.valueMax, now.valueMax)
        flagProperty(GtkAccessibleProperty.GTK_ACCESSIBLE_PROPERTY_MODAL, before.modal, now.modal)
        flagProperty(GtkAccessibleProperty.GTK_ACCESSIBLE_PROPERTY_REQUIRED, before.required, now.required)
        flagProperty(GtkAccessibleProperty.GTK_ACCESSIBLE_PROPERTY_HAS_POPUP, before.hasPopup, now.hasPopup)
        property(
            GtkAccessibleProperty.GTK_ACCESSIBLE_PROPERTY_ORIENTATION,
            before.orientation,
            now.orientation,
        ) { value, orientation ->
            val gtk = if (orientation == Orientation.Vertical) {
                GtkOrientation.GTK_ORIENTATION_VERTICAL
            } else {
                GtkOrientation.GTK_ORIENTATION_HORIZONTAL
            }
            g_value_set_enum(value, gtk.value.toInt())
        }

        flagState(GtkAccessibleState.GTK_ACCESSIBLE_STATE_EXPANDED, before.expanded, now.expanded)
        flagState(GtkAccessibleState.GTK_ACCESSIBLE_STATE_SELECTED, before.selected, now.selected)
        flagState(GtkAccessibleState.GTK_ACCESSIBLE_STATE_DISABLED, before.disabled, now.disabled)
        flagState(GtkAccessibleState.GTK_ACCESSIBLE_STATE_BUSY, before.busy, now.busy)
        flagState(GtkAccessibleState.GTK_ACCESSIBLE_STATE_HIDDEN, before.hidden, now.hidden)
        tristate(GtkAccessibleState.GTK_ACCESSIBLE_STATE_CHECKED, before.checked, now.checked)
        tristate(GtkAccessibleState.GTK_ACCESSIBLE_STATE_PRESSED, before.pressed, now.pressed)
        state(GtkAccessibleState.GTK_ACCESSIBLE_STATE_INVALID, before.invalid, now.invalid) { value, invalid ->
            val gtk = if (invalid) {
                GtkAccessibleInvalidState.GTK_ACCESSIBLE_INVALID_TRUE
            } else {
                GtkAccessibleInvalidState.GTK_ACCESSIBLE_INVALID_FALSE
            }
            value.setNumber(gtk.value.toInt())
        }

        listOf(before.labelledBy, before.describedBy, before.controls, before.activeDescendant)
            .forEach { it?.removeDependent(this) }
        applyRelations()
    }

    /** The widget leaves the tree: nothing may keep pointing at it. */
    fun release() {
        listOf(applied.labelledBy, applied.describedBy, applied.controls, applied.activeDescendant)
            .forEach { it?.removeDependent(this) }
        applied = Semantics()
        ref?.bind(null)
        ref = null
    }

    fun applyRelations() {
        relation(GtkAccessibleRelation.GTK_ACCESSIBLE_RELATION_LABELLED_BY, applied.labelledBy, list = true)
        relation(GtkAccessibleRelation.GTK_ACCESSIBLE_RELATION_DESCRIBED_BY, applied.describedBy, list = true)
        relation(GtkAccessibleRelation.GTK_ACCESSIBLE_RELATION_CONTROLS, applied.controls, list = true)
        relation(GtkAccessibleRelation.GTK_ACCESSIBLE_RELATION_ACTIVE_DESCENDANT, applied.activeDescendant, list = false)
    }

    private fun relation(relation: GtkAccessibleRelation, target: SemanticsRef?, list: Boolean) {
        target?.addDependent(this)
        val other = target?.widget
        // The variadic call is the documented one; each relation list ends with NULL, the call with -1.
        when {
            other == null -> gtk_accessible_reset_relation(accessible, relation)
            list -> gtk_accessible_update_relation(accessible, relation, other, null, -1)
            else -> gtk_accessible_update_relation(accessible, relation, other, -1)
        }
    }

    private fun setRole(role: Role) = memScoped {
        val value = alloc<GValue>()
        g_value_init(value.ptr, gtk_accessible_role_get_type())
        g_value_set_enum(value.ptr, role.gtk.value.toInt())
        g_object_set_property(widget.reinterpret(), "accessible-role", value.ptr)
        g_value_unset(value.ptr)
    }

    private fun <T : Any> property(key: GtkAccessibleProperty, before: T?, now: T?, write: (CPointer<GValue>, T) -> Unit) {
        if (before == now) return
        if (now == null) {
            gtk_accessible_reset_property(accessible, key)
            return
        }
        memScoped {
            val name = alloc<GtkAccessibleProperty.Var>()
            name.value = key
            val value = alloc<GValue>()
            gtk_accessible_property_init_value(key, value.ptr)
            write(value.ptr, now)
            gtk_accessible_update_property_value(accessible, 1, name.ptr, value.ptr)
            g_value_unset(value.ptr)
        }
    }

    private fun <T : Any> state(key: GtkAccessibleState, before: T?, now: T?, write: (CPointer<GValue>, T) -> Unit) {
        if (before == now) return
        if (now == null) {
            gtk_accessible_reset_state(accessible, key)
            return
        }
        memScoped {
            val name = alloc<GtkAccessibleState.Var>()
            name.value = key
            val value = alloc<GValue>()
            // GTK initialises these as their enum type but reads them back with `g_value_get_int`.
            if (key in INT_STATES) {
                g_value_init(value.ptr, g_type_from_name("gint"))
            } else {
                gtk_accessible_state_init_value(key, value.ptr)
            }
            write(value.ptr, now)
            gtk_accessible_update_state_value(accessible, 1, name.ptr, value.ptr)
            g_value_unset(value.ptr)
        }
    }

    private fun text(key: GtkAccessibleProperty, before: String?, now: String?) =
        property(key, before, now) { value, text -> g_value_set_string(value, text) }

    private fun number(key: GtkAccessibleProperty, before: Double?, now: Double?) =
        property(key, before, now) { value, number -> g_value_set_double(value, number) }

    private fun flagProperty(key: GtkAccessibleProperty, before: Boolean?, now: Boolean?) =
        property(key, before, now) { value, flag -> value.setNumber(if (flag) 1 else 0) }

    private fun flagState(key: GtkAccessibleState, before: Boolean?, now: Boolean?) =
        state(key, before, now) { value, flag -> value.setNumber(if (flag) 1 else 0) }

    private fun tristate(key: GtkAccessibleState, before: Tristate?, now: Tristate?) =
        state(key, before, now) { value, tristate ->
            val gtk = when (tristate) {
                Tristate.False -> GtkAccessibleTristate.GTK_ACCESSIBLE_TRISTATE_FALSE
                Tristate.True -> GtkAccessibleTristate.GTK_ACCESSIBLE_TRISTATE_TRUE
                Tristate.Mixed -> GtkAccessibleTristate.GTK_ACCESSIBLE_TRISTATE_MIXED
            }
            value.setNumber(gtk.value.toInt())
        }

    /** GTK types its states differently (boolean, int, enum): the initialised value says which. */
    private fun CPointer<GValue>.setNumber(number: Int) {
        val type = pointed.g_type
        when (g_type_name(type)?.toKString()) {
            "gboolean" -> g_value_set_boolean(this, number)
            "gint" -> g_value_set_int(this, number)
            else -> {
                check(g_type_name(g_type_fundamental(type))?.toKString() == "GEnum") {
                    "unexpected accessible value type ${g_type_name(type)?.toKString()}"
                }
                g_value_set_enum(this, number)
            }
        }
    }
}
