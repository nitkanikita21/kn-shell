// ---------------------------------------------------------------------------
// The vocabulary every component's API shares.
// ---------------------------------------------------------------------------

package dev.knshell.uikit.core

import dev.knshell.icons.IconRef

/** The meaning of a colour; the theme decides which colour that is. */
enum class UColor { Primary, Secondary, Success, Info, Warning, Error, Neutral }

/** How much a component stands out. */
enum class UVariant { Solid, Outline, Soft, Subtle, Ghost, Link }

enum class USize { Xs, Sm, Md, Lg, Xl }

enum class UOrientation { Horizontal, Vertical }

/** On which side of its trigger a floating part opens. */
enum class USide { Top, Bottom, Left, Right }

/** Which edge of a floating part lines up with its trigger. */
enum class UAlign { Start, Center, End }

/** A button a component offers inside itself, such as the actions of an alert or a toast. */
data class UAction(
    val label: String,
    val icon: IconRef? = null,
    /** `null` takes the colour of the component. */
    val color: UColor? = null,
    val testTag: String? = null,
    val onClick: () -> Unit,
)
