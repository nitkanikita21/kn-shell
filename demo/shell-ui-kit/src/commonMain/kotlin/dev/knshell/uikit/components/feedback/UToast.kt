package dev.knshell.uikit.components.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.knshell.compose.Align
import dev.knshell.compose.Box
import dev.knshell.compose.Column
import dev.knshell.compose.Entry
import dev.knshell.compose.FocusRequester
import dev.knshell.compose.Icon
import dev.knshell.compose.Key
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Row
import dev.knshell.compose.Semantics
import dev.knshell.compose.align
import dev.knshell.compose.entryStyle
import dev.knshell.compose.focusRequester
import dev.knshell.compose.focusable
import dev.knshell.compose.height
import dev.knshell.compose.hExpand
import dev.knshell.compose.minWidth
import dev.knshell.compose.onKey
import dev.knshell.compose.padding
import dev.knshell.compose.semantics
import dev.knshell.compose.semiBold
import dev.knshell.compose.testTag
import dev.knshell.compose.weight
import dev.knshell.icons.IconRef
import dev.knshell.icons.lucide.Lucide
import dev.knshell.ui.primitives.Image
import dev.knshell.uikit.components.element.UButton
import dev.knshell.uikit.core.UAction
import dev.knshell.uikit.core.UColor
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.UVariant
import dev.knshell.uikit.core.colorSet
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uFocusRing
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.core.withAlpha
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A picture in a toast. [alt] says what it shows, for a screen reader, and is required. */
data class UToastImage(val src: String, val alt: String) {
    init {
        require(alt.isNotBlank()) { "a toast image needs alt text" }
    }
}

/**
 * A line to answer in a toast: a field and a send button. Enter sends the text, which is then
 * cleared. [onDraftChange] hears every change of the text, so that the toaster holds the countdown
 * while something is being written.
 */
data class UToastReply(
    val onSend: (String) -> Unit,
    val placeholder: String = "Відповісти…",
    val sendLabel: String = "Надіслати",
    val onDraftChange: (String) -> Unit = {},
)

/** Slots of [UToastCard] an application can restyle. */
data class UToastUi(
    /** The card itself. */
    val base: Modifier = Modifier,
    val icon: Modifier = Modifier,
    val title: Modifier = Modifier,
    val description: Modifier = Modifier,
    /** The countdown bar. */
    val progress: Modifier = Modifier,
)

data class UToastDefaults(
    /** How long a toast stays when neither the toast nor its toaster says otherwise. */
    val duration: Duration = 4.seconds,
    /** The accessible name of the close button. */
    val closeLabel: String = "Закрити",
    val ui: UToastUi = UToastUi(),
) : UComponentDefaults

/** The toast defaults of the current theme. */
@Composable
fun uToastDefaults(): UToastDefaults = UTheme.config.find() ?: UToastDefaults()

private const val CARD_MIN_WIDTH = 320
private const val IMAGE_WIDTH = 296
private const val IMAGE_HEIGHT = 120
private const val BAR_HEIGHT = 3
private const val TEXT_CHARS = 40

/**
 * One toast as a card: a title and an optional description, a status [icon] (or [leading] in its
 * place), a [meta] text, a picture, a reply line, [actions], and a countdown bar at the bottom while
 * [progress] (0 to 1) is given.
 *
 * The card takes the focus. Escape calls [onClose]. [color] paints only the icon: the card stays the
 * popover. The card is not a live region by itself; a [UToasterHost] makes it one.
 */
@Composable
fun UToastCard(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    color: UColor = UColor.Neutral,
    icon: IconRef? = null,
    leading: (@Composable () -> Unit)? = null,
    meta: (@Composable () -> Unit)? = null,
    image: UToastImage? = null,
    reply: UToastReply? = null,
    actions: List<UAction> = emptyList(),
    progress: Float? = null,
    onClose: (() -> Unit)? = null,
    ui: UToastUi = UToastUi(),
    content: (@Composable () -> Unit)? = null,
) {
    ToastCardBody(
        focus = remember { FocusRequester() },
        title = title,
        modifier = modifier,
        role = null,
        description = description,
        color = color,
        icon = icon,
        leading = leading,
        meta = meta,
        image = image,
        reply = reply,
        actions = actions,
        progress = progress,
        onClose = onClose,
        ui = ui,
        content = content,
    )
}

/**
 * The card of [UToastCard] with the [focus] handle and [role] given by the caller: a [UToasterHost]
 * keeps the handle of each toast, to move the focus there, and sets the live role.
 */
