# Atlas: стан роботи (передача на інший комп'ютер)

Знімок на 2026-11-26, гілка `atlas`. Опис того, як усе влаштовано, — у [`README.uk.md`](README.uk.md);
тут лише те, що потрібно, щоб продовжити.

## Запуск на новій машині

```bash
git checkout atlas && git pull

./gradlew :shell-app:runAtlas                              # каталог у вікні
cargo install --path atlas/mcp                             # MCP-тулза → ~/.cargo/bin/atlas-mcp
claude mcp add --scope user atlas -- atlas-mcp             # один раз на машину
```

Після реєстрації перезапустити Claude Code у корені репозиторію. Перший пошук атласу йде через Gradle;
якщо бінарника атласу ще немає, спершу викликати інструмент `reload`.

Потрібно: GTK 4 з `gio-unix-2.0`, gtk4-layer-shell, librsvg, fontconfig, libepoxy, Rust (cargo),
JDK 21, `pkg-config`. `local.properties` не комітиться (машинний шлях до konan).

**Перевірити на ноуті окремо:** MCP-тулза бере перший `/dev/dri/renderD*`. На машині з двома GPU це не
тестувалось; перевірялась лише одна NVIDIA. Ознака проблеми — `reload`/`screenshot` не працюють або в
`logs` є помилки Vulkan/EGL.

## Тести

```bash
./gradlew :framework:linuxX64Test :atlas:core:linuxX64Test :icons:core:linuxX64Test
cargo test --manifest-path atlas/mcp/Cargo.toml
```

На момент коміту: `framework` 148, `atlas:core` 93, `icons:core` 22, демон 21; без падінь. Тести
`framework` та `icons:core` потребують графічної сесії.

## Що зроблено

- **Каталог:** контроли для всіх args (`string`, `boolean`, `enum`, `int`, `float`), значення за кожною
  story, Reset, темна тема, `--story`.
- **Матриці:** вигляди `Compose` (групи, що згортаються) і `Table`, перемикач, типовий вигляд у DSL
  (`variants("…", view = MatrixView.Table)`), значок вигляду в сайдбарі.
- **MCP (`atlas/mcp`):** Rust-демон із власним headless Wayland-композитором (рендер на GPU через
  `linux-dmabuf`). Атласи знаходить через Gradle init-скрипт за таскою `runAtlas…Executable…`, без
  конфігу в проєкті. Інструменти демона: `list_atlases`, `use_atlas`, `set_visible`, `reload`, `status`,
  `logs`, `stop`; решту віддає сам атлас.
- **Gradle-плагін (`atlas/plugin`):** `id("dev.knshell.atlas")` створює compilation, executable і таски.
- **Структура:** `atlas/core`, `atlas/mcp`, `atlas/plugin`.
- **Framework:** `Entry`, `Switch`, `DropDown`, `Grid`, інспектор (`dev.knshell.inspect`),
  `Modifier.testTag`.

### Виправлені баги `framework` (перевірити бар `shell-app`)

- Sub-node стилі (`Modifier.slider()`, `rawStyle(" …")`) не застосовувались.
- `Button`, `Flow`, тригер `HoverPopover` дописували пізніше створену дитину в кінець.
- Перестановка дітей (`move`) знищувала віджети в будь-якому контейнері й ставила елемент не туди.
- `Icon` не застосовував жодних змін після створення; плавний перехід кольору не працював. Тепер
  іконки з залежним від стану кольором у барі справді анімуються.
- `ShaderCanvas` мав той самий дефект з `animated` / `fragmentSource`.

`shell-app` після цих правок лише збирається й лінкується; візуально бар не перевірявся.

## Далі

1. **Спайк полотна** для зуму й панорами матриці: чи GTK коректно масштабує живі віджети (чіткість
   тексту, кліки й hover по збільшеному, `bounds` і скріншоти інспектора). Імовірний шлях —
   `GtkFixed` із трансформацією дочірнього віджета (не перевірено). У `framework` бракує також подій
   скролу й перетягування.
2. Зум і панорама для виглядів Compose і Table.
3. Вигляди **Focus** і **Board**: чи потрібні взагалі, ще не вирішено.

Мокапи чотирьох варіантів (A таблиця, B Compose Preview, C фокус, D дошка), приватна сторінка:
<https://claude.ai/artifact/SHDayjqcnDEdyXjxVkPbh5>. Обрано B як основний і A як перемикач; обидва
реалізовані без зуму.

## Відомі обмеження і неперевірене

- `reload` і `set_visible` перезапускають атлас: вибрана story, args і вигляди скидаються. Відновлення
  стану демоном обговорювалось, не робилось.
- Відкритий список `DropDown` і popover-и не потрапляють на скріншот і в `tree`; `set_state` не викликає
  `Modifier.onHover`.
- `framework` і `atlas:core` не публікуються: інший репозиторій поки не отримає бібліотеки (відкладено).
  На другому справжньому проєкті тулза не перевірялась.
- Порядок накладання в `Stack` / `Overlay` для пізніше доданих дітей не виправлений.
- Закриття видимого вікна рукою перевірено лише тестом із фейковим атласом.
- Тригер `HoverPopover` і `ShaderCanvas` виправлені без окремих тестів.

## Домовленості

- Баги у власному коді виправляти в причині, а не обходити.
- Не тягнути важкі залежності (KWin як headless-дисплей відхилено саме тому).
- Усе, що стосується атласу, живе в `atlas/`.
- Відповіді й документація — українською (README ще має російську копію).
