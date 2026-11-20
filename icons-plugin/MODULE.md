# icons-plugin

Build logic (not shipped code): an **included build** that turns an upstream SVG dump into
Kotlin sources. The root build wires it as a composite (`settings.gradle.kts`):

```kotlin
pluginManagement { includeBuild("icons-plugin") }
```

so packs apply it by id — `id("dev.knshell.icons.pack")`. Kotlin DSL, JVM toolchain 21.
User-facing contract: [`docs/icons.md`](../docs/icons.md).

## Tasks (per pack module)

| Task | Does |
| --- | --- |
| `syncIcons` | downloads the pinned npm tarball (`tarballUrl` or derived from `npmPackage`/`version`), finds the directory holding `*.svg`, caches it under `.cache/icons/<pkg>-<version>/icons`; no-op while the `.synced-version` marker matches. `sourceDir` syncs a local checkout instead. |
| `generateIcons` | `dependsOn(syncIcons)`; emits `<Name>Catalog.generated.kt` (every id) and `<Name>Data.generated.kt` (referenced bodies + `internal <Name>Pack : IconPack`) into `build/generated/iconPack/kotlin/`. |
| `icons` | convenience aggregator = `syncIcons` + `generateIcons`. |

## How `iconsPack { }` is wired

`IconsPackExtension` holds the config; `IconsPackPlugin.apply` registers the tasks from it and,
inside `plugins.withId("org.jetbrains.kotlin.multiplatform") { afterEvaluate { … } }`, does two
things **reflectively** (no kotlin-gradle-plugin dependency): adds the generated dir to
`nativeMain` and makes `compileKotlinLinuxX64` depend on `generateIcons`. A missing task only
logs a warning — codegen then has to be wired by hand.

## Source-scan tree-shaking contract

Only **referenced** icons get their SVG bodies emitted; the scan is file-based because the
shell's sources are not a Gradle dependency of the pack (that would be a cycle).

| Rule | Detail |
| --- | --- |
| Roots | `iconScanRoots` (default `framework/src`, `shell-app/src`, `icons-packs`), base `scanBaseDir` (repo root) |
| Matches | `Object.<Name>` properties and `Object.of("<literal>")` in `*.kt`; `*.svg` not scanned |
| Inputs | the roots are real `@InputFiles`, so a new `Icon(Lucide.Star)` re-runs codegen |
| Unknown names | reported as `[icons] <Pack>: N unknown icon reference(s), ignored: …`, build continues |
| Non-literals | `Lucide.of(variable)` packs nothing — list candidates explicitly or check `Lucide.ids` |

## `NameMapping`

`id → Property` (`home` → `Home`, `arrow-down` → `ArrowDown`, `wifi-off` → `WifiOff`) and the
reverse, which is what lets the scan resolve `WifiOff` back to `wifi-off`. Upstream collisions
(`arrow-down-0-1` / `arrow-down-01`) get an ordinal on the later id; ids starting with a digit
get no property at all (`Lucide.of("10k")`).

## Tests

```bash
cd icons-plugin && ../gradlew test      # NameMappingTest: mapping + collision rules
```

`src/test/kotlin/dev/knshell/icons/plugin/NameMappingTest.kt`, JUnit Platform, no Gradle TestKit.
