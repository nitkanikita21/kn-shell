# Module Structure & Documentation Refactor (framework, rust-core, palette-core, icons)

## Context

Модулі проєкту (крім `shell-app`) виросли без систематичного структурування:

- `framework/src/nativeMain/.../compose/Modifier.kt` — 923 рядки: `Color` + `Catppuccin` + ~30 `ModifierElement` + ~50 extension-функцій у одному файлі.
- `compose/Widgets.kt` — 735 рядків: класи GTK-вузлів (`Gtk*Node`) і_composables_ (`Box/Row/Label/Button/...`) змішані.
- `shell/Popover.kt` (455), `shell/ShellRuntime.kt` (409) — купують API-об'єкти й runtime-механіку разом.
- `rust-core/src/lib.rs` — 501 рядків: `mod`-оголошення + uniffi-рекорди + трейти + ~20 `#[uniffi::export]` функцій; `notifications.rs` — 564 рядки всього підряд.
- Публічний API vs internals не позначені: `GtkApplier`, `GtkFrameClock`,reset-хуки, node-класи доступні так само, як `Shell.run` чи `Modifier.padding`.
- KDoc/rustdoc покриття нерівне: `Services.kt`/`Shell.kt` документовані, `services/*Service.kt` — по 1 коментару; rust-core docs у кращому стані за Kotlin.
- `docs/*.md` (framework-api, shell-api, icons, theme, layout-model, bar-api) — якісні, але посилаються на файли, які зникають після сплітів.

**Ціль (затверджено користувачем):** розділити великі файли, чітко відокремити публічний API від internals, уніфікувати namespace-конвенцію, KDoc/rustdoc на кожен public API, README/MODULE.md на модуль. Виконання — субагентами на моделі `opencode-go/deepseek-v4.1-flash`.

**Жорстке обмеження:** `shell-app` НЕ рефакториться. Його імпорт-лист (зібрано сканом) фіксує заморожений публічний API:
`dev.knshell.compose.*` (Color, Modifier, Align, Label, Box, Row, Column, Stack, Overlay, Slider, Icon, runComposeWindow, modifier-екстеншени), `dev.knshell.services.*Service`, `dev.knshell.ui.primitives.*`, `dev.knshell.icons.IconRef`, `dev.knshell.icons.lucide.Lucide`, `dev.knshell.palette.*`, `dev.knshell.core.*` (UniFFI-генерація). Після рефакторингу `shell-app` компілюється без жодної зміни файлу.

## Approach

1. **Спліт у межах package, не між packages.** Нові файли лишаються в тому ж `package`, тож `import`-и споживачів не змінюються взагалі. Напр. `Modifier.kt` → `Color.kt`, `ModifierCore.kt` (інтерфейс `Modifier` + `ModifierElement` + internal-хелпери), `ModifierLayout.kt`, `ModifierText.kt`, `ModifierPaint.kt`, `ModifierInteraction.kt`.
2. **Поділ API vs internals через видимість + фізичну Organization:**
   - Символи, які не імпортує жоден зовнішній модуль (скан по `framework`/`icons`/`shell-app`) → `internal` (`GtkApplier`-хелпери, node-класи, frame-clock internals, reset-хуки, крім тих, що вимкнено п. 1).
   - Внутрішні файли модуля — у підпапки `internal/` того ж package `dev.knshell.<area>.internal`.
   - Публічний surface модуля перелічується в `MODULE.md` модуля як контракт.
3. **Namespace-конвенція (документується, ламається мінімум):**
   - `dev.knshell.<area>` — публічний API; `dev.knshell.<area>.internal` — механіка.
   - Виняток-конвенція зберігається свідомо: `dev.knshell.compose.Icon` живе в `:icons:core` (API-цукор, описано в `docs/icons.md`) — лишається як є.
   - Уніфікація: `Format.kt` імені пакета `dev.knshell.ui.format` не вводимо (споживачі існують) — замість цього пакет ієрархія `ui/` впорядковується: `ui/primitives/` (Utilities, Image) + `ui/format/` (Format.kt, package `dev.knshell.ui` без змін).
4. **Rust:** `lib.rs` лишає сcaffolding і ре-експорт; типи/трейти/експорти роз'їжджаються по `types.rs`, `listeners.rs`, `api.rs`; внутрішня механіка кожного сервісу — `pub(crate)`. Кожен `pub` item — rustdoc (`///`, `# Panics`/`# Safety` де доречно). `notifications.rs` — спліт за concern (DBus-взаємодія / зберігання / розсилка слухачам).
5. **Документація:** KDoc на кожен public символ у `framework`, `icons:core`, `icons-plugin`; rustdoc у `rust-core`, `palette-core`; `MODULE.md` для `framework`, `rust-core`, `icons/core`, `icons-plugin` (palette-core README.md уже є); синхронізація `docs/*.md` (нові шляхи файлів) без зміни їхнього змістовного покриття.
6. **Верифікація кожного кроку (гейт):** `cargo test` для чатки + `./gradlew :framework:nativeTest :icons:core:nativeTest :shell-app:compileKotlinLinuxX64` (або вузькі задачі, якщо повний прохід зависає) — через `gate:` у workflow. Повний `./gradlew build` — фінальний крок.
7. **Оркестрація:** SubagentWorkflow, worker-агенти з `model: "opencode-go/deepseek-v4.1-flash"`. Фази послідовні (спільний Gradle-daemon і взаємозалежність модулів); у межах фази — паралельні workerи по неперетинних директоріях, кожен зі своїм gate-проходом після змін.

## Files to modify

