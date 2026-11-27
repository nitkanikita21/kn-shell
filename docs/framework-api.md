# Framework API contract

`framework/` is the reusable library; `demo/shell-app/` is one shell built on it. A third-party
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

Everything below is public and safe to use; anything else (`InlineStyle`, `ShellRuntime`,
`GtkFrameClock`, `GLibDispatcher`, `applyModifiers`, `PLAIN_CLASS`, `*Element` modifier classes,
the `Gtk*Node` widget nodes) is an implementation detail and may change. The one exception is the
satellite seam documented under [Visibility rules](#visibility-rules): `GtkNode`, `GtkApplier`,
`applyModifierFromModule`, `plain()`, `Color`, `Easing`, `lerpColor` are public because `:icons:core`
is a separate Gradle module and Kotlin has no friend-module visibility.

### `dev.knshell.compose` — UI

| Symbol | Purpose |
| --- | --- |
| `Row`, `Column`, `Box`, `CenterBox`, `Stack`, `Overlay`, `Slider`, `Label`, `Markup`, `Button` | layout and leaf widgets |
| `Grid(columns, spacing)` | table (`GtkGrid`): children fill it row by row, columns align |
| `Entry`, `Switch`, `DropDown` | input widgets (see [Input widgets](#input-widgets)) |
| `Modifier` + extensions | layout (`size`, `padding`, `margin`, `align`, `hExpand`, `fillWidth`, `minSize`, `maxWidth`), styling (`background`, `gradient`, `glow`, `radius`, `textColor`, `fontSize`, …), interaction (`onClick`, `onHover`, `cursor`, `pointerCursor`, `inputRegion`), animation (`transition`, `gradientStops`), `rawStyle`, `clipOverflow`, `testTag` (inspector name, see the Inspector section below) |
| `Align`, `AnimatedAxis`, `Corner`, `Color` | supporting types |
| `animatedWidth`, `animatedHeight`, `animatedSize` (Modifier extensions) | Compose-owned size animation (AnimationSpec + GTK frame clock; passive native layout manager) |
| `Canvas`, `ShaderCanvas` | cairo / OpenGL drawing |

Layout semantics — who measures, what `size`/`minSize`/`maxSize`/`weight` really mean, which
container to pick, and what GTK cannot express — are in [`docs/layout-model.md`](layout-model.md).

### Input widgets

| Composable | GTK widget | Callback | Notes |
| --- | --- | --- | --- |
| `Entry(value, modifier, placeholder, onValueChange)` | `GtkEntry` | `(String) -> Unit`, after every user edit | `value` is written back only when it differs from the field text, so the cursor does not jump |
| `Switch(checked, modifier, onCheckedChange)` | `GtkSwitch` | `(Boolean) -> Unit`, user toggles only | programmatic `checked` does not fire it |
| `DropDown(items, selected, modifier, onSelect)` | `GtkDropDown` over a `GtkStringList` | `(Int) -> Unit`, index of the picked item | `selected` is an index; it is re-applied after `items` changes |

```kotlin
var query by remember { mutableStateOf("") }
var enabled by remember { mutableStateOf(true) }
var mode by remember { mutableStateOf(0) }

Entry(value = query, placeholder = "Search", onValueChange = { query = it },
    modifier = Modifier.width(200))
Switch(checked = enabled, onCheckedChange = { enabled = it })
DropDown(items = listOf("Dark", "Light"), selected = mode, onSelect = { mode = it })
```

The three widgets belong to the same plain-widget family as the rest of the DSL, so the reset
(`PLAIN_CSS`, see `compose/Reset.kt`) strips their GTK paint. Until they are styled they look bare:
the switch track and knob, the drop-down button and popover, and the trough and knob of `Slider`
have no background or border. Style them with the typed `Modifier.slider(...)` or with
`Modifier.rawStyle(selector, declarations)`: one selector per call (no comma lists), appended to the
widget's own class, so `""` is the widget, `":checked"` a state and `" slider"` a sub-node:

```kotlin
Slider(value = volume, modifier = Modifier.width(120).slider(
    troughColor = Color.hex(0x242a2b), highlightColor = Color.hex(0x7fd6a2), knobColor = Color.hex(0xc8d2ce)))

Switch(checked = on, modifier = Modifier.rawStyle(" slider", "background-color: #7fd6a2;"),
    onCheckedChange = { on = it })
```

Sub-node styling works because of how `InlineStyle` (the per-widget style layer behind every
modifier) handles it. Rules with a combinator (`" slider"`, `" trough"`) target a child CSS node, and
GTK only reaches those from a display-wide provider. That provider stays private to the widget: it
is scoped by the widget's generated class (`.knm-N`) and removed when the widget is finalized, so a
sub-node rule never leaks to other widgets and does not outlive its widget.

### Accessibility, keyboard and focus

| Symbol | Purpose |
| --- | --- |
| `Modifier.semantics(Semantics(…))` | ARIA role, name, description, states (`checked`, `pressed`, `expanded`, `selected`, `disabled`, `busy`, `hidden`, `invalid`, `required`, `modal`), `hasPopup`, `orientation`, value range and relations (`labelledBy`, `describedBy`, `controls`, `activeDescendant`), written to GTK's accessibility tree (AT-SPI). A field left `null` is not claimed. |
| `SemanticsRef`, `Modifier.semanticsRef(ref)` | Names a widget for relations; `ref.announce(message, assertive)` speaks through it. |
| `Modifier.mergedSemantics()` | Hides a child that only repeats its parent's name. |
| `Modifier.onKey(capture) { event -> Boolean }` | Key presses reaching the widget or its descendants; `true` consumes. `capture` runs before the children. `KeyEvent(key, character, shift, ctrl, alt, meta)`. |
| `Modifier.focusable(focusable, autofocus)` | Puts a widget in (or, with `false`, takes it out of) the Tab order. Roving focus is one `focusable(true)` item among `false` ones. |
| `FocusRequester`, `Modifier.focusRequester(r)`, `Modifier.onFocusChange { }` | Moving focus from code and following it. |
| `FocusScope(modifier, trap, autoFocus, restoreFocus) { }` | A group that focuses its first widget, optionally keeps Tab inside, and gives the focus back when it leaves the composition. |

### Layers and popups

| Symbol | Purpose |
| --- | --- |
| `OverlayHost { }`, `Portal { }` | `Portal` draws its content over everything in the nearest `OverlayHost` (dialogs, toasts), with the composition locals of the place it is declared in. |
| `Popup(open, onDismiss, side, align, autoFocus, dismissible, content) { anchor }` (`dev.knshell.shell`) | A popover of the compositor, so it is not clipped by a layer-shell bar. The caller owns `open`; Escape and an outside click call `onDismiss`. Content sees the caller's composition locals; focus goes in and comes back. |

A child added later to a `Stack` or `Overlay` paints at its place in composition order.

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
`Modifier.size(w, h).widthBehavior()` to `Row(modifier = Modifier.animatedWidth(target = w, crossSize = h)) { … }`.
Benchmark: `scripts/anim-bench.sh` (`--help`).

### `dev.knshell.shell` — windows

| Symbol | Purpose |
| --- | --- |
| `Shell.run { … }` | starts GTK, the GLib loop, the Recomposer and declares windows |
| `barWindow(BarWindowSpec) { … }` | layer-shell bar (layer, anchors, margins, exclusive zone, namespace) |
| `window(WindowSpec) { … }` | regular toplevel |
| `Shell.services` | same object as `Services` |
| `Shell.Hyprland.focusWorkspace/spawn/dispatch` | compositor actions |
| `HoverPopover { trigger }`, `Popup(…)` | popovers with their own Compose composition (see [Layers and popups](#layers-and-popups)) |
| `BarAnchor`, `BarLayer`, `ExclusiveZone`, `Margins` | window spec types (`keyboardMode` takes a
  `GTK_LAYER_SHELL_KEYBOARD_MODE_*` constant, not a wrapper type) |

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

`primitives`: `Space`, `HSpace`, `VSpace`, `Spacer`, `Text`, `Image` (`Icon` lives in `:icons:core`, `dev.knshell.compose`); `Format`: `Float.format`,
`percent`, `whole`, `floorInt`, `ceilInt`, `formatBytesPerSec`; `bucketLevels` for spectrum
visualisers.

### `dev.knshell.inspect` — inspector

Lets an outside tool read the widget tree of a window, act on it and take screenshots. The Atlas
MCP daemon (`atlas/mcp`) is its main user. The API is plain Kotlin; there is no JSON in it.

| Symbol | Purpose |
| --- | --- |
| `Modifier.testTag(name)` (`dev.knshell.compose`) | Names a node so `Inspector.find` can address it. No visual effect. Tags are not checked for uniqueness; a lookup by a shared tag throws. |
| `windowInspector()` | Inspector of the first window of `ShellRuntime`. GTK is not touched until the first call on the result. |
| `Inspector` | Reads, acts and renders one window (see below). Its constructor is internal; get it from `windowInspector()`. |
| `InspectNode` | One node of the tree: `id`, `type`, `testTag`, `bounds` (`NodeBounds`, relative to the composition root), `cssClasses`, `text`, `checked`, `value`, `selected`, `options`, `visible`, `sensitive`, `children`, `role`, `name`, `description`, `states`, `focused`. |
| `VirtualKeyboard` | Presses keys through `headless-compositor`; `VirtualKeyboard.fromEnvironment()` is `null` anywhere else. |
| `InteractionState` | `Hover`, `Pressed`, `Focus`: the states `setState` can force. |
| `InspectException` | Every refused request: unknown or ambiguous target, wrong node type, hidden or insensitive node, value out of range. |
| `LineSocketServer(path, handler)` | Newline-delimited request/response server on a unix socket (mode `0600`), served by the GLib main loop. `start(): Boolean` binds (and returns `false` with a log line on failure); `stop()` closes everything and removes the socket file. The framework does not know the payload; the handler decides what a line means. |

`Inspector` methods:

| Method | Behaviour |
| --- | --- |
| `tree(): InspectNode` | Whole composition, depth first, with ids and testTags. |
| `find(target: String): InspectNode` | Node named by a decimal id or a testTag. Throws when nothing or several nodes match. |
| `focus(id)` | Gives the keyboard focus to the widget or its first focusable descendant. |
| `click(id)` | Clicks a `Button` (emits `clicked`), or runs the `Modifier.onClick` handler of another node. Throws if the node has no click handler. |
| `setText(id, text)` | Replaces the text of an `Entry`; its `onValueChange` fires. |
| `setChecked(id, checked)` | Toggles a `Switch`; its `onCheckedChange` fires. |
| `setValue(id, value)` | Moves a `Slider`. Values outside its range, or NaN, are rejected. |
| `select(id, index)` | Selects an item of a `DropDown`. Indices outside its options are rejected. |
| `setState(id, state, on)` | Sets or clears GTK state flags: hover (prelight), pressed (active), focus (focused, focus-visible). `onHover` callbacks are not fired. |
| `screenshot(id: Int? = null): ByteArray` | PNG of the composition root, or of a node's rectangle. The area is cropped from the render of the node's surface, so neighbouring backgrounds are included. A popover is its own surface: a node inside one is rendered from it, and it is not part of a picture of the window. |
| `suspend awaitIdle()` | Waits until pending recomposition and layout are done. |

Actions go through the same GTK signals a user input would emit, so Compose callbacks fire. A node
must be visible and sensitive to take an action; otherwise `InspectException`. Actions must run on the
GTK main thread: `LineSocketServer` handlers already do.

```kotlin
val inspector = windowInspector()
val send = inspector.find("send")          // by testTag; a numeric id works too
inspector.click(send.id)
inspector.awaitIdle()
val png: ByteArray = inspector.screenshot()
```

The socket protocol that wraps this for Atlas is documented in
[`docs/storybook/README.uk.md`](storybook/README.uk.md) (section "Інспектор: `--inspect`").

`disableUiTransitions()` turns GTK CSS transitions off for the process. An inspected application
should call it: `awaitIdle` does not wait for a transition, so a screenshot taken right after a
change would otherwise catch it half way. Compose-driven animations are not affected.

## Visibility rules

* Public: everything a shell author composes (`@Composable` functions, `Modifier`
  extensions, service objects, shell/window types).
* Internal: GTK node classes (`Gtk*Node`), the runtime, modifier element classes and
  styling internals — they are specific to this bridge and exist so the DSL stays simple.
  That includes `installPlainReset` (`compose/Reset.kt`), which the runtime calls for you, and
  `GtkFrameClock`/`GLibDispatcher` (the frame and dispatcher plumbing).
* Public for `:icons:core` only (frozen seam, see above): `GtkNode`, `GtkApplier`,
  `applyModifierFromModule`, `plain()`, `Color`, `Easing`, `Easing.eased`, `lerpColor`.
  They are public because visibility cannot be shared across Gradle modules.
* Rust records/callback interfaces live in `dev.knshell.core` (generated by UniFFI) and are
  exposed through the service objects; prefer `Services.*` over calling them directly.

## Checks

```bash
./gradlew :framework:linuxX64Test   # native smoke tests (spectrum, animation, formatting)
./gradlew :demo:shell-app:run            # build + launch the example shell
```

KDoc in the source is the reference for every symbol; this page only fixes the contract.

## Generated KDoc (Dokka)

```bash
./gradlew :framework:dokkaHtml
# → framework/build/dokka/html/index.html
```

Dokka documents the `framework` module only; application code (`shell-app`) is not part of
the published API.
