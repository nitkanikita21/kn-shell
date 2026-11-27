package dev.knshell.uikit.components.overlays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Button
import dev.knshell.compose.Column
import dev.knshell.compose.FocusRequester
import dev.knshell.compose.Icon
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.Tristate
import dev.knshell.compose.align
import dev.knshell.compose.enabled
import dev.knshell.compose.focusRequester
import dev.knshell.compose.hExpand
import dev.knshell.compose.height
import dev.knshell.compose.margin
import dev.knshell.compose.minWidth
import dev.knshell.compose.onKey
import dev.knshell.compose.padding
import dev.knshell.compose.semantics
import dev.knshell.compose.size
import dev.knshell.compose.testTag
import dev.knshell.icons.IconRef
import dev.knshell.icons.lucide.Lucide
import dev.knshell.shell.Popup
import dev.knshell.uikit.a11y.URovingState
import dev.knshell.uikit.a11y.rememberUTypeahead
import dev.knshell.uikit.a11y.rememberURoving
import dev.knshell.uikit.a11y.uRovingItem
import dev.knshell.uikit.core.UAlign
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.UOrientation
import dev.knshell.uikit.core.USide
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.UVariantStyle
import dev.knshell.uikit.core.colorSet
import dev.knshell.uikit.core.metrics
import dev.knshell.uikit.core.uDimmed
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uFocusFill
import dev.knshell.uikit.components.element.UKbd
import dev.knshell.uikit.core.uFocusRing
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.core.uVariant
import dev.knshell.uikit.core.withAlpha
import dev.knshell.uikit.overlay.UPopupSurface
import dev.knshell.uikit.overlay.UTrigger
import dev.knshell.uikit.overlay.toPopupAlign
import dev.knshell.uikit.overlay.toPopupSide
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/**
 * One action of a [UDropdownMenu].
 *
 * [color] paints the label (`null` keeps the text colour of the menu), [checked] makes a checkbox item
 * in that state, and a [disabled] item is shown but cannot be chosen. [onSelect] runs and the menu closes.
 */
data class UMenuItem(
    val label: String,
    val onSelect: () -> Unit,
    val icon: IconRef? = null,
    val kbds: List<String> = emptyList(),
    val color: UColor? = null,
    val checked: Boolean? = null,
    val disabled: Boolean = false,
    val testTag: String? = null,
)

/** Slots of [UDropdownMenu] an application can restyle. */
data class UDropdownMenuUi(
    /** The popup surface around the list. */
    val surface: Modifier = Modifier,
    /** Each item. */
    val item: Modifier = Modifier,
    /** The line between two groups of items. */
    val separator: Modifier = Modifier,
)

data class UDropdownMenuDefaults(
    val side: USide = USide.Bottom,
    val size: USize = USize.Md,
    val ui: UDropdownMenuUi = UDropdownMenuUi(),
) : UComponentDefaults

/** The menu defaults of the current theme. */
@Composable
fun uDropdownMenuDefaults(): UDropdownMenuDefaults = UTheme.config.find() ?: UDropdownMenuDefaults()

private const val MIN_WIDTH = 200

/**
 * A list of actions opened from [trigger], in groups separated by lines. The trigger opens the menu
 * with a click, Enter or Space, and with Down on the first item or Up on the last one.
 *
 * Inside the menu the arrows move between the enabled items, around the ends; Home and End go to the
 * first and last; typing the start of a label jumps to it. Enter, Space or a click run the item's
 * [UMenuItem.onSelect] and close the menu. Escape closes it and gives the focus back to the trigger;
 * Tab closes it and moves the focus on from the trigger.
 *
 * [open] makes the menu controlled; with `null` it keeps its own state. Submenus are not supported.
 */
