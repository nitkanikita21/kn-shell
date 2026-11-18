# Модуль іконок (Lucide) + sub-framework для ікон-паків

## Context (навіщо)

`dev.knshell.ui.primitives.Icon` (framework/src/nativeMain/kotlin/dev/knshell/ui/primitives/Utilities.kt:49)
— це просто `Label` з гліфом Nerd Font (`Icon("\uF015")`, shell-app/src/linuxX64Main/kotlin/dev/knshell/app/ui/test.ui.kt:53,97).
Нема іменованого API, немає гарантій розміру, залежить від встановленого шрифту, неможливо
замінити на інший набір іконок.

Мета: справжня векторна система іконок — Lucide як перший пак — поверх маленького
**sub-framework**, який робить інші паки (Material Symbols, Phosphor, Tabler, FontAwesome…)
підключуваними з мінімальним кодом, і зручною у існуючому GTK4/Compose DSL (`Modifier`,
`dev.knshell.compose.Color`, frame clock). Поважаємо контракт docs/framework-api.md
(«framework не імпортирує код застосунку»; third-party shell потребує лише `:framework`).

## Рішення (згодні з користувачем)

1. **Рендерер: librsvg через cinterop** (librsvg 2.63.4 встановлено; `pkg-config --cflags --libs librsvg-2.0` працює). Підтримка будь-якого SVG-паку, чітко на будь-якому розмірі.
2. **Окремі Gradle-модулі**: sub-framework `icons/core/` → `:icons:core`; **паки — у підпапці
   `icons-packs/`** (`icons-packs/lucide/` → `:icons-packs:lucide`). Спільна механіка sync/generate
   виноситься у **Gradle-плагін** `icons-plugin/` (included build), щоб новий пак = ~10 рядків
   конфігу, а не копіювання задач (загальний візерунок модулів — як `palette-core/`).
3. **Каталог повний, ассети tree-shaken** — IDE бачить ВСІ іконки Lucide: генератор робить
   типізовані `IconRef`-константи на ВЕСЬ каталог (~1500 записів, по одному рядку `val X =
   ref("x")`, без даних — компілюється швидко). Важкі SVG-рядки вбудовуються ТІЛЬКИ для іконок,
   знайдених сканером у коді. Додав `Icon(Lucide.Bell)` (автокомпліт працює завжди) → наступний
   білд запакує її SVG. Якщо хтось обійшов генерацію (stale build) — рантайм малює порожньо +
   одноразовий stderr-warning «rebuild». Жодного ручного списку used-іконок.
4. **Старий `Icon(glyph)` з Nerd Font повністю видаляється.** У місцях гліфів — векторна іконка
   Lucide поруч з текстом, де це природно для інтерфейсу (індикатор, чіп «power»), інакше —
   звичайний `Label`. Конкретика в секції «Файли».

## Архітектура

```
icons/
  core/              dev.knshell.icons          — sub-framework (:icons:core)
     api → :framework  (Color, Modifier, Canvas; rsvg через власний cinterop kn.rsvg)
icons-packs/
  lucide/            dev.knshell.icons.lucide   — пак Lucide (:icons-packs:lucide): лише конфіг плагіну
     api → :icons:core
icons-plugin/        included build — Gradle-плагін "dev.knshell.icons.pack" (sync + scan + codegen)
shell-app/           implementation → :icons-packs:lucide
```

### API sub-framework (`:icons:core`, package `dev.knshell.icons`)

```kotlin
/** Як SVG-розмітка паку виражає колір/товщину — контракт адаптації паку. */
data class IconStyle(
    val tintAttribute: String = "stroke", // lucide/tabler/feather: stroke; material/phosphor filled: fill
    val strokeWidth: Double = 2.0,         // нормалізується до viewBox паку рендерером
    val viewBox: Size? = null,             // default паку; viewBox з конкретного SVG має пріоритет
)

interface IconPack {
    val name: String
    val style: IconStyle
    /** kebab-case id іконки → сирий SVG (рядок `<svg …>`). null = невідома/незапакована іконка. */
    fun svg(id: String): String?
}

/** Композебл. Ергономіка повторює старі Icon/Text (size, color, transitionMs). */
@Composable fun Icon(
    icon: IconRef,                        // IconRef(pack, id), створюють паки
    modifier: Modifier = Modifier,
    size: Int = 24,
    color: Color? = null,                 // null → CSS currentColor (спадає колір label)
    transitionMs: Int = 0,                // анімована перефарбовка через GtkFrameClock
    strokeWidth: Double? = null,          // перевизначення товщини на виклик (Lucide це підтримує)
)
```

