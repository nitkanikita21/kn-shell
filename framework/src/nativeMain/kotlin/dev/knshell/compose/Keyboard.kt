// ---------------------------------------------------------------------------
// Keys — what was pressed, as widgets receive it.
// ---------------------------------------------------------------------------

@file:OptIn(ExperimentalForeignApi::class)

package dev.knshell.compose

import kotlinx.cinterop.ExperimentalForeignApi
import kn.gtk.GDK_ALT_MASK
import kn.gtk.GDK_CONTROL_MASK
import kn.gtk.GDK_SHIFT_MASK
import kn.gtk.GDK_SUPER_MASK
import kn.gtk.gdk_keyval_to_unicode

/** The keys components react to. Anything that prints is [Character]. */
enum class Key {
    Enter,
    Space,
    Escape,
    Tab,
    Up,
    Down,
    Left,
    Right,
    Home,
    End,
    PageUp,
    PageDown,
    Backspace,
    Delete,
    Menu,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,

    /** A printing key; see [KeyEvent.character]. */
    Character,

    /** Any other key, modifiers pressed on their own included. */
    Unknown,
}

/** A key going down on a widget or inside it. */
data class KeyEvent(
    val key: Key,
    /** What the key prints, for [Key.Character] and [Key.Space]. */
    val character: Char? = null,
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
)

/**
 * Handles keys of the widget and of everything focused inside it: [handler] returns `true` for a key
 * it used, which stops there. With [capture] the handler sees the key before the focused widget
 * does; without it, only what the focused widget left over.
 */
fun Modifier.onKey(capture: Boolean = false, handler: (KeyEvent) -> Boolean): Modifier =
    with(KeyElement(handler, capture))

internal data class KeyElement(val handler: (KeyEvent) -> Boolean, val capture: Boolean) : ModifierElement

internal fun keyEvent(keyval: UInt, state: UInt): KeyEvent {
    fun held(mask: UInt) = state and mask != 0u
    // X11 keysyms, which GDK key values are.
    val key = when (keyval) {
        0xff0du, 0xff8du, 0xfe34u -> Key.Enter
        0x20u -> Key.Space
        0xff1bu -> Key.Escape
        0xff09u, 0xfe20u -> Key.Tab
        0xff52u, 0xff97u -> Key.Up
        0xff54u, 0xff99u -> Key.Down
        0xff51u, 0xff96u -> Key.Left
        0xff53u, 0xff98u -> Key.Right
        0xff50u, 0xff95u -> Key.Home
        0xff57u, 0xff9cu -> Key.End
        0xff55u, 0xff9au -> Key.PageUp
        0xff56u, 0xff9bu -> Key.PageDown
        0xff08u -> Key.Backspace
        0xffffu, 0xff9fu -> Key.Delete
        0xff67u -> Key.Menu
        in 0xffbeu..0xffc9u -> Key.entries[Key.F1.ordinal + (keyval - 0xffbeu).toInt()]
        else -> null
    }
    val code = gdk_keyval_to_unicode(keyval).toInt()
    val character = code.takeIf { it in 32..0xFFFF }?.toChar()
    return KeyEvent(
        key = key ?: if (character != null) Key.Character else Key.Unknown,
        character = if (key == null || key == Key.Space) character else null,
        shift = held(GDK_SHIFT_MASK),
        ctrl = held(GDK_CONTROL_MASK),
        alt = held(GDK_ALT_MASK),
        meta = held(GDK_SUPER_MASK),
    )
}