@Composable
fun UDropdownMenu(
    items: List<List<UMenuItem>>,
    modifier: Modifier = Modifier,
    open: Boolean? = null,
    onOpenChange: (Boolean) -> Unit = {},
    side: USide = uDropdownMenuDefaults().side,
    size: USize = uDropdownMenuDefaults().size,
    ui: UDropdownMenuUi = UDropdownMenuUi(),
    trigger: @Composable (UTrigger) -> Unit,
) {
    val tokens = UTheme.tokens
    val themed = uDropdownMenuDefaults().ui
    var ownOpen by remember { mutableStateOf(false) }
    val isOpen = open ?: ownOpen
    fun setOpen(value: Boolean) {
        if (open == null) ownOpen = value
        onOpenChange(value)
    }

    // Items are numbered over all groups; only the enabled ones take the focus.
    val flat = items.flatten()
    val starts = items.runningFold(0) { start, group -> start + group.size }
    val keys = flat.indices.filter { !flat[it].disabled }
    val labels = keys.map { flat[it].label }

    val roving = rememberURoving<Int>()
    val typeahead = rememberUTypeahead()
    val triggerRequester = remember { FocusRequester() }
    // Where the menu opens: the item to focus, and a counter that makes every opening a new one.
    var startAt by remember { mutableStateOf<Int?>(null) }
    var session by remember { mutableStateOf(0) }
    fun openAt(key: Int?) {
        startAt = key
        session++
        setOpen(true)
    }

    Popup(
        open = isOpen,
        onDismiss = { setOpen(false) },
        modifier = modifier,
        side = side.toPopupSide(),
        align = UAlign.Start.toPopupAlign(),
        autoFocus = false,
        content = {
            UPopupSurface(modifier = themed.surface.then(ui.surface)) {
                Column(
                    modifier = Modifier
                        .minWidth(MIN_WIDTH)
                        .semantics(Semantics(role = Role.Menu))
                        .onKey { event ->
                            if (event.key == Key.Tab) {
                                setOpen(false)
                                triggerRequester.moveFocus(backwards = event.shift)
                                return@onKey true
                            }
                            val char = event.character
                            if (event.key != Key.Character || char == null || event.ctrl || event.alt || event.meta) {
                                return@onKey false
                            }
                            val current = roving.focused?.let { keys.indexOf(it) } ?: -1
                            val found = typeahead.type(char, labels, current) ?: return@onKey false
                            roving.focus(keys[found])
                            true
                        },
                ) {
                    LaunchedEffect(session, isOpen) {
                        if (isOpen) (startAt ?: keys.firstOrNull())?.let { roving.focus(it) }
                    }
                    items.forEachIndexed { group, list ->
                        if (group > 0) {
                            Box(
                                modifier = Modifier
                                    .hExpand()
                                    .height(1)
                                    .margin(vertical = tokens.spacing.xs)
                                    .uFill(tokens.colors.border)
                                    .semantics(Semantics(role = Role.Separator))
                                    .then(themed.separator)
                                    .then(ui.separator),
                            )
                        }
                        list.forEachIndexed { index, item ->
                            val key = starts[group] + index
                            MenuRow(
                                item = item,
                                key = key,
                                keys = keys,
                                roving = roving,
                                size = size,
                                slot = themed.item.then(ui.item),
                                onActivate = {
                                    // The popup gives the focus back to the trigger as it closes.
                                    setOpen(false)
                                    item.onSelect()
                                },
                            )
                        }
                    }
                }
            }
        },
    ) {
        trigger(
            UTrigger(
                modifier = Modifier
                    .focusRequester(triggerRequester)
                    .semantics(Semantics(expanded = isOpen, hasPopup = true))
                    .onKey { event ->
                        when (event.key) {
                            Key.Down -> {
                                openAt(keys.firstOrNull())
                                true
                            }
                            Key.Up -> {
                                openAt(keys.lastOrNull())
                                true
                            }
                            else -> false
                        }
                    },
                onClick = { if (isOpen) setOpen(false) else openAt(keys.firstOrNull()) },
            ),
        )
    }
}

/** One item of the menu: a button with the menu item role, or a checkbox item when [UMenuItem.checked] is set. */
@Composable
private fun MenuRow(
    item: UMenuItem,
    key: Int,
    keys: List<Int>,
    roving: URovingState<Int>,
    size: USize,
    slot: Modifier,
    onActivate: () -> Unit,
) {
    val tokens = UTheme.tokens
    val metrics = size.metrics
    val clear = tokens.colors.accent.withAlpha(0f)
    val foreground = item.color?.let { tokens.colors.colorSet(it).text } ?: tokens.colors.popoverForeground
    val style = UVariantStyle(
        background = clear,
        foreground = foreground,
        border = clear,
        hoverBackground = tokens.colors.accent,
        pressedBackground = tokens.colors.accent,
    )
    val checked = item.checked

    Button(
        modifier = Modifier
            .hExpand()
            .uVariant(style, radius = tokens.radius.sm, interactive = !item.disabled)
            .uFont(metrics.fontSize)
            .padding(horizontal = metrics.paddingX, vertical = tokens.spacing.xs + 2)
            .uFocusRing()
            .uFocusFill(tokens.colors.accent)
            .uDimmed(item.disabled)
            .enabled(!item.disabled)
            .semantics(
                Semantics(
                    role = if (checked == null) Role.MenuItem else Role.MenuItemCheckbox,
                    label = item.label,
                    checked = checked?.let { if (it) Tristate.True else Tristate.False },
                    disabled = true.takeIf { item.disabled },
                ),
            )
            .uRovingItem(roving, key, keys, orientation = UOrientation.Vertical)
            .then(item.testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .then(slot),
        onClick = { if (!item.disabled) onActivate() },
    ) {
        Row(spacing = metrics.gap, modifier = Modifier.hExpand().align(v = Align.Center)) {
            if (checked != null) {
                Box(modifier = Modifier.size(metrics.icon)) {
                    if (checked) Icon(Lucide.Check, size = metrics.icon, color = foreground)
                }
            }
            item.icon?.let { Icon(it, size = metrics.icon, color = foreground) }
            Label(item.label, align = 0f, modifier = Modifier.hExpand())
            if (item.kbds.isNotEmpty()) UKbd(item.kbds, size = USize.Xs)
        }
    }
}
