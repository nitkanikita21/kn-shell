# Theme: palette in Rust, style in Kotlin

The split is the shadcn one: **something reads the picture, something decides the design.**

- `palette-core` (Rust) answers *what colour is the wallpaper*: dominant clusters (each with
  lightness, chroma, hue and its share of the image), a suggested hue, a suggested chroma, a
  suggested mode. It knows nothing about the UI.
- `shell-app/.../theme/WallpaperPalette.kt` (Kotlin) is that reply as a design input: one hue,
  how saturated *that hue family* gets, an optional second family, and how bright the wallpaper
  is overall.
- `shell-app/.../theme/ThemeScale.kt` (Kotlin) owns *how the UI looks*: the full semantic token
  set, derived from the palette plus the user's dark/light choice.
- `shell-app/.../theme/ThemeTuning.kt` (Kotlin) owns *how loud* the result is.

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
| `destructive` / `destructiveForeground` | errors; red, nudged towards the wallpaper's hue |
| `border` | hairlines between surfaces |
| `input` | form-control outlines (slightly stronger than `border`) |
| `ring` | focus ring |

`chart-*` and `sidebar-*` are deliberately absent: the shell has neither charts nor a sidebar,
and an unused token is a token nobody keeps correct.

`ThemeTokens.entries()` returns the set in shadcn order; the demo's swatches iterate it, so a
token added to the theme cannot be missing from the specimen.

## The palette is more than a hue

`WallpaperPalette` keeps the clusters, because the two numbers the shell used to pass around
cannot answer the question the theme actually asks:

- `suggested_chroma` is a **weighted mean** over the chromatic clusters. A wallpaper that is half
  saturated green and half grey reports a small chroma, so the UI comes out grey exactly when the
  wallpaper is bold. Every measured wallpaper (6 of them, `~/Pictures/wallpapers`) reports
  `suggested_chroma` between `0.008` and `0.092`.
- `familyChroma` is the **maximum** chroma among clusters within `±30°` of the dominant hue —
  "how colourful does this hue get here?" — and lands between `0.010` and `0.187` on the same six.
  Surfaces are built from it. It is independent of area coverage and exactly `0` for a genuinely
  grey wallpaper, so nothing invents a tint.
- `secondHue` is the heaviest cluster at least `40°` away (and `c ≥ 0.02`), `null` when the
  wallpaper is monochromatic. `secondary`/`muted`/`ring` lean towards it, so a two-tone wallpaper
  produces a two-tone UI instead of flattening to one colour.
- `dominantLightness` is the lightness of the cluster that covers the most pixels; it shifts the
  surface ramp by at most `±0.018`, far too little to disturb contrast but enough that a
  near-black photo does not get a bright-on-bright shell.

## Derivation rules

Everything is OKLCH: `L` comes from a fixed ramp, `h`/`C` from the wallpaper. Every colour is
built with `Color.oklchFitted` (see below), so a request that does not fit sRGB loses chroma
instead of drifting to another hue.

Chroma inputs, in `ThemeScale`:

| Input | Formula |
| --- | --- |
| `neutral` (surfaces) | `clamp(family × 0.90 × tint, 0.015 × tint, cap)` |
| `tint` (`accent`, `ring`, secondary text) | `clamp(family × 1.10 × tint, neutral × 1.5, cap)` |
| `second` (`secondary`, `muted`) | `clamp(secondFamily × 0.90 × 1.25 × tint, neutral × 1.2, cap × 1.3)` |
| `brand` (`primary`) | `min(max(meanChroma, family × 0.8) × 2.5 × accent, 0.18)` |
| `destructive` | `clamp(meanChroma × 1.5, 0.15, 0.21)` dark · `clamp(meanChroma × 1.6, 0.17, 0.245)` light |

Dark surfaces (`background` / `card` / `secondary` / `accent`), with `lift = tuning.lift` (0.022)
and `pull` the dominant-lightness shift:

