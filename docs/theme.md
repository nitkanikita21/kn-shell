# Theme: palette in Rust, style in Kotlin

The split is the shadcn one: **something reads the picture, something decides the design.**

- `palette-core` (Rust) answers *what colour is the wallpaper*: dominant clusters, a
  suggested hue, a suggested chroma, a suggested mode. It knows nothing about the UI.
- `shell-app/.../theme/ThemeScale.kt` (Kotlin) owns *how the UI looks*: the full semantic token
  set, derived from that hue/chroma plus the user's dark/light choice.

Nothing about the UI can be changed in Rust, and nothing about the wallpaper is re-derived in
Kotlin.

## Tokens

`ThemeTokens` mirrors shadcn's naming, so a component can be reasoned about the same way it
would be on the web:

| Token | Role |
| --- | --- |
| `background` | the window itself |
| `foreground` | default text on `background` |
| `card` / `cardForeground` | raised surfaces: cards, panels |
| `popover` / `popoverForeground` | floating surfaces: menus, tooltips |
| `primary` / `primaryForeground` | the brand colour from the wallpaper: selected tab, filled button |
| `secondary` / `secondaryForeground` | second surface step: inactive tab, well, progress track |
| `muted` / `mutedForeground` | recessed area and secondary text |
| `accent` / `accentForeground` | hover/pressed wash — **not** the brand colour |
| `destructive` / `destructiveForeground` | errors; fixed, does not drift with the wallpaper |
| `border` | hairlines between surfaces |
| `input` | form-control outlines (slightly stronger than `border`) |
| `ring` | focus ring |

`chart-*` and `sidebar-*` are deliberately absent: the shell has neither charts nor a sidebar,
and an unused token is a token nobody keeps correct.

`ThemeTokens.entries()` returns the set in shadcn order; the demo's swatches iterate it, so a
token added to the theme cannot be missing from the specimen.

## Derivation rules

- `primary` uses the wallpaper's own chroma, `×2.5`, capped at `0.18` — a muted wallpaper gets
  a muted accent, a neon one still cannot produce a glowing button.
- In light mode `primary` drops to lightness `0.55`: the dark theme's `0.705` is unreadable on
  white.
- Neutrals keep a sliver of the wallpaper hue (`NEUTRAL_CHROMA = 0.008`) instead of being pure
  grey, so the shell reads as one piece with its wallpaper.
- In the dark theme `border`/`input` are translucent hairlines, so the wallpaper shows through
  the shell's edges; in the light theme they are solid.
- `destructive` is fixed (hue 25/27) on purpose: an error colour that follows the wallpaper is
  not an error colour.

## Mode

`ThemeMode` (`Auto` / `Dark` / `Light`) is the user's override. `Auto` follows
`palette-core`'s `suggested_mode`; the other two win, because a dark UI can sit perfectly on a
bright wallpaper. `ThemeService.setMode` re-derives the tokens from the last palette, so
switching costs no wallpaper read.
