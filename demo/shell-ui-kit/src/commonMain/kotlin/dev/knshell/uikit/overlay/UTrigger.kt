package dev.knshell.uikit.overlay

import dev.knshell.compose.Modifier

/**
 * What the trigger of a floating part (menu, popover, tooltip) applies to itself: [modifier] to the
 * widget that takes the focus, which carries the ARIA state, keys and focus handling, and
 * [onClick] to run when it is activated.
 *
 * ```kotlin
 * UDropdownMenu(items) { trigger -> UButton("Меню", onClick = trigger.onClick, modifier = trigger.modifier) }
 * ```
 */
class UTrigger(val modifier: Modifier, val onClick: () -> Unit)
