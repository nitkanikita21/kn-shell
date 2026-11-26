# framework — module map

The library every shell links against: the GTK4 bridge, the Compose-style DSL, the window
runtime and the reactive wrappers around `:rust-core`. One process owns exactly one GTK init,
GLib loop, frame clock, recomposer and service registry (`Shell.run`).

User-facing contract: [`docs/framework-api.md`](../docs/framework-api.md) (API),
[`docs/layout-model.md`](../docs/layout-model.md) (measurement rules),
[`docs/shell-api.md`](../docs/shell-api.md) (windows/architecture). This file is the internal
index: what lives where, and where a new thing belongs.

## Packages

All Kotlin lives in `src/nativeMain/kotlin/dev/knshell/`.

| Package / dir | Owns | Public surface |
| --- | --- | --- |
| `compose/` | the DSL: `GtkNode` tree + `GtkApplier`, the `Modifier` model and its CSS emission, layout containers, frame clock, plain-theme reset, cairo/GL canvases, the popover-free toplevel composition | everything a shell author writes: `Row`/`Column`/`Box`/`Stack`/`Overlay`/`CenterBox`/`Label`/`Button`/`Slider`/`Canvas`/`ShaderCanvas`, `Modifier` + extensions, `Color`, `Align`, `Corner` |
| `shell/` | window declaration and runtime: `Shell.run`, `ShellScope`, `barWindow`/`window` specs, layer-shell interop, GTK loop + recomposer + window registry, hover popovers | `Shell`, `ShellScope`, `BarWindowSpec`, `WindowSpec`, `WindowHandle`, `BarAnchor`, `BarLayer`, `ExclusiveZone`, `Margins`, `Popover`, `HoverPopover`, `PopoverController` |
| `services/` | thin reactive wrappers over the UniFFI listeners (`StateFlow`, lazy start, `Services.startAll()`) | the 10 service objects + `Services` |
| `animation/` | specs and drivers: `AnimationSpec` (`Tween`/`Spring`/`Snap`), `Easing`, `lerp`, spring/floats, Compose-owned container-size animation (registry, tween, animator) | `AnimationSpec`, `Easing`, `lerp`, `lerpColor`, `spring`, `animateFloatAsState`, `Animate` |
| `ui/` | formatting helpers (`Format.kt`) and the `primitives/` widgets that are not part of the layout core (`Text`, `Icon` lives in `:icons:core`, `Image`, `Spacer`/`Space`) | `Float.format`/`percent`/…, `Text`, `Image`, `Spacer`, `Space` |

## How a widget is built (the seam that matters)

Compose emits a `GtkNode` tree; `GtkApplier` (`compose/GtkApplier.kt`) creates, inserts and
updates GTK widgets, and each node's `applyModifier` pushes a `Modifier` chain into
(a) a stylesheet built by `compose/ModifierCss.kt` + `compose/InlineStyle.kt` and
(b) layout properties read by the native `KnBoxLayout` manager (`rust-core/src/kn_box_layout.c`).

Consequences worth knowing before editing:

* Adding a `Modifier` extension = one element class in `compose/ModifierElements.kt` + one
  `with(...)` extension in the matching `Modifier*` file + a CSS/GTK branch in
  `compose/ModifierCss.kt`. A modifier that only sets GTK properties does not need CSS.
* Adding a widget = a `GtkNode` subclass (`compose/ComposeNodes.kt` / `ComposeBasicNodes.kt`)
  + a `@Composable` factory in `compose/Widgets.kt`. Node classes are `internal`; only the
  composable is public.
* Every DSL widget carries the `PLAIN_CLASS` CSS class (`compose/ComposeNodes.kt: plain()`), so the GTK
  theme never paints anything and all visuals come from `Modifier`. `compose/Reset.kt` neutralises
  the theme globally and is installed by the runtime (`internal`, not API).
* Layout truth is the native manager, not GtkBox: `spacing`/`homogeneous` are pushed to it
  (`attachBoxLayout`, `setBoxSpacing`, `setBoxChildWeight`, …). Compose owns animated sizes;
  the manager only allocates. Details: `docs/layout-model.md`.
* All GTK calls happen on the GTK thread — the recomposer is fed through `GLibDispatcher`
  (`g_idle_add`) and frames come from the single `GtkFrameClock` (16 ms tick, zero frames when
  idle).

## Frozen by satellite modules (do not tighten)

`:icons:core` and the generated `:icons-packs:*` code are separate Gradle modules, and Kotlin has
no friend-module visibility, so these stay `public` and are the framework↔satellite seam:
`GtkNode`, `GtkApplier`, `applyModifierFromModule`, `plain()`, `Modifier`, `Color`, `Easing`,
`Easing.eased`, `lerpColor`. Everything else that no shell imports is `internal`.

## Tests

`src/nativeTest/kotlin/dev/knshell/` — `compose/LayoutProbeGraphicsTest.kt` is the executable layout
contract (1564 lines, drives the native manager), `animation/*` and `services/{Format,Spectrum}Test.kt`.
They live in the same module, so they may touch `internal` symbols.

```bash
./gradlew :framework:linuxX64Test     # note: there is no `nativeTest` task
./gradlew :framework:dokkaHtml        # KDoc site → framework/build/dokka/html
```

## Adding code: where it goes

| You are adding | Put it in |
| --- | --- |
| a styling/layout modifier | `compose/ModifierLayout.kt`, `ModifierPaint.kt` or `ModifierText.kt` + an element + a CSS branch |
| a leaf widget or container | `compose/ComposeBasicNodes.kt` (node) + `compose/Widgets.kt` (composable) |
| window/layer-shell behaviour | `shell/ShellRuntime.kt` (runtime) or `shell/LayerShell.kt` (gdk-layer-shell calls) |
| a system data feed | `services/<Name>Service.kt` + `Services.kt`; the Rust side is `rust-core/src/api.rs` |
| an easing/timing primitive | `animation/` — a spec in `AnimationSpec.kt`, a driver in `Spring.kt`/`Sync.kt` |
| anything a shell must call | public + KDoc + a row in `docs/framework-api.md` |
