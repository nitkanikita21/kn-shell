package dev.knshell.uikit.components.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.knshell.compose.Align
import dev.knshell.compose.BorderSide
import dev.knshell.compose.Button
import dev.knshell.compose.Column
import dev.knshell.compose.Icon
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Orientation
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.SemanticsRef
import dev.knshell.compose.align
import dev.knshell.compose.border
import dev.knshell.compose.enabled
import dev.knshell.compose.focusable
import dev.knshell.compose.hExpand
import dev.knshell.compose.medium
import dev.knshell.compose.padding
import dev.knshell.compose.semantics
import dev.knshell.compose.semanticsRef
import dev.knshell.compose.testTag
import dev.knshell.icons.IconRef
import dev.knshell.uikit.a11y.rememberURoving
import dev.knshell.uikit.a11y.uRovingItem
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.UOrientation
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.UVariantStyle
import dev.knshell.uikit.core.colorSet
import dev.knshell.uikit.core.metrics
import dev.knshell.uikit.core.uDimmed
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uFocusRing
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.core.uVariant
import dev.knshell.uikit.core.withAlpha
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** One tab. [id] is what [UTabs] reports as selected. */
data class UTabItem(
    val id: String,
    val label: String,
    val icon: IconRef? = null,
    val disabled: Boolean = false,
    val testTag: String? = null,
)

/** Whether arrows select the tab they reach, or only focus it until Enter or Space. */
enum class UActivation { Automatic, Manual }

enum class UTabsVariant { Pill, Line }

data class UTabsUi(
    val base: Modifier = Modifier,
    val list: Modifier = Modifier,
    val tab: Modifier = Modifier,
    /** The tab that is selected, after [tab]. */
    val selectedTab: Modifier = Modifier,
    val panel: Modifier = Modifier,
)

data class UTabsDefaults(
    val color: UColor = UColor.Primary,
    val variant: UTabsVariant = UTabsVariant.Pill,
    val size: USize = USize.Md,
    val ui: UTabsUi = UTabsUi(),
) : UComponentDefaults

@Composable
fun uTabsDefaults(): UTabsDefaults = UTheme.config.find() ?: UTabsDefaults()

/** Space between the edge of a pill list and its tabs. */
private const val LIST_PADDING = 3

/**
 * Tabs with the panel of the [selected] one, drawn by [content]. The list is one Tab stop: arrows
 * along [orientation], Home and End move between tabs, and with [UActivation.Automatic] select them.
 */
