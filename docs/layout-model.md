# Layout model

What GTK guarantees, what this framework emulates, and what you should not expect. Every
statement below is pinned by a test in `framework/src/nativeTest/kotlin/dev/knshell/compose/LayoutProbeGraphicsTest.kt`
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

**Cross-axis negotiation.** A box asked for its size on the *cross* axis at a given main size has
to split that main size between children first — into the same shares `allocate()` will later hand
out — and only then measure each child's cross axis at its own share. That is what
`gtk_box_layout_compute_opposite_size_for_size()` does in GTK and `kn_cross_size_for_main()` does
here. Forwarding the container's whole main size to every child instead is invisible until a child
turns a size into a shape: seven `aspectRatio(1f)` children in a 1336px-tall column each answered
1336px of width, the window therefore asked for 9400px, and the UI could not follow the window
width at all.

## The four size knobs, and what each really means

| Modifier | GTK field | Semantics |
| --- | --- | --- |
| `size(w, h)` / `width` / `height` | `gtk_widget_set_size_request` | a **minimum**, not a size: the widget never gets smaller, but a parent may give it more |
| `requiredSize(w, h)` / `requiredWidth` / `requiredHeight` | size request **+** `Align.Start` on the named axes | an **exact** size (compose's `requiredSize`): the parent cannot stretch those axes, so `requiredSize(44)` stays 44×44 in a taller `Row` and a chip keeps its 1:1 |
| `aspectRatio(r)` | per-child `ratio` in `KnBoxLayout` | keeps a shape instead of a number: the **cross** axis decides (a `Fill` child takes the container's whole cross size) and the **main** axis becomes `cross * r`. So `aspectRatio(1f)` in a `Row` is "as tall as the row, and exactly that wide". An `hExpand()` on the same child is ignored — the ratio already owns those pixels. A `weight` is not: when the box is asked for its cross size, a weighted ratio child answers `share / r` rather than its content height (CSS `flex: 1; aspect-ratio: 1`), so seven `weight(1.0).aspectRatio(1f)` swatches tile the row as squares instead of shrinking to the label height |
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
`hexpand`, `vexpand`, `halign`, `valign`, `opacity`, `size_request` a chain touched. A field that no chain ever
wrote is never touched, so a writer outside the pipeline keeps its value — `GtkOverlayBaseNode`
centres its `Overlay` children, `GtkCenterRegionNode` positions slots. This is why
`Modifier.padding(all = 4)` on an icon does not move it back to `Fill`.

A widget that has an intrinsic size of its own (`Image`, `ShaderCanvas`) pins it the same way, from
inside the pipeline: the node prepends `Modifier.size(w, h).align(…)` to the caller's chain, so the
pin is the first writer and the caller's own modifier still wins. Dropping `size()` from a chain
resets the request to the natural size (`kn-modifier-size`), exactly like the other fields.

## Choosing a container

| You want | Use |
| --- | --- |
| a line or a column of widgets | `Row` / `Column` (spacing, `weight`, caps) |
| generic block flow (the "div") | `Box` — children stack like block elements, **not** Compose's `Box` |
| start / centre / end layout | `CenterBox` with named regions |
| the largest child, painted on top of each other | `Stack` (children `align` inside one cell) |
| a badge, counter or icon that must **not** size its container | `Overlay` (children never contribute; combine with `size()` + `clipOverflow()`) |
| cairo / GL drawing | `Canvas` / `ShaderCanvas` |

**`align` needs a cell to move inside.** On the cross axis of a `Row`/`Column`/`Box` the
manager hands the child the full cross extent, so `align(v = Center)` works. On the *main*
axis children are packed — a non-expanding child owns exactly its natural size — so aligning it
has nothing to move in. Centring a 22px icon inside a 44px chip therefore wants `Stack`
(every child gets the whole cell), not `Box`:

```kotlin
Stack(Modifier.size(44).radius(12).background(c.accent)) {
    Icon(Lucide.Home, size = 22, color = c.bg, modifier = Modifier.align(h = Align.Center, v = Align.Center))
}
```

`Overlay` deliberately sets no expand flag and `Stack` sets none either: `GtkBox` aggregates the
expand flags of its children, so an expanding spacer would stretch its whole row. Ask for
expansion explicitly with `Modifier.align(h = Align.Fill)` / `fillWidth()`.

## CenterBox: who gets the width

`CenterBox` is `GtkCenterBox` whose three slots are real region boxes (`GtkCenterRegionNode`, a
horizontal `GtkBox` with `KnBoxLayout` each). The allocation itself is GTK's
`gtk_center_layout_distribute()` (GTK 4.12+) — the framework only routes a child into the slot its
node names (`ComposeNodes.kt`, `GtkCenterBoxNode.place`). Nothing about centring is ours.

Given the box width `W`, the CSS `border-spacing` `s` (0 by default, the plain reset does not set
it) and the `(min, natural)` of each visible region, the four steps run in this order:

1. **Centre first, against the sides' *minimums*.**
   `centre = clamp(W − s − (start.min + end.min), centre.min, centre.nat)`.
   `shrink-center-last` is `TRUE` by default, so the upper bound is the centre's natural width: the
   centre keeps it while the sides shrink, and only shrinks itself once they sit at their minimums.
2. **Sides next, against the centre and the other side's minimum.**
   `start = clamp(W − s − (centre + end.min), start.min, start.nat)`, symmetric for `end`. A side
   never exceeds its natural width at this step.
3. **Position: the centre is centred on the whole width.**
   `x = W/2 − centre/2`. If that would overlap a side, the centre is pushed in instead:
   `x = start + s` when `start + s > x`, otherwise `x = W − centre − end − s` when the end would
   overlap. The start check is tried first.
4. **Expanders take the leftover, up to the centre.**
   A region whose child computes `expand` gets everything between it and the centre:
   `start = x − s`, `end = W − (x + centre) − s`. An expanding *centre* instead spans
   `W − 2·max(start, end)` (symmetric margins). With no centre child the whole leftover goes to an
   expanding start, else to an expanding end, and is split in half when both expand.

Consequences that matter in a bar:

* The centre pill sits at the middle of the **surface**, not of the free space: an asymmetric start
  group does not shift it until it grows wide enough to collide with it.
* `W` is decided one level up — `Modifier.fillWidth()` or layer-shell anchors — because the box's
  own measure is `natural = centre.nat + 2·max(start.nat, end.nat)`: the measure pass assumes
  symmetric sides, so a `CenterBox` never reports a content width.
* A region keeps its natural width unless a child inside it expands (`fillWidth()`, `hExpand()`,
  `weight`). `weight` splits the *region's* leftover and cannot cross the region boundary.
* Tight space squeezes the sides to their minimums first, then the centre to its own minimum. The
  probe (`LayoutProbeGraphicsTest.kt:309`, "CenterBox cannot express which side is squeezed first") pins
  that `shrink-center-last` changes nothing for a centre that already fits its minimum: the centre
  always receives `W − start − end`.
* Spacing between regions is GTK's `border-spacing`, not a Kotlin parameter — `CenterBox` has no
  `spacing` argument; use `Row(spacing = …)` *inside* a region.

## What is emulated, and what cannot work

* **`weight` and `aspectRatio` are not native.** They live in `KnBoxLayout`, so they only work inside `Row`, `Column`,
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
* `LayoutProbeGraphicsTest.kt` helpers are the quickest way to ask GTK a direct question:
  `gtk_widget_measure`, then `allocate(widget, w, h)` (a test-only helper that sizes an unmapped
  widget) and `allocRect(child)`.
* An `Overlay` — like any container that leaves `gtk_overlay_set_measure_overlay()` off — adds no
  size of its own. `Box { Overlay { Label } }` therefore measures its border and nothing else, and
  a row of those collapses to 2px. `Stack` is the one that sizes to what it stacks.
* GTK's own warnings are meaningful: `Trying to measure … for width of N, but it needs at least
  M` means a minimum beat a cap or a cell.
