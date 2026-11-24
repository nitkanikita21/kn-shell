# Atlas — галерея компонентов UI kit (kn-shell)

Статус: проект (design). Реализации Atlas пока нет; спайк out-of-process (child → host) завершён, см. `RESEARCH.md`. Мокапы: `docs/storybook/mockups/`.

## Цель и контекст

- `framework` — библиотека, которую использует внешний разработчик. UI kit и Atlas являются её частями.
- `shell-app` — **пример (PoC)** потребителя. Его сервисы (`ThemeService`, `ThemeScale`, палитра из обоев, `rust-core`) не являются частью фреймворка.
- Atlas — инструмент для просмотра и тестирования компонентов (вариации, состояния, контролы, тема), аналог `@Preview` в Compose Android или Storybook.

## Слои

```
core            ← GtkApplier, ComposeNodes, Modifier*, Layout,
                  Box/Row/Column/Stack/Overlay/Canvas, Image/Text/Spacer
ui kit          ← Button/Label/Slider с вариантами; DesignTokens, ThemeTokens,
                  ThemeMode, LocalKnTheme, KnTheme                  (позже)
atlas       ← галерея; собственные chrome, тема и стили; зависит только от core
потребители     ← shell-app (PoC), другие приложения
```

Зависимости однонаправленные: `core` ← `ui kit` ← потребители; `core` ← `atlas`.

- **Atlas полностью изолирован от ui kit.** Имеет собственную систему тем и стилей (i3/TUI из мокапов). Ничего из тем ui kit он не импортирует.
- **Тема stories** задаётся извне через декоратор, который передаёт потребитель:
  ```kotlin
  atlas(decorator = { content -> KnTheme(tokens = myTokens) { content() } }) {
      story("Button/Primary") { Button(...) }
  }
  ```
  Галерея не знает о `DesignTokens`. Переключатель Light/Dark в галерее вызывает лямбду потребителя.
- Когда появится ui kit, его stories идут через тот же декоратор без изменений в галерее.
- Chrome может строиться на `core` или на сыром GTK-applier. `core` лучше оставить: это слой layout-а, а не тема и не UI kit.

## Сборка и runner-ы

- **KN-библиотеки** вместо executable: `shell-kn` (экспорт `kn_shell_run()`) и `atlas-kn` (экспорт `kn_atlas_run(argc, argv)`). Entry-точки через `@CName`.
- **Rust-runner-ы тонкие:** `runners/shell` (`main()` → `kn_shell_run()`), `runners/atlas` (CLI, watch, relink, restart → `kn_atlas_run()`).
- `atlas-kn` **не тянет `rust-core`** и `services/`, поэтому дублирования UniFFI нет.
- `shell-kn` тянет `rust-core` как rlib в Rust-runner-е.
- Сборка: Gradle собирает KN-библиотеки → `cargo build -p shell-runner -p atlas-runner` с отдельным `target/`. Оркестрация через скрипт в `scripts/`, а не через `build.rs`.
- Linker-опции GTK берутся через `pkg-config` в Rust `build.rs`, а не из дублирующего списка.
- Hot reload = перезапуск процесса. Rust-runner сокращает цикл (watch → relink → restart с той же story через `--story button/primary`), но relink Kotlin/Native остаётся медленным.

## Сервисы, темы и контейнеры

- **Правило:** `Services.*` используют только контейнеры с бизнес-логикой (`shell-app`). UI kit и `core` сервисов не импортируют.
- **Компоненты принимают данные параметрами**, а данные, одинаковые для всего дерева (тема, токены, плотность), — через `CompositionLocal` (`LocalKnTheme`), а не параметром.
- `KnTheme(tokens) { ... }` ставится в корень **каждого** окна и попапа: `CompositionLocal` не проходит через границу отдельно смонтированных композиций (см. комментарий в `shell-app/.../ui/helpers/injectDi.kt`). Желательно вынести это в общую функцию монтирования.
- **Мок-сервисы** нужны только контейнерам и живут у потребителя (`shell-app/linuxX64Mock`). Фреймворк даёт механизм, но не знает о Hyprland, MPRIS и других конкретных сервисах.
- **Токены (`DesignTokens`, `ThemeTokens`, `ThemeMode`, шкалы)** переезжают в ui kit: это модель, которую потребляет UI. Дефолт (`DesignTokens.default()`) — статичный нейтральный набор в ui kit, без алгоритма.
- **Остаются в `shell-app`:** `ThemeScale`, `ThemeTuning`, `WallpaperPalette`, `palette/*`, `ThemeService`, `AwwwWallpaperSource`. Это пример, а не контракт.
- Контракты (интерфейсы сервисов) в отдельный модуль **не выносим**: держать их отдельно не имеет смысла, когда контейнеры и моки живут у потребителя.