- `background` L `0.145 + lift + pull × 0.6`
- `card` L `0.225 + lift × 0.9 + pull × 0.7` (≈ 0.08 above `background`; was 0.205, merged with the window)
- `secondary`/`muted` L `0.31 + lift × 1.1 + pull × 0.8`
- `accent` L `0.371 + lift × 1.3 + pull` — shadcn's hover wash, one step above `secondary`
- `foreground` L `0.985`, `mutedForeground` L `0.708`, `ring` L `0.556`, `primary` L `0.705`

Light mode:

- `background` L `0.935 − lift × 0.6`, chroma `neutral × 0.80` — the window is tinted too; a pure
  white window next to a coloured panel is what makes a light theme feel bolted onto its wallpaper
- `card` L `0.998 − lift × 0.25`, chroma `neutral × 0.45`
- `secondary`/`muted` L `0.905 − lift × 0.9`, `accent` L `0.865 − lift × 0.5` (ramp steps ≈ 0.07 / 0.04 / 0.03)
- `primary` L `0.55`: the dark theme's `0.705` is unreadable on white
- `foreground` L `0.205`, `mutedForeground` L `0.50`, `ring` L `0.55`

Other rules:

- In the dark theme `border`/`input` are translucent, **tinted** hairlines (L `0.62`/`0.68`,
  alpha `0.22`/`0.28`), so the wallpaper shows through the shell's edges and the edge belongs to
  the same colour family. In the light theme they are solid, L `0.84`.
- `destructive` is red, but not a fixed red: its hue starts at `25°` and follows the wallpaper by
  `0.35` of the way, clamped to `±12°`, and only for warm wallpapers (`|Δh| ≤ 90°`). A cold
  wallpaper cannot drag an error colour to teal, and a warm one gets a red that belongs.

## Gamut fitting

`Color.oklch(l, c, h)` clips each channel on its own: out of gamut, the colour comes back as a
*different* colour — the hue drifts, and saturated hues (green worst) lose most of their punch.
The theme hits this constantly, because dark surfaces sit at L ≈ 0.16 where sRGB allows only:

| Hue | max chroma @ L 0.14 | @ 0.167 | @ 0.24 | @ 0.30 |
| --- | --- | --- | --- | --- |
| 126° (green) | 0.035 | 0.042 | 0.060 | 0.075 |
| 253° (blue) | 0.043 | 0.051 | 0.073 | 0.091 |
| 295° (violet) | 0.076 | 0.090 | 0.130 | 0.162 |

`Color.oklchFitted` keeps `L` and `h` exactly and bisects `c` (20 steps) down to the most
saturated colour that is still representable. That is why a surface cap above ~0.05 does nothing
on its own: the slider has to raise `lift`, not the cap.

## Tint tuning

`ThemeTuning` is the only place that decides how loud the result is — the derivation multiplies
and caps, it never picks a hue:

| Knob | Meaning |
| --- | --- |
| `tint` | chroma multiplier for surfaces (`0` = no wallpaper tint at all) |
| `accent` | chroma multiplier for `primary`/`ring` |
| `lift` | lightness lift of the dark surfaces (subtracted in light mode) |
| `neutralCap` / `accentCap` | ceilings for surface / accent chroma |
| `secondHue` | `0` keeps one hue, `1` uses the wallpaper's second family fully |

| Preset | tint | accent | lift | neutralCap | accentCap |
| --- | --- | --- | --- | --- | --- |
| `Off` | 0 | 1 | 0.022 | 0.055 | 0.070 |
| `Subtle` | 0.55 | 0.85 | 0.022 | 0.030 | 0.070 |
| `Balanced` (default) | 1 | 1 | 0.022 | 0.055 | 0.070 |
| `Vivid` | 1.55 | 1.35 | 0.045 | 0.090 | 0.100 |

`ThemeService.setTuning` re-derives from the cached palette, so switching presets or dragging the
slider costs no wallpaper read and needs no restart. The demo's Settings tab drives it.

## Mode

`ThemeMode` (`Auto` / `Dark` / `Light`) is the user's override. `Auto` follows
`palette-core`'s `suggested_mode`; the other two win, because a dark UI can sit perfectly on a
bright wallpaper. `ThemeService.setMode` re-derives the tokens from the last palette, so
switching costs no wallpaper read.