**Пайплайн рендерингу** (на `Icon`): `Canvas` (framework `Widgets.kt:520`, cairo `drawFn`) →
`rsvg_handle_new_from_data` на пофарбованій розмітці → `rsvg_handle_render_document(handle, cr, viewport)`
у розмірі `size × scale_factor`. `cairo_t` переходить між cinterop-пакетами через `reinterpret`
(kn.gtk ↔ kn.rsvg).

**Перефарбовка (string surgery один раз на (іконка, колір), не на кожен кадр)**: розмітка обробляється один раз на
(іконка, колір): діти загортаються у `<g stroke="$hex" fill="none" stroke-width="w"
stroke-linecap="round" stroke-linejoin="round">` (атрибути за `IconStyle.tintAttribute`).
`RsvgHandle` кешується в LRU (~256 записів, key = pack:id:colorHex:width) — handle незмінний,
тримати його дешево.

**Анімація кольору**: при `transitionMs > 0` інтерполяція Color по кадрах через наявний frame
clock (`withFrameNanos` — приклад: framework/src/nativeMain/kotlin/dev/knshell/compose/ShaderCanvas.kt,
animation/Sync.kt) + `gtk_widget_queue_draw` на кожен кадр.

**Поведінка при помилці**: `svg(id)` → null → нічого не малюємо + одноразове попередження в
stderr з id іконки та підказкою перебудувати (tree-shaking не встиг запакувати). Ніколи не кидає виняток.

### cinterop

`icons/core/src/nativeInterop/cinterop/rsvg.def`: `package = kn.rsvg`,
`headers = librsvg/rsvg.h` (+ linkerOpts `-lrsvg-2`), cflags із
`pkg-config --cflags librsvg-2.0` у build.gradle.kts (візерунок скопійовано з
framework/build.gradle.kts — cinterops gtk4/gl). Окремий пакет уникне колізій типів із kn.gtk.

### Плагін `icons-plugin` (included build) — механіка кодгену

`icons-plugin/build.gradle.kts` — плагін на `kotlin-dsl` (JVM, build-time only);
`settings.gradle.kts` → `pluginManagement { includeBuild("icons-plugin") }`. Плагін реалізує всю
механіку (sync, scan, codegen, srcDir-вріз); пак описує лише ДАНІ:

```kotlin
// icons-packs/lucide/build.gradle.kts — весь кастомний код модуля паку:
plugins { alias(libs.plugins.kotlin.multiplatform); id("dev.knshell.icons.pack") }
kotlin { linuxX64(); sourceSets.nativeMain.dependencies { api(project(":icons:core")) } }
iconsPack {
    objectName.set("Lucide")                       // ім'я згенерованого object
    version.set(libs.versions.lucideStatic)        // версія ассетів → кеш
    assets.set(npmTarball("lucide-static"))        // sync: registry.npmjs.org → .cache/icons/lucide-<ver>/
    iconsDir.set("package/icons")                  // шлях до *.svg всередині архіву
    idToClassName.set(PASCAL_CASE)                 // wifi-off → WifiOff (acronym-aware)
    tintAttribute.set("stroke")                    // → IconStyle
    defaultStrokeWidth.set(2.0)
}
```

Задачі плагіну (автоматично на кожен застосований модуль):
- `syncIcons<Name>` — тарбол → `.cache/icons/<name>-<version>/` (gitignored; skip якщо кешwarm
  → офлайн-білди після першого sync);
