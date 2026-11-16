# Framework API contract

`framework/` is the reusable library; `shell-app/` is one shell built on it. A third-party
shell only needs `:framework` (and gets `:rust-core` transitively).

## Modules

```
rust-core   Rust staticlib + UniFFI bindings (dev.knshell.core)
framework   GTK4 bridge, Compose DSL, shell runtime, services  ← library
shell-app   main() + your bar (TestBar, PlayerChip, …)          ← application
```

Dependency direction: `shell-app → framework → rust-core`. The framework never imports
application code.

## Public API (stable surface)

Everything below is public and safe to use; anything else (`GtkNode`, `GtkApplier`,
`InlineStyle`, `ShellRuntime`, `applyModifiers`, `PLAIN_CLASS`, `*Element` modifier classes)
is an implementation detail and may change.

### `dev.knshell.compose` — UI

| Symbol | Purpose |
| --- | --- |
| `Row`, `Column`, `Box`, `CenterBox`, `Overlay`, `Canvas`, `Slider`, `Label`, `Markup`, `Button` | layout and leaf widgets |
| `Modifier` + extensions | layout (`size`, `padding`, `margin`, `align`, `hExpand`, `fillWidth`, `minSize`, `maxWidth`), styling (`background`, `gradient`, `glow`, `radius`, `textColor`, `fontSize`, …), interaction (`onClick`, `onHover`, `cursor`, `pointerCursor`), animation (`transition`, `gradientStops`), `rawStyle`, `clipOverflow` |
| `Align`, `AnimatedAxis`, `Corner`, `Color` | supporting types |
| `animatedWidth`, `animatedHeight`, `animatedSize` (Modifier extensions) | Compose-owned size animation (AnimationSpec + GTK frame clock; passive native layout manager) |
| `Canvas`, `ShaderCanvas` | cairo / OpenGL drawing |
| `installPlainReset` | resets the GTK theme (called by the runtime) |

### Animated size (Compose-owned)

The size animation is a **`Modifier`** — there is no dedicated component. `animatedWidth` /
`animatedHeight` / `animatedSize` attach a passive native layout manager to the **container** the
modifier is applied to (a `Row`/`Column`), and Compose owns the tween, advanced by the shared GTK
frame clock. Apply it via the container's `modifier =` parameter:

| API | Purpose |
| --- | --- |
| `Modifier.animatedWidth(target = null, spec = Tween(), minMainSize = null, crossSize = null, spacing = 0)` | animates the **width** of the receiver container to follow its content, or to `target` |
| `Modifier.animatedHeight(…)` | animates the **height** of the receiver container |
| `Modifier.animatedSize(axis, target, spec, minMainSize, crossSize, spacing)` | `axis = AnimatedAxis.Width/Height`; the general form |
| `AnimationSpec.Tween(durationMs = 180, easing = Easing.OutCubic)` | default; monotonic |
| `AnimationSpec.Spring(stiffness, dampingRatio, visibilityThreshold)` | opt-in; leaves/indicators only |
| `AnimationSpec.Snap` | no animation, no frames requested |

Semantics: with `target = null` the target is the children's **natural** size (`for_size = -1`)
plus the container's spacing, so it does not depend on the current animated value (no feedback
loop). `target` pins the main axis to an exact size; `minMainSize` enforces a minimum; `crossSize`
pins the cross axis. Changing any of these or `spec` retargets **from the current value**. Children
keep their natural size and the container clips (`GTK_OVERFLOW_HIDDEN`): content is revealed, not
reflowed. Float→int rounding happens only in the native allocation. The tween runs without
recomposition per frame and is advanced by the shared GTK frame clock; an idle UI requests zero
frames. The default is a monotonic `Tween`; `Spring` can overshoot and clip a container, so use it
for leaves. Nested containers each animate to their own natural target; use the same `spec` to keep
them in phase.

`durationMs`/`easing` parameters and `Modifier.widthBehavior()/heightBehavior()/sizeBehavior()` were
removed: they animated via `gtk_widget_set_size_request`, a minimum that cannot grow. Migrate
`Modifier.size(w, h).widthBehavior()` to `Row(modifier = Modifier.animatedWidth(mainSize = w, crossSize = h)) { … }`.
Benchmark: `scripts/anim-bench.sh` (`--help`).

### `dev.knshell.shell` — windows

| Symbol | Purpose |
| --- | --- |
| `Shell.run { … }` | starts GTK, the GLib loop, the Recomposer and declares windows |
| `barWindow(BarWindowSpec) { … }` | layer-shell bar (layer, anchors, margins, exclusive zone, namespace) |
| `window(WindowSpec) { … }` | regular toplevel |
| `Shell.services` | same object as `Services` |
| `Shell.Hyprland.focusWorkspace/spawn/dispatch` | compositor actions |
| `HoverPopover { trigger }` | popover with its own Compose composition |
| `BarAnchor`, `BarLayer`, `ExclusiveZone`, `Margins`, `KeyboardMode` | window spec types |

### `dev.knshell.services` — data

`Services.<name>.state` is a `StateFlow`; services start lazily on first access.
`Services.startAll()` (called by `Shell.run`) subscribes everything.

| Service | State | Actions |
| --- | --- | --- |
| `windows` | `List<WindowInfo>` | — |
| `workspaces` | `List<WorkspaceInfo>` | `focus(name)` |
| `media` | `MediaState` | `togglePlayPause()` |
| `volume` | `Int` (0–150) | `setVolume(Int)` |
| `system` | `SystemStats` | — |
| `spectrum` | `List<Int>` (16 × 0..100) | — |
| `battery` | `BatteryState` | — |
| `hyprland` | `activeWindow: ActiveWindow?`, `currentSubmap: String` | — |
| `network` | `NetworkState` (rx/tx rates, cumulative totals, interface, type, SSID, signal, IP) | — |
| `notifications` | `state: List<NotificationInfo>` (icon + actions included), `doNotDisturb`, `isServer` | `toggleDoNotDisturb()` (server-side suppression), `dismiss(id)`, `invokeAction(id, action)`, `clear()` |

### `dev.knshell.animation`

`AnimationSpec` (`Tween`, `Spring`, `Snap`) for container sizes; `spring`/`animateFloatAsState`,
`Easing`, `Animate`, `lerp`, `lerpColor` — all driven by the shared GTK frame clock.

### `dev.knshell.ui`

`primitives`: `Space`, `HSpace`, `VSpace`, `Spacer`, `Icon`, `Text`, `Image`; `Format`: `Float.format`,
`percent`, `whole`, `floorInt`, `ceilInt`, `formatBytesPerSec`; `bucketLevels` for spectrum
visualisers.

## Visibility rules

* Public: everything a shell author composes (`@Composable` functions, `Modifier`
  extensions, service objects, shell/window types).
* Internal: GTK node classes, the applier, the runtime, modifier element classes and
  styling internals — they are specific to this bridge and exist so the DSL stays simple.
* Rust records/callback interfaces live in `dev.knshell.core` (generated by UniFFI) and are
  exposed through the service objects; prefer `Services.*` over calling them directly.

## Checks

```bash
./gradlew :framework:linuxX64Test   # native smoke tests (spectrum, animation, formatting)
./gradlew :shell-app:run            # build + launch the example shell
```

KDoc in the source is the reference for every symbol; this page only fixes the contract.

## Generated KDoc (Dokka)

```bash
./gradlew :framework:dokkaHtml
# → framework/build/dokka/html/index.html
```

Dokka documents the `framework` module only; application code (`shell-app`) is not part of
the published API.
