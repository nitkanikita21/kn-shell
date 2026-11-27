package dev.knshell.uikit.components.overlays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.knshell.compose.Align
import dev.knshell.compose.BorderSide
import dev.knshell.compose.Box
import dev.knshell.compose.Color
import dev.knshell.compose.Column
import dev.knshell.compose.FocusRequester
import dev.knshell.compose.FocusScope
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Portal
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.SemanticsRef
import dev.knshell.compose.Stack
import dev.knshell.compose.align
import dev.knshell.compose.border
import dev.knshell.compose.expand
import dev.knshell.compose.hExpand
import dev.knshell.compose.margin
import dev.knshell.compose.minWidth
import dev.knshell.compose.onClick
import dev.knshell.compose.onKey
import dev.knshell.compose.padding
import dev.knshell.compose.semantics
import dev.knshell.compose.semanticsRef
import dev.knshell.compose.semiBold
import dev.knshell.icons.lucide.Lucide
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.UVariant
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** [AlertDialog] interrupts to ask for a decision: it has no close button. */
enum class UModalRole { Dialog, AlertDialog }

data class UModalUi(
    /** The dimmed layer behind the dialog. */
    val overlay: Modifier = Modifier,
    /** The dialog itself. */
    val content: Modifier = Modifier,
    val header: Modifier = Modifier,
    val title: Modifier = Modifier,
    val description: Modifier = Modifier,
    val body: Modifier = Modifier,
    val footer: Modifier = Modifier,
)

data class UModalDefaults(
    /** The accessible name of the close button. */
    val closeLabel: String = "Закрити",
    val ui: UModalUi = UModalUi(),
) : UComponentDefaults

@Composable
fun uModalDefaults(): UModalDefaults = UTheme.config.find() ?: UModalDefaults()

private val Scrim = Color(0, 0, 0, 0.5f)

private const val MIN_WIDTH = 360

/** Longest line of the description before it wraps. */
private const val TEXT_CHARS = 48

/**
 * A dialog over the window, in the nearest `OverlayHost`. While [open] the keyboard stays inside it:
 * Tab goes around its controls, and Escape or a click beside it close a [dismissible] one by calling
 * [onOpenChange]. Closing gives the focus back to where it was.
 *
 * The first control takes the focus, or [initialFocus] when given. In an [UModalRole.AlertDialog]
 * put the safe action first in [footer], so that it is the one focused.
 */
@Composable
fun UModal(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    footer: (@Composable () -> Unit)? = null,
    dismissible: Boolean = true,
    role: UModalRole = UModalRole.Dialog,
    initialFocus: FocusRequester? = null,
    ui: UModalUi = UModalUi(),
    content: @Composable () -> Unit = {},
) {
    if (!open) return
    Portal {
        val tokens = UTheme.tokens
        val defaults = uModalDefaults()
        val themed = defaults.ui
        val titleRef = remember { SemanticsRef() }
        val descriptionRef = remember { SemanticsRef() }
        val ms = tokens.motion.themeMs
        val dismiss = { if (dismissible) onOpenChange(false) }
        if (initialFocus != null) LaunchedEffect(initialFocus) { initialFocus.requestFocus() }

        Stack(modifier = Modifier.expand()) {
            // Beside the dialog, not around it: a click inside the dialog must not reach it.
            Box(
                Modifier.expand().uFill(Scrim).onClick { dismiss() }.then(themed.overlay).then(ui.overlay),
            )
            FocusScope(
                modifier = Modifier
                    .align(Align.Center)
                    .margin(horizontal = tokens.spacing.lg, vertical = tokens.spacing.lg)
                    .onKey { event ->
                        val escape = event.key == Key.Escape
                        if (escape) dismiss()
                        escape
                    },
                trap = true,
                autoFocus = initialFocus == null,
            ) {
                Column(
                    modifier = Modifier
                        .uFill(tokens.colors.popover, border = tokens.colors.border, radius = tokens.radius.lg, shadow = true)
                        .uTextColor(tokens.colors.popoverForeground)
                        .uFont(size = 13)
                        .minWidth(MIN_WIDTH)
                        .semantics(
                            Semantics(
                                role = if (role == UModalRole.Dialog) Role.Dialog else Role.AlertDialog,
                                modal = true,
                                labelledBy = titleRef,
                                describedBy = descriptionRef.takeIf { description != null },
                            ),
                        )
                        .then(themed.content)
                        .then(ui.content)
                        .then(modifier),
                ) {
                    Row(
                        spacing = tokens.spacing.md,
                        modifier = Modifier
                            .hExpand()
                            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.md)
                            .then(themed.header)
                            .then(ui.header),
                    ) {
                        Column(spacing = tokens.spacing.xs, modifier = Modifier.hExpand().align(v = Align.Center)) {
                            Label(
                                title,
                                align = 0f,
                                modifier = Modifier.semanticsRef(titleRef).uFont(size = 15).semiBold()
                                    .then(themed.title).then(ui.title),
                            )
                            if (description != null) {
                                Label(
                                    description,
                                    align = 0f,
                                    wrap = true,
                                    maxWidthChars = TEXT_CHARS,
                                    modifier = Modifier.semanticsRef(descriptionRef)
                                        .uTextColor(tokens.colors.mutedForeground)
                                        .then(themed.description).then(ui.description),
                                )
                            }
                        }
                        if (dismissible && role == UModalRole.Dialog) {
                            UButton(
                                onClick = { dismiss() },
                                modifier = Modifier.align(v = Align.Start),
                                color = UColor.Neutral,
                                variant = UVariant.Ghost,
                                size = USize.Sm,
                                leadingIcon = Lucide.X,
                                accessibleLabel = defaults.closeLabel,
                            )
                        }
                    }
                    Column(
                        spacing = tokens.spacing.sm,
                        modifier = Modifier
                            .hExpand()
                            .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.xs)
                            .then(themed.body)
                            .then(ui.body),
                    ) { content() }
                    if (footer != null) {
                        Row(
                            spacing = tokens.spacing.sm,
                            modifier = Modifier
                                .hExpand()
                                .border(tokens.colors.border, sides = setOf(BorderSide.Top), transitionMs = ms)
                                .padding(horizontal = tokens.spacing.lg, vertical = tokens.spacing.md)
                                .then(themed.footer)
                                .then(ui.footer),
                        ) {
                            Row(spacing = tokens.spacing.sm, modifier = Modifier.hExpand().align(h = Align.End)) {
                                footer()
                            }
                        }
                    } else {
                        Box(Modifier.padding(vertical = tokens.spacing.xs))
                    }
                }
            }
        }
    }
}
