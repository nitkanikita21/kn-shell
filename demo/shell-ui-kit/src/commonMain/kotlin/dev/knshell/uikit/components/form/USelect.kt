package dev.knshell.uikit.components.form

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.Align
import dev.knshell.compose.Button
import dev.knshell.compose.Color
import dev.knshell.compose.Column
import dev.knshell.compose.FocusRequester
import dev.knshell.compose.Icon
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.align
import dev.knshell.compose.enabled
import dev.knshell.compose.focusRequester
import dev.knshell.compose.hExpand
import dev.knshell.compose.minHeight
import dev.knshell.compose.minWidth
import dev.knshell.compose.onKey
import dev.knshell.compose.padding
import dev.knshell.compose.semantics
import dev.knshell.compose.weight
import dev.knshell.icons.lucide.Lucide
import dev.knshell.shell.Popup
import dev.knshell.uikit.a11y.LocalUField
import dev.knshell.uikit.a11y.URovingState
import dev.knshell.uikit.a11y.UTypeahead
import dev.knshell.uikit.a11y.rememberURoving
import dev.knshell.uikit.a11y.rememberUTypeahead
import dev.knshell.uikit.a11y.uFieldControl
import dev.knshell.uikit.a11y.uInvalid
import dev.knshell.uikit.a11y.uRovingItem
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.UOrientation
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.USizeMetrics
import dev.knshell.uikit.core.UVariantStyle
import dev.knshell.uikit.core.colorSet
import dev.knshell.uikit.core.metrics
import dev.knshell.uikit.core.uDimmed
import dev.knshell.uikit.core.uFocusRing
import dev.knshell.uikit.core.uFocusFill
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.core.uVariant
import dev.knshell.uikit.core.withAlpha
import dev.knshell.uikit.overlay.UPopupSurface
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** Slots of [USelect] an application can restyle. */
data class USelectUi(
    /** The trigger: the box that shows the value. */
    val base: Modifier = Modifier,
    /** The list that opens under the trigger. */
    val list: Modifier = Modifier,
    val option: Modifier = Modifier,
    /** The option that is chosen, after [option]. */
    val selectedOption: Modifier = Modifier,
)

data class USelectDefaults(
    val color: UColor = UColor.Primary,
    val size: USize = USize.Md,
    val ui: USelectUi = USelectUi(),
) : UComponentDefaults

/** The select defaults of the current theme. */
@Composable
fun uSelectDefaults(): USelectDefaults = UTheme.config.find() ?: USelectDefaults()

private const val MIN_WIDTH = 160
private const val MIN_LIST_WIDTH = 180
private const val LIST_GAP = 2

/**
 * A choice of one value from [items], shown in [label] form, in a list that opens under the trigger.
 * Select-only: the value is never typed.
 *
 * Enter, Space and a click open it, as do Down and Up. Inside the list the arrows and Home and End
 * move the active option, a letter jumps to the next option whose label starts with it, Enter or
 * Space choose it and close the list, and Tab chooses the active option and goes on from the
 * trigger. Escape closes without a change. Without a visible text, the trigger needs
 * [accessibleLabel], unless a `UFormField` names it.
 */
