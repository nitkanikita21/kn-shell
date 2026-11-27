package dev.knshell.uikit.components.element

import androidx.compose.runtime.Composable
import dev.knshell.compose.Box
import dev.knshell.compose.align
import dev.knshell.compose.Align
import dev.knshell.compose.Icon
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.Role
import dev.knshell.compose.Semantics
import dev.knshell.compose.weight
import dev.knshell.compose.clipOverflow
import dev.knshell.compose.radius
import dev.knshell.compose.semantics
import dev.knshell.compose.semiBold
import dev.knshell.compose.size
import dev.knshell.icons.IconRef
import dev.knshell.ui.primitives.Image
import dev.knshell.uikit.core.USize
import dev.knshell.uikit.core.metrics
import dev.knshell.uikit.core.uFill
import dev.knshell.uikit.core.uFont
import dev.knshell.uikit.core.uTextColor
import dev.knshell.uikit.theme.UComponentDefaults
import dev.knshell.uikit.theme.UTheme

/** Slots of [UAvatar] an application can restyle. */
data class UAvatarUi(
    /** The circle itself. */
    val base: Modifier = Modifier,
    /** The initials, when the avatar shows no picture or icon. */
    val initials: Modifier = Modifier,
    /** The picture, when [UAvatar] gets a `src`. */
    val image: Modifier = Modifier,
)

data class UAvatarDefaults(
    val size: USize = USize.Md,
    val ui: UAvatarUi = UAvatarUi(),
) : UComponentDefaults

/** The avatar defaults of the current theme. */
@Composable
fun uAvatarDefaults(): UAvatarDefaults = UTheme.config.find() ?: UAvatarDefaults()

/** At most this many initials are shown. */
private const val MAX_INITIALS = 2

/**
 * A round picture of a person or an application. With [src] it shows the picture; otherwise [icon],
 * or else the initials of [name] (the first letter of up to two words). [name] is the accessible name
 * of the image and is required. Takes no input.
 */
@Composable
fun UAvatar(
    name: String,
    modifier: Modifier = Modifier,
    src: String? = null,
    icon: IconRef? = null,
    size: USize = uAvatarDefaults().size,
    ui: UAvatarUi = UAvatarUi(),
) {
    require(name.isNotBlank()) { "a UAvatar needs a name: it is its accessible name" }
    val tokens = UTheme.tokens
    val themed = uAvatarDefaults().ui
    val metrics = size.metrics
    val diameter = metrics.height
    val initials = name.trim().split(Regex("\\s+"))
        .take(MAX_INITIALS)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")

    Box(
        modifier = Modifier
            .size(width = diameter, height = diameter)
            .uFill(tokens.colors.accent, radius = diameter / 2)
            .radius(diameter / 2)
            .clipOverflow()
            .uTextColor(tokens.colors.accentForeground)
            .uFont(metrics.fontSize)
            .semiBold()
            .semantics(Semantics(role = Role.Img, label = name))
            .then(themed.base)
            .then(ui.base)
            .then(modifier),
    ) {
        when {
            src != null -> Image(
                path = src,
                modifier = Modifier.radius(diameter / 2).clipOverflow().then(themed.image).then(ui.image),
                width = diameter,
                height = diameter,
            )
            icon != null -> Icon(
                icon,
                modifier = Modifier.weight().align(Align.Center),
                size = metrics.icon,
                color = tokens.colors.accentForeground,
            )
            else -> Label(
                initials,
                align = 0.5f,
                modifier = Modifier.weight().align(Align.Center).then(themed.initials).then(ui.initials),
            )
        }
    }
}
