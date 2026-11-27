package dev.knshell.uikit.theme

/**
 * Who decides between the dark and the light scale.
 *
 * palette-core guesses from the wallpaper (`suggested_mode`) and that guess is what [Auto]
 * follows. But a guess is not a preference: a dark UI can sit perfectly on a bright wallpaper
 * (and the reverse), so the palette must never be the last word. [Dark] and [Light] are the
 * user's override and win over whatever the wallpaper suggested.
 */
enum class ThemeMode {
    Auto,
    Dark,
    Light,
}
