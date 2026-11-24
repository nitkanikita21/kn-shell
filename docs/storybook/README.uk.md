# Atlas — галерея компонентов UI kit (kn-shell)

Статус: проєкт (design). Реалізації Atlas ще немає; спайк out-of-process (child → host) завершено, див. `RESEARCH.md`. Мокапи: `docs/storybook/mockups/`.

## Мета і контекст

- `framework` — бібліотека, яку споживає зовнішній розробник. UI kit і Atlas є її частинами.
- `shell-app` — **приклад (PoC)** споживача. Його сервіси (`ThemeService`, `ThemeScale`, палітра з шпалер, `rust-core`) не є частиною фреймворку.
- Atlas — інструмент для перегляду і тестування компонентів (варіації, стани, контроли, тема), аналог `@Preview` у Compose Android або Storybook.

## Шари

```
core            ← GtkApplier, ComposeNodes, Modifier*, Layout,
                  Box/Row/Column/Stack/Overlay/Canvas, Image/Text/Spacer
ui kit          ← Button/Label/Slider з варіантами; DesignTokens, ThemeTokens,
                  ThemeMode, LocalKnTheme, KnTheme                  (пізніше)
atlas       ← галерея; власні chrome, тема і стилі; залежить лише від core
споживачі       ← shell-app (PoC), інші застосунки
```

Залежності односпрямовані: `core` ← `ui kit` ← споживачі; `core` ← `atlas`.

- **Atlas повністю ізольований від ui kit.** Має власну систему тем і стилів (i3/TUI з мокапів). Нічого з тем ui kit не імпортує.
- **Тема stories** задається ззовні через декоратор, який передає споживач:
  ```kotlin
  atlas(decorator = { content -> KnTheme(tokens = myTokens) { content() } }) {
      story("Button/Primary") { Button(...) }
  }
  ```
  Галерея не знає про `DesignTokens`. Перемикач Light/Dark у галереї викликає лямбду споживача.
- Коли ui kit з'явиться, його stories ідуть через той самий декоратор без змін у галереї.
- Chrome може будуватися на `core` або на сирому GTK-applier. `core` краще лишити: це шар layout-а, а не тема чи UI kit.

## Збірка і runner-и

- **KN-бібліотеки** замість executable: `shell-kn` (експорт `kn_shell_run()`) і `atlas-kn` (експорт `kn_atlas_run(argc, argv)`). Entry-точки через `@CName`.
- **Rust-runner-и тонкі:** `runners/shell` (`main()` → `kn_shell_run()`), `runners/atlas` (CLI, watch, relink, restart → `kn_atlas_run()`).
- `atlas-kn` **не тягне `rust-core`** і `services/`, тож подвійного UniFFI немає.
- `shell-kn` тягне `rust-core` як rlib у Rust-runner-і.
- Збірка: Gradle будує KN-бібліотеки → `cargo build -p shell-runner -p atlas-runner` з окремим `target/`. Оркестрація через скрипт у `scripts/`, а не через `build.rs`.
- Linker-опції GTK беруться через `pkg-config` у Rust `build.rs`, а не з дубльованого списку.
- Hot reload = перезапуск процесу. Rust-runner скорочує цикл (watch → relink → restart з тією самою story через `--story button/primary`), але relink Kotlin/Native лишається повільним.

## Сервіси, теми і контейнери

