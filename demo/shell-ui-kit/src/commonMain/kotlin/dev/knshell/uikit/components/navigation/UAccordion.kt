package dev.knshell.uikit.components.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.knshell.compose.Align
import dev.knshell.compose.BorderSide
import dev.knshell.compose.Button
import dev.knshell.compose.Column
import dev.knshell.compose.FocusRequester
import dev.knshell.compose.Icon
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.SemanticsRef
import dev.knshell.compose.align
import dev.knshell.compose.border
import dev.knshell.compose.enabled
import dev.knshell.compose.focusRequester
import dev.knshell.compose.hExpand
import dev.knshell.compose.onKey
import dev.knshell.compose.padding
import dev.knshell.compose.semantics
import dev.knshell.compose.semanticsRef
import dev.knshell.compose.testTag
import dev.knshell.icons.IconRef
import dev.knshell.icons.lucide.Lucide
import dev.knshell.uikit.a11y.rovingTarget
import dev.knshell.uikit.core.UOrientation
import dev.knshell.uikit.core.UVariantStyle
import dev.knshell.uikit.core.uDimmed
import dev.knshell.uikit.core.uFocusRing
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.core.uVariant
import dev.knshell.uikit.core.withAlpha
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** One section of a [UAccordion]. [id] is the key that `expanded` holds. */
data class UAccordionItem(
    val id: String,
    val label: String,
    val icon: IconRef? = null,
    val disabled: Boolean = false,
    val testTag: String? = null,
)

/** Whether one section may be open at a time, or any number of them. */
enum class UAccordionType { Single, Multiple }

/** Slots of [UAccordion] an application can restyle. */
data class UAccordionUi(
    /** The whole accordion. */
    val base: Modifier = Modifier,
    /** One section, header and panel together. */
    val item: Modifier = Modifier,
    /** The header button of a section. */
    val header: Modifier = Modifier,
    /** The panel of an open section. */
    val panel: Modifier = Modifier,
)

data class UAccordionDefaults(
    val ui: UAccordionUi = UAccordionUi(),
) : UComponentDefaults

/** The accordion defaults of the current theme. */
@Composable
fun uAccordionDefaults(): UAccordionDefaults = UTheme.config.find() ?: UAccordionDefaults()

private const val FONT_SIZE = 13
private const val ICON_SIZE = 16

/**
 * Sections that open and close, given by [items]; [expanded] holds the ids of the open ones and
 * [onExpandedChange] hears every change. With [UAccordionType.Single] opening one closes the others.
 *
 * Each header is a button and a Tab stop: Enter and Space open or close it. Up, Down, Home and End
 * move the focus between the headers. The panel of an open section is a region named by its header,
 * and [content] is composed only while the section is open.
 */
@Composable
fun UAccordion(
    items: List<UAccordionItem>,
    expanded: Set<String>,
    onExpandedChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    type: UAccordionType = UAccordionType.Single,
    ui: UAccordionUi = UAccordionUi(),
    content: @Composable (UAccordionItem) -> Unit,
) {
    val tokens = UTheme.tokens
    val themed = uAccordionDefaults().ui
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val headers = remember { mutableMapOf<String, SemanticsRef>() }
    val panels = remember { mutableMapOf<String, SemanticsRef>() }
    val enabledIds = items.filterNot { it.disabled }.map { it.id }
    val clear = tokens.colors.background.withAlpha(0f)

    fun toggle(id: String) {
        onExpandedChange(
            when {
                id in expanded -> expanded - id
                type == UAccordionType.Single -> setOf(id)
                else -> expanded + id
            },
        )
    }

    Column(
        modifier = Modifier
            .uFont(FONT_SIZE)
            .uTextColor(tokens.colors.foreground)
            .then(themed.base)
            .then(ui.base)
            .then(modifier),
    ) {
        items.forEach { item ->
            key(item.id) {
                val isOpen = item.id in expanded
                val requester = requesters.getOrPut(item.id) { FocusRequester() }
                val header = headers.getOrPut(item.id) { SemanticsRef() }
                val panel = panels.getOrPut(item.id) { SemanticsRef() }
                val style = UVariantStyle(
                    background = clear,
                    foreground = tokens.colors.foreground,
                    border = clear,
                    hoverBackground = tokens.colors.accent,
                    pressedBackground = tokens.colors.accent,
                )

                Column(
                    modifier = Modifier
                        .hExpand()
                        .border(tokens.colors.border, sides = setOf(BorderSide.Bottom), transitionMs = tokens.motion.themeMs)
                        .then(themed.item)
                        .then(ui.item),
                ) {
                    Button(
                        modifier = Modifier
                            .uVariant(style, radius = 0, interactive = !item.disabled)
                            .hExpand()
                            .padding(horizontal = tokens.spacing.md, vertical = tokens.spacing.sm)
                            .semanticsRef(header)
                            .focusRequester(requester)
                            .semantics(
                                Semantics(
                                    role = Role.Button,
                                    label = item.label,
                                    expanded = isOpen,
                                    disabled = true.takeIf { item.disabled },
                                    controls = panel.takeIf { isOpen },
                                ),
                            )
                            .onKey { event ->
                                if (event.ctrl || event.alt || event.meta) return@onKey false
                                val target = rovingTarget(
                                    keys = enabledIds,
                                    current = item.id,
                                    pressed = event.key,
                                    orientation = UOrientation.Vertical,
                                    loop = true,
                                ) ?: return@onKey false
                                requesters[target]?.requestFocus()
                                true
                            }
                            .uFocusRing()
                            .uDimmed(item.disabled)
                            .enabled(!item.disabled)
                            .then(item.testTag?.let { Modifier.testTag(it) } ?: Modifier)
                            .then(themed.header)
                            .then(ui.header),
                        onClick = { if (!item.disabled) toggle(item.id) },
                    ) {
                        Row(spacing = tokens.spacing.sm, modifier = Modifier.hExpand().align(v = Align.Center)) {
                            item.icon?.let { Icon(it, size = ICON_SIZE, color = style.foreground) }
                            Label(item.label, align = 0f, wrap = true, modifier = Modifier.hExpand())
                            Icon(if (isOpen) Lucide.ChevronDown else Lucide.ChevronRight, size = ICON_SIZE, color = style.foreground)
                        }
                    }
                    if (isOpen) {
                        Column(
                            modifier = Modifier
                                .hExpand()
                                .semanticsRef(panel)
                                .semantics(Semantics(role = Role.Region, labelledBy = header))
                                .padding(horizontal = tokens.spacing.md, vertical = tokens.spacing.sm)
                                .then(themed.panel)
                                .then(ui.panel),
                        ) { content(item) }
                    }
                }
            }
        }
    }
}
