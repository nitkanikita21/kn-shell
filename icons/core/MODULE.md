# icons:core

The icon sub-framework: typed references, a librsvg renderer with no GTK state of its own,
and the one composable a shell calls. User-facing contract: [`docs/icons.md`](../../docs/icons.md).

## What lives here

| File | Owns |
| --- | --- |
| `icons/IconTypes.kt` | `IconStyle`, `IconPack`, `IconRef`, `IconPack.ref` — the reference model |
| `icons/IconNode.kt` | `internal IconNode` — `GtkDrawingArea`, draw func, tint/tween fields |
| `icons/SvgDocument.kt` | pure markup: `colorToHex`, `buildTintedDocument`, attribute stripping |
| `icons/RsvgCache.kt` | two-level LRU (tinted documents + compiled `RsvgHandle`s), `iconViewport` |
| `compose/Icon.kt` | `Icon(...)` composable and the frame-clock tint tween |

Sizes are logical px at the API edge; `IconStyle` viewBox/stroke units are SVG user units.

## Dependency direction

```
icons-packs/<pack> → icons:core → :framework
```

`:framework` never imports icons. `icons/core/build.gradle.kts` exposes `:framework` as
`api` because `Icon`'s signature mentions framework types.

## Package exception: `Icon` in `dev.knshell.compose`

`compose/Icon.kt` deliberately uses `dev.knshell.compose` so `import dev.knshell.compose.Icon`
reads like the rest of the DSL, even though the module is `:icons:core`. That is the only
reason the module depends on these framework seam symbols (all `public`, never tightened):

| Symbol | Used for |
| --- | --- |
| `GtkNode` | `IconNode` base class (widget ownership, `widget`) |
| `GtkApplier` | `ComposeNode<IconNode, GtkApplier>` |
| `plain()` | keeps the GTK theme off the drawing area |
| `applyModifierFromModule` | applies the caller's `Modifier` chain |
| `Color`, `Easing`, `lerpColor` | tint API, OutCubic curve, per-frame colour blend |

## Adding a hand-written pack

No plugin needed — implement the three members and expose refs:

```kotlin
internal object MyPack : IconPack {
    override val name = "MyIcons"
    override val style = IconStyle(tintAttribute = "fill", viewBoxSize = 48.0)
    override fun svgInner(id: String): String? = when (id) {
        "logo" -> "<path d=\"…\"/>"
        else -> null
    }
}

val Logo: IconRef get() = MyPack.ref("logo")
```

Put it in its own module that does `api(project(":icons:core"))` (see `icons-packs/lucide/`).

## Tests

```bash
./gradlew :icons:core:linuxX64Test     # SvgDocument + LRU + viewport, no display needed
```

Tests live in `src/nativeTest/kotlin/dev/knshell/icons/` and may touch `internal` symbols.