@Composable
fun <T : Any> USelect(
    items: List<T>,
    value: T?,
    onValueChange: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    color: UColor = uSelectDefaults().color,
    size: USize = uSelectDefaults().size,
    disabled: Boolean = false,
    invalid: Boolean = false,
    accessibleLabel: String? = null,
    ui: USelectUi = USelectUi(),
) {
    require(accessibleLabel != null || LocalUField.current != null) {
        "a USelect outside a UFormField needs an accessibleLabel"
    }
    val tokens = UTheme.tokens
    val themed = uSelectDefaults().ui
    val metrics = size.metrics
    val set = tokens.colors.colorSet(color)
    val field = LocalUField.current
    val isInvalid = uInvalid(invalid)
    var open by remember { mutableStateOf(false) }
    val triggerRequester = remember { FocusRequester() }
    // Inside a field the control is the field's own requester, which a click on the label focuses.
    val triggerFocus = field?.control ?: triggerRequester
    val roving = rememberURoving<Int>()
    val typeahead = rememberUTypeahead()

    val labels = items.map { label(it) }
    val selected = value?.let { items.indexOf(it) }?.takeIf { it >= 0 }
    val text = value?.let { label(it) }

    fun choose(index: Int) {
        onValueChange(items[index])
        open = false
    }

    val style = UVariantStyle(
        background = tokens.colors.background,
        foreground = tokens.colors.foreground,
        border = if (isInvalid) tokens.colors.destructive else tokens.colors.input,
        hoverBackground = tokens.colors.accent,
        pressedBackground = tokens.colors.accent,
    )

    Popup(
        open = open,
        onDismiss = { open = false },
        autoFocus = false,
        content = {
            UPopupSurface(
                modifier = Modifier
                    .semantics(
                        Semantics(
                            role = Role.ListBox,
                            label = accessibleLabel,
                            labelledBy = if (accessibleLabel == null) field?.label else null,
                        ),
                    )
                    .minWidth(MIN_LIST_WIDTH)
                    .then(themed.list)
                    .then(ui.list),
            ) {
                USelectList(
                    labels = labels,
                    selected = selected,
                    roving = roving,
                    typeahead = typeahead,
                    metrics = metrics,
                    color = set.text,
                    option = themed.option.then(ui.option),
                    selectedOption = themed.selectedOption.then(ui.selectedOption),
                    onChoose = { choose(it) },
                    onTab = { index, backwards ->
                        choose(index)
                        triggerFocus.moveFocus(backwards = backwards)
                    },
                )
            }
        },
        anchor = {
            Button(
                modifier = Modifier
                    .uVariant(style, radius = tokens.radius.md, interactive = !disabled)
                    .uFont(metrics.fontSize)
                    .minHeight(metrics.height - 2)
                    .minWidth(MIN_WIDTH)
                    .padding(horizontal = metrics.paddingX)
                    .uFocusRing()
                    .uDimmed(disabled)
                    .enabled(!disabled)
                    .then(if (field == null) Modifier.focusRequester(triggerRequester) else Modifier)
                    .uFieldControl(
                        Semantics(
                            role = Role.Combobox,
                            label = accessibleLabel,
                            valueText = text,
                            expanded = open,
                            hasPopup = true,
                            invalid = true.takeIf { invalid },
                            disabled = true.takeIf { disabled },
                        ),
                    )
                    .onKey { event ->
                        val opens = !disabled && (event.key == Key.Down || event.key == Key.Up)
                        if (opens) open = true
                        opens
                    }
                    .then(themed.base)
                    .then(ui.base)
                    .then(modifier),
                onClick = { if (!disabled) open = true },
            ) {
                // The value at the start, the chevron at the end of whatever width the trigger has.
                Row(spacing = metrics.gap, modifier = Modifier.align(v = Align.Center)) {
                    Label(
                        text ?: placeholder,
                        align = 0f,
                        ellipsize = true,
                        modifier = Modifier
                            .weight()
                            .then(if (text == null) Modifier.uTextColor(tokens.colors.mutedForeground) else Modifier),
                    )
                    Icon(Lucide.ChevronDown, size = metrics.icon, color = style.foreground)
                }
            }
        },
    )
}

/**
 * The options of an open [USelect]. The list is a roving group of Buttons: the chosen option, else
 * the first, takes the focus when it opens. Typing and Tab are handled here, because they are about
 * the list as a whole.
 */
@Composable
private fun USelectList(
    labels: List<String>,
    selected: Int?,
    roving: URovingState<Int>,
    typeahead: UTypeahead,
    metrics: USizeMetrics,
    color: Color,
    option: Modifier,
    selectedOption: Modifier,
    onChoose: (index: Int) -> Unit,
    onTab: (index: Int, backwards: Boolean) -> Unit,
) {
    val tokens = UTheme.tokens
    val indices = labels.indices.toList()
    val clear = tokens.colors.popover.withAlpha(0f)

    LaunchedEffect(Unit) {
        if (labels.isNotEmpty()) roving.focus(selected ?: 0)
    }

    Column(
        spacing = LIST_GAP,
        modifier = Modifier.onKey { event ->
            when {
                event.ctrl || event.alt || event.meta -> false
                event.key == Key.Tab -> {
                    val active = roving.focused ?: return@onKey false
                    onTab(active, event.shift)
                    true
                }
                event.key == Key.Character -> {
                    val char = event.character ?: return@onKey false
                    val found = typeahead.type(char, labels, roving.focused ?: selected ?: -1) ?: return@onKey false
                    roving.focus(found)
                    true
                }
                else -> false
            }
        },
    ) {
        labels.forEachIndexed { index, text ->
            key(index) {
                val isSelected = index == selected
                val style = UVariantStyle(
                    background = if (isSelected) tokens.colors.accent else clear,
                    foreground = tokens.colors.popoverForeground,
                    border = clear,
                    hoverBackground = tokens.colors.accent,
                    pressedBackground = tokens.colors.accent,
                )
                Button(
                    modifier = Modifier
                        .uVariant(style, radius = tokens.radius.sm, interactive = true)
                        .uFont(metrics.fontSize)
                        .hExpand()
                        .padding(horizontal = metrics.paddingX, vertical = metrics.gap)
                        .uFocusRing()
                        .uFocusFill(tokens.colors.accent)
                        .semantics(Semantics(role = Role.Option, label = text, selected = isSelected))
                        .uRovingItem(
                            state = roving,
                            key = index,
                            keys = indices,
                            selected = selected,
                            orientation = UOrientation.Vertical,
                        )
                        .then(option)
                        .then(if (isSelected) selectedOption else Modifier),
                    onClick = { onChoose(index) },
                ) {
                    Row(spacing = metrics.gap, modifier = Modifier.hExpand().align(v = Align.Center)) {
                        Label(text, align = 0f, modifier = Modifier.hExpand())
                        if (isSelected) {
                            Icon(Lucide.Check, size = metrics.icon, color = color)
                        }
                    }
                }
            }
        }
    }
}