- `framework/src/nativeMain/kotlin/dev/knshell/compose/` — спліт `Modifier.kt`, `Widgets.kt`; нові `internal/` файли; правки `GtkApplier.kt`, `GtkFrameClock.kt`, `Reset.kt`, `Interaction.kt` (видимість/доки).
- `framework/src/nativeMain/kotlin/dev/knshell/shell/` — спліт `Popover.kt`, `ShellRuntime.kt`; доки `Shell.kt`, `ShellScope.kt`.
- `framework/src/nativeMain/kotlin/dev/knshell/services/` — доки на кожен `*Service.kt`; `Spectrum.kt`/`SpectrumService.kt` розмежування.
- `framework/src/nativeMain/kotlin/dev/knshell/ui/` — впорядкування `format/`, `primitives/`; доки.
- `framework/src/nativeMain/kotlin/dev/knshell/animation/` — доки, `Sync.kt`/`AnimatedSizeAnimator.kt` видимість.
- `framework/build.gradle.kts` — лише якщо потрібно для джерел (зазвичай ні — той же source set).
- `rust-core/src/lib.rs` → `types.rs`, `listeners.rs`, `api.rs`; спліт `notifications.rs`; rustdoc-pass по всіх `*.rs`.
- `palette-core/src/*.rs` — rustdoc-pass (структура вже чиста), правки `README.md` під нові шляхи якщо з'являться.
- `icons/core/src/nativeMain/kotlin/dev/knshell/icons/` — KDoc-pass; `compose/Icon.kt` лишається в `dev.knshell.compose`.
- `icons-plugin/src/main/kotlin/...` — KDoc + `README.md` плагіна.
- Нові: `framework/MODULE.md`, `rust-core/README.md`, `icons/core/MODULE.md` (або README.md), `icons-plugin/README.md`.
- `docs/framework-api.md`, `docs/shell-api.md`, `docs/icons.md`, `docs/bar-api.md` — оновити шляхи файлів.
- `shell-app/**` — заборонено (тільки компілюється як споживач).

## Reuse

- `framework/src/nativeTest/.../compose/LayoutProbeTest.kt` — виконуваний контракт layout-сементики (`docs/layout-model.md`); спліти не повинні його чіпати.
- `icons-plugin/.../NameMappingTest.kt` — існуючий тест плагіна.
- Стиль KDoc уже задано в `Services.kt`, `Shell.kt`, `Utilities.kt`, `docs/*.md` — нові доки наслідують тон (коротко, приклад коду в ` ```kotlin `).
- Існуюча конвенція rustdoc у `rust-core/src/lib.rs` (модульний `//!`-огляд) і `palette-core/src/lib.rs` — масштабується, не винаходиться.

## Steps

- [ ] **0. Baseline.** Прогнати `cargo test` (rust-core, palette-core) і `./gradlew :framework:nativeTest :icons:core:nativeTest :shell-app:compileKotlinLinuxX64`. Зафіксувати, що обидва green ДО рефакторингу (якщо ні — зупинитись і доповісти, не лагодити чуже всерівно).
- [ ] **1. Usage-scan публічного surface.** Агент (researcher, deepseek) будує мапу: кожен public символ `framework`/`icons:core`/`rust-core` → зовнішні споживачі (shell-app, інші модулі, тести). Результат — список «заморожений API» vs «можно internal».
- [ ] **2. rust-core restructure.** Спліт `lib.rs` → `types.rs`/`listeners.rs`/`api.rs`; `notifications.rs` за concern; `pub(crate)` для механіки; rustdoc на кожен `pub` item; `README.md`. Gate: `cargo test` у `rust-core`.
- [ ] **3. palette-core docs pass.** Rustdoc-покриття `pub` itemів, оновити `README.md` за потреби. Gate: `cargo test` у `palette-core`.
- [ ] **4. framework compose/ спліт.** `Modifier.kt` → 5–6 файлів (той же package); `Widgets.kt` → nodes/layout/basic; `internal/` підпапка для `GtkFrameClock` internals, `Reset` хуків тощо (лише символи без зовнішніх споживачів за мапою з кроку 1). KDoc на public. Gate: `:framework:nativeTest` + `:shell-app:compileKotlinLinuxX64`.
- [ ] **5. framework shell/ + services/ + ui/ + animation/.** Спліт `Popover.kt`, `ShellRuntime.kt`; KDoc на кожен `*Service`; впорядкування `ui/`; видимість `animation/`. Gate: те саме.
- [ ] **6. icons:core + icons-plugin.** KDoc-pass; `internal` для `RsvgCache` internals (перевірити споживачів); README для `icons/core` і `icons-plugin`. Gate: `:icons:core:nativeTest` + `:icons-plugin:test` (JVM).
- [ ] **7. MODULE.md для всіх модулів + синхронізація docs/*.md.** Кожен модуль: призначення, публічний API-таблиця, правила залежностей, карта файлів. `docs/*.md` — нові шляхи. Gate: ручна перевірка посилань (`rg` за старими шляхами = 0 hit).
- [ ] **8. Final verification.** `./gradlew build` (повний, включаючи shell-app) + `cargo test` в обох кратках; reviewer-агент (deepseek, read-only) перевіряє: жодного дифу в `shell-app/**`, кожен public символ документований, файли ≤ ~300 рядків.

## Verification

- Кожен крок 2–6 — своїм gate-проходом (build/test), історично green.
- Фінал: `./gradlew build` + `cargo test` (обидві чатки) без змін у `shell-app/` (`git diff --name-only` не містить `shell-app/`).
- Ручний smoke (за бажанням користувача): зібраний shell-app запускається як раніше (поки межа — компіляція споживача).
