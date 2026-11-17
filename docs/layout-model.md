# Layout model

What GTK guarantees, what this framework emulates, and what you should not expect. Every
statement below is pinned by a test in `framework/src/nativeTest/kotlin/dev/knshell/compose/LayoutProbeTest.kt`
or by a comment in `rust-core/src/kn_box_layout.c`; the probe is the executable form of this page.

## Who measures

GTK negotiates size **bottom-up**: a widget answers `(minimum, natural)` from `measure()`, the
parent decides how to spend the difference in `allocate()`, and the child never learns how much
room is left over. Compose layouts work top-down from constraints. The bridge keeps GTK's model
and adds only where a Compose expectation cannot be expressed:

| Container | Measured by | Notes |
| --- | --- | --- |
| `Row`, `Column`, `Box` | `KnBoxLayout` (C, `rust-core/src/kn_box_layout.c`) | the only place `weight`, `maxWidth`/`maxHeight` and homogeneous cells exist |
| `CenterBox` | three inner `GtkBox`es, each with `KnBoxLayout` | a slot is a real box, so a region holding several widgets still gets one cell |
| `Stack`, `Overlay` | `GtkOverlay` | `Stack` turns `measure-overlay` on per child, `Overlay` leaves it off |
| `animatedWidth` / `animatedHeight` / `animatedSize` | `KnAnimatedSizeLayout` (C) | reports the applied value as both minimum and natural, so growth animates instead of being clipped |
| anything else (`Label`, `Button`, `Slider`, `Canvas`, images) | stock GTK | |

A container that is not one of the above is a plain GTK widget: no `weight`, no cap, no
alignment cell.

## The four size knobs, and what each really means

| Modifier | GTK field | Semantics |
| --- | --- | --- |
| `size(w, h)` / `width` / `height` | `gtk_widget_set_size_request` | a **minimum**, not a size: the widget never gets smaller, but a parent may give it more |
| `minWidth` / `minHeight` / `minSize` | CSS `min-width` / `min-height` | also a minimum, applied by the theme layer after the plain reset removed the theme's own |
| `maxWidth` / `maxHeight` / `maxSize` | *no GTK equivalent* | a **ceiling**, emulated: it clamps an explicit `size()` request and, inside a `KnBoxLayout` container, the width the manager hands out. Clamped to the child's own minimum (`min` beats `max`, as in CSS) |
| `align(h, v)` | `gtk_widget_set_halign` / `valign` | picks a position inside the cell the parent hands out. `Fill` = take the cell. `Center`/`End` = natural size, aligned |
| `hExpand` / `vExpand` / `expand` | `gtk_widget_set_hexpand` / `vexpand` | **requests** surplus from the parent; inside a `KnBoxLayout` container this is what makes a child grow |
| `weight(n)` (`Modifier.weight`) | per-child `weight` in `KnBoxLayout` | splits the **leftover** of the main axis proportionally (Android semantics). Absent weight = `0`, i.e. natural size only |
| `padding` | CSS padding | inside the border, included in the measured size |
| `margin` | `gtk_widget_set_margin_*` | outside the border, **adds** to the measured size |
| `opacity` | `gtk_widget_set_opacity` | paint only, no layout effect |
| `clipOverflow` | `gtk_widget_set_overflow(HIDDEN)` | clips children (including `Overlay`/`Stack` children) to the allocation |

### Ownership of the layout fields

`applyModifiers` runs on every recomposition and resets only what a modifier chain *previously
wrote*: the widget itself remembers (`g_object_set_data`, keys `kn-modifier-*`) which of
`hexpand`, `vexpand`, `halign`, `valign`, `opacity` a chain touched. A field that no chain ever
wrote is never touched, so a writer outside the pipeline keeps its value — `Image` pins itself to
`halign/valign = START`, `GtkOverlayNode` centres its children, `GtkCenterRegionNode` positions
slots. This is why `Modifier.padding(all = 4)` on an icon does not move it back to `Fill`.

## Choosing a container

| You want | Use |
| --- | --- |
| a line or a column of widgets | `Row` / `Column` (spacing, `weight`, caps) |
| generic block flow (the "div") | `Box` — children stack like block elements, **not** Compose's `Box` |
| start / centre / end layout | `CenterBox` with named regions |
| the largest child, painted on top of each other | `Stack` (children `align` inside one cell) |
| a badge, counter or icon that must **not** size its container | `Overlay` (children never contribute; combine with `size()` + `clipOverflow()`) |
| cairo / GL drawing | `Canvas` / `ShaderCanvas` |

`Overlay` deliberately sets no expand flag and `Stack` sets none either: `GtkBox` aggregates the
expand flags of its children, so an expanding spacer would stretch its whole row. Ask for
expansion explicitly with `Modifier.align(h = Align.Fill)` / `fillWidth()`.

## What is emulated, and what cannot work

* **`weight` is not native.** It lives in `KnBoxLayout`, so it only works inside `Row`, `Column`,
  `Box` and a `CenterBox` region. A `Stack`/`Overlay` child cannot take a weight — those
  containers hand each child the same cell.
* **`maxWidth`/`maxHeight` are not native.** The interpreter is the layout manager; outside one,
  only an explicit `size()` request is capped. A cap also cannot make a child *shrink* text it
  refuses to shrink: give the label `ellipsize`/`wrap` first, otherwise `min` wins and the cap
  does nothing (GTK would warn `…needs at least N…`).
* **`align` needs a cell.** `Center`/`End` on a widget whose parent hands it its own natural size
  (stock GTK containers, or a `Stack` that measured it) has nothing to align inside.
* **Percentages of the parent are not available.** There is no `fillMaxWidth(0.5f)`. Either take
  a `weight` share or bind the size to the window width from a service.
* **No baselines, no intrinsic ratios, no wrap-content two-pass.** A single bottom-up pass
  measures; anything needing a second pass with the final size (true "as wide as the tallest
  sibling", aspect-ratio boxes) has to be computed in Kotlin and passed as a size.
* **Content does not reflow on shrink.** A `GtkLabel` with `ellipsize` reports a small minimum and
  is clipped/ellipsized; a label without it reports its full width as the minimum and therefore
  forces its parent wider. `Stack` and `Overlay` follow the same rule (natural size, clipped
  inside the cell).
* **Text has a floor.** A 10px label still reports ~17px height and a few px of width: sizes below
  that are honoured as minimums and the paint overflows. That is what `Overlay` + `clipOverflow()`
  is for.

## Debugging a layout

* `GTK_DEBUG=layout` (GTK's own switch) prints measure/allocate for every widget in the tree.
* `LayoutProbeTest.kt` helpers are the quickest way to ask GTK a direct question:
  `gtk_widget_measure`, then `allocate(widget, w, h)` (a test-only helper that sizes an unmapped
  widget) and `allocRect(child)`.
* GTK's own warnings are meaningful: `Trying to measure … for width of N, but it needs at least
  M` means a minimum beat a cap or a cell.
