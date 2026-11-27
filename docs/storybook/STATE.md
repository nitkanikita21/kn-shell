# Atlas: стан роботи (передача на інший комп'ютер)

Знімок на 2026-11-26, гілка `atlas`. Опис того, як усе влаштовано, — у [`README.uk.md`](README.uk.md);
тут лише те, що потрібно, щоб продовжити.

## Запуск на новій машині

```bash
git checkout atlas && git pull

./gradlew :demo:shell-app:runAtlas                              # каталог у вікні
cargo install --path atlas/mcp                             # MCP-тулза → ~/.cargo/bin/atlas-mcp
claude mcp add --scope user atlas -- atlas-mcp             # один раз на машину
```

Після реєстрації перезапустити Claude Code у корені репозиторію. Перший пошук атласу йде через Gradle;
якщо бінарника атласу ще немає, спершу викликати інструмент `reload`.

Перевірити машину: `atlas-mcp doctor` (бібліотеки, JDK 21, render-вузол, сесія). Потрібно: GTK 4 з `gio-unix-2.0`, gtk4-layer-shell, librsvg, fontconfig, libepoxy, Rust (cargo),
JDK 21, `pkg-config`. `local.properties` не комітиться (машинний шлях до konan).

**Ноутбук перевірено (2026-11-27):** дві GPU (`renderD128` amdgpu, `renderD129` nvidia), композитор
бере першу. Під `headless-compositor` проходять GTK-тести, атлас рендериться на GPU й віддає
скріншоти. Через сам `atlas-mcp` (`reload`, `screenshot`) не перевірялось. Якщо там щось не працює,
у `logs` будуть помилки Vulkan/EGL.

## Тести

```bash
./gradlew :framework:linuxX64Test :atlas:core:linuxX64Test :icons:core:linuxX64Test   # без дисплея
./gradlew :framework:linuxX64GraphicsTest :icons:core:linuxX64GraphicsTest            # GTK-тести
./gradlew :atlas:mcp:check :headless-compositor:check                                 # cargo test
./gradlew :demo:shell-app:atlasGoldenCheck                                                 # вигляд stories
```

GTK-тести йдуть у графічній сесії, якщо вона є, а без неї самі запускаються під `headless-compositor`.
Примусово без екрана: `env -u WAYLAND_DISPLAY -u DISPLAY ./gradlew …`. Будь-який інший клієнт можна
запустити так: `headless-compositor -- <команда>`.

На 2026-11-27: `framework` 39 без дисплея і 124 графічні, `atlas:core` 114, `icons:core` 20 і 2,
демон 23, композитор 4; без падінь.

## Що зроблено

- **Каталог:** контроли для всіх args (`string`, `boolean`, `enum`, `int`, `float`), значення за кожною
  story, Reset, темна тема, `--story`.
- **Матриці:** вигляди `Compose` (групи, що згортаються) і `Table`, перемикач, типовий вигляд у DSL
  (`variants("…", view = MatrixView.Table)`), значок вигляду в сайдбарі.
- **MCP (`atlas/mcp`):** Rust-демон; приватний дисплей — з модуля `headless-compositor` (рендер на
  GPU через `linux-dmabuf`). Атласи знаходить через Gradle init-скрипт у модулях із плагіном
  `dev.knshell.atlas`, за таскою `runAtlas…Executable…`.
  Інструменти демона: `list_atlases`, `use_atlas`, `set_visible`, `reload`, `status`,
  `logs`, `stop`; решту віддає сам атлас.
- **Golden:** прогін усіх stories без екрана з порівнянням пікселів, «Перевірити» й «Прийняти» у
  вікні атласу, шрифти проєкту в `golden-fonts/`. Еталони демо-stories лежать у кореневій
  `atlas-goldens/` (`goldens=atlas-goldens` у `gradle.properties`). Записані на ноутбуці, на ПК збіг
  ще не перевірявся.
- **Gradle-плагін (`atlas/plugin`):** `id("dev.knshell.atlas")` створює compilation, executable і таски.
- **Структура:** `atlas/core`, `atlas/mcp`, `atlas/plugin`; окремо `headless-compositor`.
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

- `reload` і `set_visible` перезапускають атлас, але вибрана story, вигляд матриці й значення args відновлюються демоном з нового атласу (`Session` у `atlas.rs`). Ті частини, яких у новій збірці немає, демон перелічує у відповіді.
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
