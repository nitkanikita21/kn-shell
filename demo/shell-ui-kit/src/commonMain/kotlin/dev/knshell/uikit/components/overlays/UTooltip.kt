package dev.knshell.uikit.components.overlays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.Box
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.onFocusChange
import dev.knshell.compose.onHover
import dev.knshell.compose.onKey
import dev.knshell.compose.padding
import dev.knshell.compose.semantics
import dev.knshell.shell.Popup
import dev.knshell.uikit.core.UAlign
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.components.element.UKbd
import dev.knshell.uikit.core.USide
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.overlay.UPopupSurface
import dev.knshell.uikit.overlay.UTrigger
import dev.knshell.uikit.overlay.toPopupAlign
import dev.knshell.uikit.overlay.toPopupSide
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Slots of [UTooltip] an application can restyle. */
data class UTooltipUi(
    /** The bubble itself. */
    val bubble: Modifier = Modifier,
    /** The text in the bubble. */
    val text: Modifier = Modifier,
)

data class UTooltipDefaults(
    val side: USide = USide.Top,
    /** Milliseconds the pointer rests on the trigger before the tooltip opens. */
    val delayMs: Int = 500,
    val ui: UTooltipUi = UTooltipUi(),
) : UComponentDefaults

/** The tooltip defaults of the current theme. */
@Composable
fun uTooltipDefaults(): UTooltipDefaults = UTheme.config.find() ?: UTooltipDefaults()

private const val TEXT_CHARS = 40

/**
 * A short hint about a control. It opens at once when the control takes the keyboard focus and after
 * [delayMs] when the pointer rests on it. It closes when the focus leaves, when the pointer leaves,
 * on a click and on Escape, which keeps the focus where it is.
 *
 * The tooltip is not interactive and takes no focus. [text] becomes the description of the control,
 * which the trigger applies together with [UTrigger.modifier] and [UTrigger.onClick]. [kbds] are shown
 * after the text as key caps.
 */
@Composable
fun UTooltip(
    text: String,
    modifier: Modifier = Modifier,
    kbds: List<String> = emptyList(),
    delayMs: Int = uTooltipDefaults().delayMs,
    side: USide = uTooltipDefaults().side,
    ui: UTooltipUi = UTooltipUi(),
    trigger: @Composable (UTrigger) -> Unit,
) {
    val tokens = UTheme.tokens
    val themed = uTooltipDefaults().ui
    var focused by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    var hoverRested by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val visible = !dismissed && (focused || hoverRested)

    LaunchedEffect(hovered, delayMs) {
        hoverRested = false
        if (hovered) {
            delay(delayMs.milliseconds)
            hoverRested = true
        }
    }

    Popup(
        open = visible,
        onDismiss = { dismissed = true },
        modifier = modifier,
        side = side.toPopupSide(),
        align = UAlign.Center.toPopupAlign(),
        autoFocus = false,
        dismissible = false,
        content = {
            UPopupSurface(
                modifier = Modifier
                    .semantics(Semantics(role = Role.Tooltip))
                    .then(themed.bubble)
                    .then(ui.bubble),
            ) {
                Row(
                    spacing = tokens.spacing.sm,
                    modifier = Modifier.padding(horizontal = tokens.spacing.sm, vertical = tokens.spacing.xs),
                ) {
                    Label(
                        text,
                        align = 0f,
                        wrap = true,
                        maxWidthChars = TEXT_CHARS,
                        modifier = Modifier.then(themed.text).then(ui.text),
                    )
                    if (kbds.isNotEmpty()) UKbd(kbds, size = USize.Xs)
                }
            }
        },
    ) {
        trigger(
            UTrigger(
                modifier = Modifier
                    .semantics(Semantics(description = text))
                    .onFocusChange {
                        focused = it
                        dismissed = false
                    }
                    .onHover(
                        onEnter = {
                            hovered = true
                            dismissed = false
                        },
                        onExit = { hovered = false },
                    )
                    .onKey { event ->
                        // Escape only hides a visible tooltip; with none shown it belongs to the window.
                        val hide = event.key == Key.Escape && visible
                        if (hide) dismissed = true
                        hide
                    },
                onClick = { dismissed = true },
            ),
        )
    }
}