@Composable
fun UTabs(
    items: List<UTabItem>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    orientation: UOrientation = UOrientation.Horizontal,
    activation: UActivation = UActivation.Automatic,
    color: UColor = uTabsDefaults().color,
    variant: UTabsVariant = uTabsDefaults().variant,
    size: USize = uTabsDefaults().size,
    accessibleLabel: String? = null,
    ui: UTabsUi = UTabsUi(),
    content: @Composable (UTabItem) -> Unit,
) {
    val tokens = UTheme.tokens
    val themed = uTabsDefaults().ui
    val roving = rememberURoving<String>()
    val panel = remember { SemanticsRef() }
    val tabs = remember { mutableMapOf<String, SemanticsRef>() }
    val enabled = items.filterNot { it.disabled }.map { it.id }
    val horizontal = orientation == UOrientation.Horizontal

    val list: @Composable () -> Unit = {
        val frame = when (variant) {
            UTabsVariant.Pill -> Modifier
                .uFill(tokens.colors.secondary, radius = tokens.radius.md)
                .padding(all = LIST_PADDING)
            UTabsVariant.Line -> Modifier.border(
                tokens.colors.border,
                sides = setOf(if (horizontal) BorderSide.Bottom else BorderSide.Right),
                transitionMs = tokens.motion.themeMs,
            )
        }
        val listModifier = frame
            .semantics(
                Semantics(
                    role = Role.TabList,
                    label = accessibleLabel,
                    orientation = if (horizontal) Orientation.Horizontal else Orientation.Vertical,
                ),
            )
            .align(h = Align.Start, v = Align.Start)
            .then(themed.list)
            .then(ui.list)
        val body: @Composable () -> Unit = {
            items.forEach { item ->
                key(item.id) {
                    val active = item.id == selected
                    Tab(
                        item = item,
                        active = active,
                        color = color,
                        variant = variant,
                        size = size,
                        horizontal = horizontal,
                        modifier = Modifier
                            .semanticsRef(tabs.getOrPut(item.id) { SemanticsRef() })
                            .semantics(
                                Semantics(
                                    role = Role.Tab,
                                    label = item.label,
                                    selected = active,
                                    disabled = true.takeIf { item.disabled },
                                    controls = panel.takeIf { active },
                                ),
                            )
                            .uRovingItem(
                                state = roving,
                                key = item.id,
                                keys = enabled,
                                selected = selected,
                                orientation = orientation,
                                onMove = { if (activation == UActivation.Automatic) onSelect(it) },
                            )
                            .then(themed.tab)
                            .then(ui.tab)
                            .then(if (active) themed.selectedTab.then(ui.selectedTab) else Modifier),
                        onClick = { onSelect(item.id) },
                    )
                }
            }
        }
        if (horizontal) {
            Row(spacing = 2, modifier = listModifier) { body() }
        } else {
            Column(spacing = 2, modifier = listModifier) { body() }
        }
    }
    val panelContent: @Composable () -> Unit = {
        items.firstOrNull { it.id == selected }?.let { item ->
            key(item.id) {
                Column(
                    modifier = Modifier
                        .semanticsRef(panel)
                        .semantics(Semantics(role = Role.TabPanel, labelledBy = tabs[item.id]))
                        // The panel is a Tab stop, so that its text can be reached and read.
                        .focusable()
                        .uFocusRing()
                        .hExpand()
                        .padding(all = tokens.spacing.sm)
                        .then(themed.panel)
                        .then(ui.panel),
                ) { content(item) }
            }
        }
    }

    val root = Modifier.uFont(size.metrics.fontSize).then(themed.base).then(ui.base).then(modifier)
    if (horizontal) {
        Column(spacing = tokens.spacing.sm, modifier = root) {
            list()
            panelContent()
        }
    } else {
        Row(spacing = tokens.spacing.md, modifier = root) {
            list()
            panelContent()
        }
    }
}

@Composable
private fun Tab(
    item: UTabItem,
    active: Boolean,
    color: UColor,
    variant: UTabsVariant,
    size: USize,
    horizontal: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tokens = UTheme.tokens
    val set = tokens.colors.colorSet(color)
    val metrics = size.metrics
    val clear = set.fill.withAlpha(0f)
    val pill = variant == UTabsVariant.Pill
    val foreground = when {
        !active -> tokens.colors.mutedForeground
        pill -> set.onFill
        else -> set.text
    }
    val background = if (active && pill) set.fill else clear
    val hover = if (active) background else tokens.colors.accent.withAlpha(if (pill) 1f else 0.5f)
    val style = UVariantStyle(background, foreground, clear, hover, hover)
    val underline = if (pill) {
        Modifier
    } else {
        Modifier.border(
            if (active) set.fill else clear,
            width = 2,
            sides = setOf(if (horizontal) BorderSide.Bottom else BorderSide.Right),
            transitionMs = tokens.motion.themeMs,
        )
    }

    Button(
        modifier = Modifier
            .uVariant(style, radius = if (pill) tokens.radius.md - 2 else 0, interactive = !item.disabled)
            .then(underline)
            .medium()
            .padding(horizontal = metrics.paddingX + 2, vertical = metrics.gap - 1)
            .uFocusRing()
            .uDimmed(item.disabled)
            .enabled(!item.disabled)
            .then(item.testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .then(modifier),
        onClick = onClick,
    ) {
        Row(spacing = metrics.gap, modifier = Modifier.align(Align.Center)) {
            item.icon?.let {
                Icon(it, modifier = Modifier.align(v = Align.Center), size = metrics.icon, color = foreground)
            }
            Label(item.label)
        }
    }
}