## DX: как определять вариации

Структура:

```
ui-kit/
  Button.kt          // компонент
  Button.story.kt    // stories рядом
```

Пример:

```kotlin
val buttonStories = storyGroup("Button") {

    story("Primary") {
        Button(text = args.text, enabled = args.enabled, size = args.size, onClick = {})
    }

    variants("Matrix") {
        axis("size", ButtonSize.entries)
        axis("enabled", listOf(true, false))
        render { Button(text = "Save", size = it.size, enabled = it.enabled) {} }
    }
}

private val args = argsOf {
    string("text", default = "Save")
    boolean("enabled", default = true)
    enum("size", ButtonSize.entries, default = ButtonSize.Medium)
}
```

Что видит разработчик в галерее:

- **Сайдбар:** дерево групп и stories (`Button / Primary`, `Button / Matrix`).
- **Контролы:** текст для `string`, переключатель для `boolean`, dropdown для `enum`, слайдер для чисел. Строятся из `args`.
- **Тема:** переключатель Light/Dark через декоратор потребителя.
- **Матрица:** сетка всех комбинаций `axis`.
- **Kotlin-сниппет** обновляется вместе с контролами.

Те же stories можно прогнать в `nativeTest`: для каждой комбинации построить дерево `GtkApplier` и проверить node-дамп.

## Мокапы

- `mockups/overview.html` — канвас с панорамированием и зумом, фреймы вариантов, группировка (компонент / состояние / размер / без группировки), поиск, сайдбар.
- `mockups/stories/button.html`, `slider.html`, `label.html` — матрицы вариаций по компоненту, панель args, сниппет.
- Общая навигация и глобальная панель настроек (тема, accent, density) на каждой странице.
- Мокапы — только дизайн, CSS-имитация, не реальный GTK.

## Ограничения

- **Нет reflection** в Kotlin/Native: `argsOf { ... }` пишется вручную или генерируется через KSP (позже).
- **Дрейф контролов:** ручные `args` могут разойтись с сигнатурой компонента. Компилятор ловит большинство расхождений, потому что story вызывает компонент напрямую.
- **Компоненты должны экспонировать параметры** (`enabled`, `size`, `variant`). Сейчас `Button(text, modifier, onClick)` их не имеет. Это изменение ui kit-а.
- **Hot reload = перезапуск процесса.**

## План работ

1. **Spike (1–2 дня):** `atlas-kn` с одним `@CName` экспортом и Rust-runner с одним окном.
2. **Разделение `core` и ui kit:** вынести `Services.*` из компонентов в контейнеры; проверить, что `core` не импортирует `services/`.
3. **Фаза A:** галерея с сайдбаром и одной story, без контролов.
4. **Фаза B:** `argsOf`, контролы, декоратор темы.
5. **Фаза C:** `variants`/матрица, node-тесты на основе stories.
6. **Фаза D:** KSP для `args`, golden PNG для критичных компонентов.
7. **Позже:** ui kit (компоненты с вариантами), перевод stories на него.

## Открытые вопросы

- `core` и ui kit: отдельные Gradle-модули (`framework-core`, `framework-uikit`) или один модуль с двумя пакетами?
- Сколько занимает relink `atlas-kn` (определяет комфорт цикла).
- Позволяет ли Gobley собрать KN-библиотеку без встроенного `rust-core`.
- Минимальная CI-схема для GTK-display (Xvfb или `GDK_BACKEND=broadway`).
- `argsOf`: ручной DSL или KSP с самого начала.
- Вынос `Services.*` из компонентов: сколько мест это затрагивает.