- `generateIcons<Name>` — два проходи:
  1. **каталог**: список `*.svg` з кешу → повний перелік `IconRef` (для IDE);
  2. **uses**: regex-скан `*.kt` у зареєстрованих коренях споживачів (`iconScanRoots` —
     file-globs з rootDir, НЕ project-deps → немає циклу shell-app↔:icons-packs:lucide;
     дефолт: усі `*/src/**` репо) на `Lucide\.[A-Z][A-Za-z0-9]*` та `Lucide\.of\("([a-z0-9-]+)"\)`
     → tree-shaken SVG-рядки; warning для id, яких немає в архіві;
  - out: `build/generated/` через `srcDir` → compile залежить від generate, згенероване не комітиться;
- `compileKotlinLinuxX64` dependsOn `generateIcons<Name>` + `syncIcons<Name>`.

### Пак Lucide (`icons-packs/lucide`)

- **Джерело істини**: npm-тарбол `lucide-static` (містить `icons/*.svg`, 24×24,
  `stroke="currentColor"`, stroke-width 2 — канонічні конвенції Lucide). Версія закріплена в
  `gradle/libs.versions.toml` (`[versions] lucideStatic = "…"`; точну оберемо на етапі реалізації).
- **Codegen — два файли**:
  1. `LucideCatalog.generated.kt` — **повний каталог** (~1500):
     ```kotlin
     object Lucide {
         internal val pack = LucidePack // IconPack: when(id) → LucideData.svg(id)
         val Home: IconRef get() = ref("home")
         val WifiOff: IconRef get() = ref("wifi-off")
         // …усі іконки з архіву — IDE бачить весь автокомпліт
         fun of(id: String): IconRef = ref(id) // динамічний escape hatch
     }
     ```
  2. `LucideData.generated.kt` — **tree-shaken ассети**: `internal fun svg(id: String) = when (id) { "home" -> "<svg…>"; … }`
     тільки для іконок, знайдених сканером uses.
- **Перевірка**: записів у `LucideData` == кількість знайдених uses; refs у `LucideCatalog` ==
  `count(icons/*.svg)`; у кожному SVG збережено `viewBox`, `<svg`-заголовок викидається/нормалізується генератором.

### Підключення іншого паку (документований how-to)

Все нетривіальне (тарбол, кеш, скан, два генератори, srcDir) живе в `icons-plugin`. Новий пак =
**нова директорія в `icons-packs/` з ~10-рядковим build.gradle.kts**:

1. `icons-packs/material-symbols/build.gradle.kts`: `plugins { id("dev.knshell.icons.pack") }` +
   `iconsPack { objectName="MaterialSymbols"; assets=npmTarball("material-symbols-svg"); iconsDir=…;
   tintAttribute="fill" }` — решта за замовчуванням;
2. `settings.gradle.kts`: `include(":icons-packs:material-symbols")`;
3. код застосунку без змін: `Icon(MaterialSymbols.Home, size = 22, color = c.accent)`.
Різниця паків поглинається sub-framework: масштабування viewBox, tint-атрибут (stroke/fill),
stroke width, нормалізація імен (camelCase/kebab/Pascal). Паки з нестандартних джерел (локальний
каталог SVG, git-repo) — інший `assets`-провайдер (`localDir(…)`/`gitArchive(…)` у тій же задачі sync).

## Файли