@Composable
internal fun ToastCardBody(
    focus: FocusRequester,
    title: String,
    modifier: Modifier,
    role: Role?,
    description: String?,
    color: UColor,
    icon: IconRef?,
    leading: (@Composable () -> Unit)?,
    meta: (@Composable () -> Unit)?,
    image: UToastImage?,
    reply: UToastReply?,
    actions: List<UAction>,
    progress: Float?,
    onClose: (() -> Unit)?,
    ui: UToastUi,
    content: (@Composable () -> Unit)?,
) {
    val tokens = UTheme.tokens
    val defaults = uToastDefaults()
    val themed = defaults.ui
    var draft by remember { mutableStateOf("") }

    fun send() {
        val text = draft.trim()
        if (reply == null || text.isEmpty()) return
        draft = ""
        reply.onDraftChange("")
        reply.onSend(text)
    }

    Column(
        modifier = Modifier
            .minWidth(CARD_MIN_WIDTH)
            .uFill(tokens.colors.popover, border = tokens.colors.border, radius = tokens.radius.md, shadow = true)
            .uTextColor(tokens.colors.popoverForeground)
            .uFont(size = 13)
            .focusRequester(focus)
            .focusable()
            .uFocusRing()
            .onKey { event ->
                val close = event.key == Key.Escape && onClose != null
                if (close) onClose?.invoke()
                close
            }
            .semantics(Semantics(role = role, label = title, description = description))
            .then(themed.base)
            .then(ui.base)
            .then(modifier),
    ) {
        Row(
            spacing = tokens.spacing.md,
            modifier = Modifier.hExpand().padding(horizontal = tokens.spacing.md, vertical = tokens.spacing.md),
        ) {
            if (leading != null) {
                leading()
            } else if (icon != null) {
                Icon(
                    icon,
                    modifier = Modifier.align(v = Align.Start).then(themed.icon).then(ui.icon),
                    size = tokens.icon.size,
                    color = tokens.colors.colorSet(color).text,
                )
            }
            Column(spacing = tokens.spacing.xs, modifier = Modifier.hExpand().align(v = Align.Center)) {
                Label(title, align = 0f, modifier = Modifier.semiBold().then(themed.title).then(ui.title))
                if (description != null) {
                    Label(
                        description,
                        align = 0f,
                        wrap = true,
                        maxWidthChars = TEXT_CHARS,
                        modifier = Modifier
                            .uTextColor(tokens.colors.mutedForeground)
                            .then(themed.description)
                            .then(ui.description),
                    )
                }
                if (content != null) content()
            }
            if (meta != null) {
                Box(Modifier.align(v = Align.Start)) { meta() }
            }
            if (onClose != null) {
                UButton(
                    onClick = onClose,
                    modifier = Modifier.align(v = Align.Start),
                    color = UColor.Neutral,
                    variant = UVariant.Ghost,
                    size = USize.Sm,
                    leadingIcon = Lucide.X,
                    accessibleLabel = defaults.closeLabel,
                )
            }
        }

        if (image != null) {
            Box(
                modifier = Modifier
                    .padding(horizontal = tokens.spacing.md)
                    .semantics(Semantics(role = Role.Img, label = image.alt)),
            ) {
                Image(image.src, width = IMAGE_WIDTH, height = IMAGE_HEIGHT)
            }
        }

        if (reply != null) {
            Row(
                spacing = tokens.spacing.sm,
                modifier = Modifier.hExpand().padding(horizontal = tokens.spacing.md, vertical = tokens.spacing.sm),
            ) {
                Entry(
                    value = draft,
                    placeholder = reply.placeholder,
                    onValueChange = { text ->
                        draft = text
                        reply.onDraftChange(text)
                    },
                    modifier = Modifier
                        .hExpand()
                        .uFill(tokens.colors.background, border = tokens.colors.input, radius = tokens.radius.md)
                        .uTextColor(tokens.colors.popoverForeground)
                        .entryStyle(
                            textColor = tokens.colors.popoverForeground,
                            caretColor = tokens.colors.foreground,
                            selectionBackground = tokens.colors.primary,
                            selectionColor = tokens.colors.primaryForeground,
                            transitionMs = tokens.motion.themeMs,
                            placeholderColor = tokens.colors.mutedForeground,
                        )
                        .padding(horizontal = tokens.spacing.sm, vertical = tokens.spacing.xs)
                        .semantics(Semantics(role = Role.TextBox, label = reply.placeholder))
                        .uFocusRing()
                        // The field itself would take Enter and Escape: they are read first here.
                        .onKey(capture = true) { event ->
                            when (event.key) {
                                Key.Enter -> {
                                    send()
                                    true
                                }
                                Key.Escape -> {
                                    focus.requestFocus()
                                    true
                                }
                                else -> false
                            }
                        },
                )
                UButton(
                    reply.sendLabel,
                    onClick = { send() },
                    size = USize.Sm,
                    disabled = draft.isBlank(),
                    trailingIcon = Lucide.ArrowRight,
                )
            }
        }

        if (actions.isNotEmpty()) {
            Row(
                spacing = tokens.spacing.sm,
                modifier = Modifier.hExpand().padding(horizontal = tokens.spacing.md, vertical = tokens.spacing.sm),
            ) {
                actions.forEachIndexed { index, action ->
                    UButton(
                        action.label,
                        onClick = action.onClick,
                        modifier = action.testTag?.let { Modifier.testTag(it) } ?: Modifier,
                        color = action.color ?: UColor.Neutral,
                        variant = if (index == 0) UVariant.Soft else UVariant.Ghost,
                        size = USize.Sm,
                        leadingIcon = action.icon,
                    )
                }
            }
        }

        if (progress != null) {
            val share = progress.coerceIn(0f, 1f)
            Row(modifier = Modifier.hExpand().height(BAR_HEIGHT).then(themed.progress).then(ui.progress)) {
                if (share > 0f) {
                    Box(Modifier.weight(share.toDouble()).uFill(tokens.colors.foreground.withAlpha(0.6f)))
                }
                if (share < 1f) Box(Modifier.weight((1f - share).toDouble()))
            }
        }
    }
}