- **Правило:** `Services.*` використовують лише контейнери з бізнес-логікою (`shell-app`). UI kit і `core` сервісів не імпортують.
- **Компоненти приймають дані параметрами**, а дані, що однакові для всього дерева (тема, токени, щільність), — через `CompositionLocal` (`LocalKnTheme`), а не параметром.
- `KnTheme(tokens) { ... }` ставиться в корінь **кожного** вікна і попапу: `CompositionLocal` не проходить через межу окремо змонтованих композицій (див. коментар у `shell-app/.../ui/helpers/injectDi.kt`). Бажано винести це в спільну функцію монтування.
- **Мок-сервіси** потрібні лише контейнерам і живуть у споживача (`shell-app/linuxX64Mock`). Фреймворк дає механізм, але не знає про Hyprland, MPRIS чи інші конкретні сервіси.
- **Токени (`DesignTokens`, `ThemeTokens`, `ThemeMode`, шкали)** переїжджають у ui kit: це модель, яку споживає UI. Дефолт (`DesignTokens.default()`) — статичний нейтральний набір у ui kit, без алгоритму.
- **Лишаються в `shell-app`:** `ThemeScale`, `ThemeTuning`, `WallpaperPalette`, `palette/*`, `ThemeService`, `AwwwWallpaperSource`. Це приклад, а не контракт.
- Контракти (інтерфейси сервісів) окремим модулем **не виносимо**: їх немає сенсу тримати окремо, коли контейнери і моки живуть у споживача.

## DX: як визначати варіації

Структура:

```
ui-kit/
  Button.kt          // компонент
  Button.story.kt    // stories поруч
```

Приклад:

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

Що бачить розробник у галереї:

- **Сайдбар:** дерево груп і stories (`Button / Primary`, `Button / Matrix`).
- **Контроли:** текст для `string`, перемикач для `boolean`, dropdown для `enum`, слайдер для чисел. Будуються з `args`.
- **Тема:** перемикач Light/Dark через декоратор споживача.
- **Матриця:** сітка всіх комбінацій `axis`.
- **Kotlin-сніпет** оновлюється разом з контролами.

Ті самі stories можна прогнати в `nativeTest`: для кожної комбінації побудувати дерево `GtkApplier` і перевірити node-дамп.

## Мокапи

- `mockups/overview.html` — канвас з панорамуванням і зумом, фрейми варіантів, групування (компонент / стан / розмір / без групування), пошук, сайдбар.
- `mockups/stories/button.html`, `slider.html`, `label.html` — матриці варіацій по компоненту, панель args, сніпет.
- Спільна навігація і глобальна панель налаштувань (тема, accent, density) на кожній сторінці.
- Мокапи — лише дизайн, CSS-імітація, не реальний GTK.

## Обмеження

- **Немає reflection** у Kotlin/Native: `argsOf { ... }` пишеться вручну або генерується через KSP (пізніше).
- **Дрейф контролів:** ручні `args` можуть розійтися з сигнатурою компонента. Компілятор ловить більшість розбіжностей, бо story викликає компонент напряму.
- **Компоненти мають експонувати параметри** (`enabled`, `size`, `variant`). Зараз `Button(text, modifier, onClick)` їх не має. Це зміна ui kit-у.
- **Hot reload = перезапуск процесу.**

## Порядок робіт

1. **Spike (1–2 дні):** `atlas-kn` з одним `@CName` експортом і Rust-runner з одним вікном.
2. **Розділення `core` і ui kit:** `Services.*` з компонентів винести в контейнери; перевірити, що `core` не імпортує `services/`.
3. **Фаза A:** галерея з сайдбаром і однією story, без контролів.
4. **Фаза B:** `argsOf`, контроли, декоратор теми.
5. **Фаза C:** `variants`/матриця, node-тести на основі stories.
6. **Фаза D:** KSP для `args`, golden PNG для критичних компонентів.
7. **Пізніше:** ui kit (компоненти з варіантами), переведення stories на нього.

## Відкриті питання

- `core` і ui kit: окремі Gradle-модулі (`framework-core`, `framework-uikit`) чи один модуль з двома пакетами?
- Скільки займає relink `atlas-kn` (визначає комфорт циклу).
- Чи Gobley дозволяє зібрати KN-бібліотеку без вбудованого `rust-core`.
- Мінімальна CI-схема для GTK-display (Xvfb або `GDK_BACKEND=broadway`).
- `argsOf`: ручний DSL чи KSP з самого початку.
- Винесення `Services.*` з компонентів: скільки місць це зачіпає.