**Нові**
- `icons/core/build.gradle.kts`, `icons/core/src/nativeInterop/cinterop/rsvg.def`
- `icons/core/src/nativeMain/kotlin/dev/knshell/icons/{IconPack.kt,IconStyle.kt,IconRef.kt,Icon.kt,RsvgCache.kt,ColorTransition.kt}`
- `icons-plugin/build.gradle.kts`, `icons-plugin/src/main/kotlin/dev/knshell/icons/plugin/{IconsPackPlugin.kt,IconsPackExtension.kt,SyncIconsTask.kt,GenerateIconsTask.kt,NameMapping.kt}`
- `icons-packs/lucide/build.gradle.kts` (лише конфіг `iconsPack { }`), `icons-packs/lucide/src/nativeMain/kotlin/dev/knshell/icons/lucide/LucidePack.kt` (hand-written: `IconStyle` + реєстрація pack-а)
- `docs/icons.md` (API + гід для авторів паків)
**Змінені**
- `settings.gradle.kts` — `pluginManagement { includeBuild("icons-plugin") }` + `include(":icons:core", ":icons-packs:lucide")`
- `gradle/libs.versions.toml` — версія `lucideStatic` (+ плагін `icons-pack` у `[plugins]`)
- `.gitignore` — `.cache/`
- `shell-app/build.gradle.kts` — `implementation(project(":icons-packs:lucide"))` + реєстрація scan-root
- `framework/src/nativeMain/kotlin/dev/knshell/ui/primitives/Utilities.kt` — видалити `Icon(glyph)`; оновити doc-шапку модуля
- doc-посилання на старий `Icon`: `framework/src/nativeMain/kotlin/dev/knshell/compose/AnimatedSize.kt:71` (`Icon("\uF43A")` → `Icon(Lucide…)` приклад не може імпортувати пак — замінити на нейтральний `Icon(icon)`), `shell/Popover.kt:131`, `docs/framework-api.md`, `README.md:213,375`
- `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/ui/test.ui.kt:53` — гліф `\uF015` (home-індикатор біля тексту) → `Icon(Lucide.Home, size = 22, color = c.bg)` поруч з лейблом
- `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/ui/test.ui.kt:97` — `Icon("⏻") + Text("Icon in accent")` (чіп) → `Icon(Lucide.Power, size = 16, color = c.accent)` + текст; при hover — `transitionMs`

## Кроки

- [x] 1. `icons-plugin`: included build, `IconsPackPlugin` з DSL `iconsPack { }`; задачі `syncIcons`/`generateIcons` (поки з тестовим «паком»).
- [x] 2. Модуль `:icons:core`: gradle-підключення + cinterop `rsvg.def`; smoke-compile прямого виклику `rsvg_handle_new_from_data` + `render_document`.
- [x] 3. Типи `IconPack`/`IconStyle`/`IconRef` + wrapper перефарбовки + LRU кеш RsvgHandle.
- [x] 4. Композебл `Icon` на `Canvas`: фіксація розміру як в Image.kt, color→hex, queue_draw; анімація кольору `transitionMs` через frame clock.
- [x] 5. `:icons-packs:lucide`: закріплена версія `lucide-static`, конфіг `iconsPack { }`; повний каталог + tree-shaken data через `generateIcons`; підключено до compile.
- [x] 6. Видалити старий `Icon(glyph)` з Utilities.kt; мігрувати test.ui.kt (:53 → Lucide.Home, :97 → Lucide.Power); оновити doc-коментарі (AnimatedSize.kt, Popover.kt), docs/framework-api.md, README.md.
- [x] 7. `docs/icons.md`: референс API + how-to «додати пак» на прикладі Material Symbols (конфіг, без коду).
- [x] 8. Тести: nativeTest у `:icons:core` — PascalCase↔kebab мапінг (тест плагіну/окремо), результат tint-wrapper, LRU-eviction, шлях null для незапакованої іконки.

## Верифікація

- `./gradlew build` (plugin компілюється, codegen відпрацював, compile чистий, тести зелені).
- `./gradlew :shell-app:run` на Hyprland: демо-ряд у test.ui.kt малює Home/Power + кілька іконок
  на 16/22/48 px; hover-чіп анімує колір (transitionMs).
- Каталог для IDE: `Lucide.<Tab>` в автокомпліті показує ~1500 імен (перевірка grep'ом `LucideCatalog.generated.kt`).
- Round-trip tree-shake: додати `Icon(Lucide.Bell)` у test.ui.kt → rebuild без мережі (`.cache`
  теплий) → SVG Bell з'являється в `LucideData.generated.kt`; видалити використання → зникає з
  data, лишается в каталозі.
- Stale-поведінка: запустити білд без newly-rebuild після додавання іконки → порожній слот +
  stderr-warning, без падіння.
- HiDPI: чіткість на масштабованому моніторі (рендер у `size × scale_factor`).
