package dev.knshell.uikit.components.form

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Orientation
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.SemanticsRef
import dev.knshell.compose.Tristate
import dev.knshell.compose.align
import dev.knshell.compose.weight
import dev.knshell.compose.enabled
import dev.knshell.compose.hExpand
import dev.knshell.compose.onClick
import dev.knshell.compose.onKey
import dev.knshell.compose.semantics
import dev.knshell.compose.semanticsRef
import dev.knshell.compose.semiBold
import dev.knshell.compose.size
import dev.knshell.compose.testTag
import dev.knshell.uikit.a11y.rememberURoving
import dev.knshell.uikit.a11y.uRovingItem
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.UOrientation
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.colorSet
import dev.knshell.uikit.core.metrics
import dev.knshell.uikit.core.uDimmed
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uFocusRing
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** One choice of a [URadioGroup]. [value] is what the group reports as chosen. */
data class URadioItem<T>(
    val value: T,
    val label: String,
    val description: String? = null,
    val disabled: Boolean = false,
    val testTag: String? = null,
)

/** Slots of [URadioGroup] an application can restyle. */
data class URadioGroupUi(
    /** The whole group. */
    val base: Modifier = Modifier,
    val legend: Modifier = Modifier,
    /** One choice, after the state of the theme. */
    val item: Modifier = Modifier,
    val selectedItem: Modifier = Modifier,
    /** The circle of a choice. */
    val indicator: Modifier = Modifier,
    val label: Modifier = Modifier,
    val description: Modifier = Modifier,
)

data class URadioGroupDefaults(
    val color: UColor = UColor.Primary,
    val size: USize = USize.Md,
    val ui: URadioGroupUi = URadioGroupUi(),
) : UComponentDefaults

/** The radio group defaults of the current theme. */
@Composable
fun uRadioGroupDefaults(): URadioGroupDefaults = UTheme.config.find() ?: URadioGroupDefaults()

/**
 * One choice among visible [items], under the [legend] that names the group. The group is one Tab
 * stop: the chosen item takes the focus, else the first enabled one. Arrows (both axes) move to the
 * neighbour, around the ends, and select it; Space and a click select the focused item.
 *
 * [orientation] only lays the items out: the keys work the same either way.
 */
@Composable
fun <T : Any> URadioGroup(
    items: List<URadioItem<T>>,
    value: T,
    onValueChange: (T) -> Unit,
    legend: String,
    modifier: Modifier = Modifier,
    orientation: UOrientation = UOrientation.Vertical,
    color: UColor = uRadioGroupDefaults().color,
    size: USize = uRadioGroupDefaults().size,
    disabled: Boolean = false,
    ui: URadioGroupUi = URadioGroupUi(),
) {
    val tokens = UTheme.tokens
    val themed = uRadioGroupDefaults().ui
    val roving = rememberURoving<T>()
    val legendRef = remember { SemanticsRef() }
    val metrics = size.metrics
    val set = tokens.colors.colorSet(color)
    val keys = items.filterNot { disabled || it.disabled }.map { it.value }

    val choices: @Composable () -> Unit = {
        items.forEach { item ->
            key(item.value) {
                val checked = item.value == value
                val off = disabled || item.disabled
                val choose = { if (!off) onValueChange(item.value) }
                Row(
                    spacing = tokens.spacing.sm,
                    modifier = Modifier
                        .semantics(
                            Semantics(
                                role = Role.Radio,
                                label = item.label,
                                description = item.description,
                                checked = if (checked) Tristate.True else Tristate.False,
                                disabled = true.takeIf { off },
                            ),
                        )
                        .uRovingItem(
                            state = roving,
                            key = item.value,
                            keys = keys,
                            selected = value,
                            orientation = null,
                            onMove = { onValueChange(it) },
                        )
                        .onKey { event ->
                            val space = event.key == Key.Space
                            if (space) choose()
                            space
                        }
                        .onClick(choose)
                        .uFocusRing()
                        .uDimmed(off)
                        .enabled(!off)
                        .then(item.testTag?.let { Modifier.testTag(it) } ?: Modifier)
                        .then(themed.item)
                        .then(if (checked) themed.selectedItem.then(ui.selectedItem) else Modifier)
                        .then(ui.item),
                ) {
                    val diameter = metrics.icon
                    Box(
                        modifier = Modifier
                            .size(diameter)
                            .align(v = Align.Start)
                            .uFill(
                                tokens.colors.background,
                                border = if (checked) set.fill else tokens.colors.input,
                                radius = diameter / 2,
                            )
                            .then(themed.indicator)
                            .then(ui.indicator),
                    ) {
                        if (checked) {
                            Box(
                                Modifier
                                    .size(diameter / 2)
                                    .uFill(set.fill, radius = diameter / 4)
                                    .weight()
                                    .align(Align.Center),
                            )
                        }
                    }
                    // In a column the text takes the width; side by side each choice keeps its own.
                    val textWidth = if (orientation == UOrientation.Vertical) Modifier.hExpand() else Modifier
                    Column(spacing = tokens.spacing.xs, modifier = textWidth) {
                        Label(
                            item.label,
                            align = 0f,
                            wrap = true,
                            modifier = Modifier.then(themed.label).then(ui.label),
                        )
                        item.description?.let {
                            Label(
                                it,
                                align = 0f,
                                wrap = true,
                                modifier = Modifier
                                    .uTextColor(tokens.colors.mutedForeground)
                                    .then(themed.description)
                                    .then(ui.description),
                            )
                        }
                    }
                }
            }
        }
    }

    Column(
        spacing = tokens.spacing.sm,
        modifier = Modifier
            .uFont(metrics.fontSize)
            .uTextColor(tokens.colors.foreground)
            .semantics(
                Semantics(
                    role = Role.RadioGroup,
                    labelledBy = legendRef,
                    orientation = if (orientation == UOrientation.Vertical) Orientation.Vertical else Orientation.Horizontal,
                    disabled = true.takeIf { disabled },
                ),
            )
            .then(themed.base)
            .then(ui.base)
            .then(modifier),
    ) {
        Label(
            legend,
            align = 0f,
            modifier = Modifier
                .semanticsRef(legendRef)
                .semiBold()
                .then(themed.legend)
                .then(ui.legend),
        )
        if (orientation == UOrientation.Vertical) {
            Column(spacing = tokens.spacing.sm) { choices() }
        } else {
            Row(spacing = tokens.spacing.md, modifier = Modifier.align(h = Align.Start)) { choices() }
        }
    }
}
