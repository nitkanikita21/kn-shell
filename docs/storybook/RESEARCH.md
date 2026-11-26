# Atlas: результати досліджень (hot swap, preview-процес, Wayland)

Статус: досліджено, hot swap відхилено, спайк `spike-wl` (child → host) завершено. Далі — реалізація Atlas без hot swap.
Деталі експериментів: `~/CODING/preview-poc/KNOWLEDGE.md` (розд. 8–9), спайк `~/CODING/preview-poc/spike-wl/`.

## 1. Hot swap Kotlin/Native — не робимо

- Офіційного hot reload для K/N немає. JetBrains Compose Hot Reload — лише JVM Desktop.
- In-process `dlopen`/`dlclose`: кожна .so має свій K/N runtime, GC roots, TLS-деструктори, GTK-callbacks на вказівники з .so. Після `dlclose` — SIGSEGV з відкладеною затримкою. Підтверджено аналізом `keel` (`README.md`, `doc/前序讨论.md`).
- Модель `keel` (не `dlclose`, нові .so на кожен reload) — RSS росте з кожним reload.
- LLVM JIT (KotlinConf 2026, relink у процес): у `~/.konan/dependencies/llvm-21-*` немає `libLLVM`/ORC, тож недоступно.
- Вирішено: зміна коду = перезапуск процесу. Стан зберігаємо окремо.

## 2. Мерехтіння при перезапуску

- Причина: child — видиме toplevel-вікно на робочому столі.
- Рішення: child не має бути на desktop (in-process або окремий Wayland-клієнт нашого сервера, див. розд. 4).

## 3. Broadway — опційний транспорт, не ядро

- Дає: запуск без desktop-вікна, перегляд у браузері, справжні GDK-події.
- Не дає: hot reload, zero-copy, ізоляцію компонента.
- Частота кадрів під broadway залежить від активності браузера (фонова вкладка не отримує rAF).
- Пастка: `/usr/sbin/broadwayd` — gtk3-бінарник. Для GTK4 потрібен `/usr/bin/gtk4-broadwayd`.

## 4. Zero-copy: що є і чого нема

- `GskRenderer.render_texture` повертає `GdkDmabufTexture` (ngl і vulkan), але публічного getter-а fd/GL-id немає.
- Fallback: `texture.download` у RAM ≈ 1.7 мс на кадр для 400×300 (≈480 КБ). Для превʼю — прийнятно.
- Headless-варіанти (Xvfb, sway, weston headless) — або software, або потребують sudo/пакетів. Не вибрані.
- `waylandcraft` (GPLv3, Minecraft-композитор) — референс того самого dmabuf-напрямку. Код не копіювати.

## 5. Спайк: host = Wayland-композитор (`spike-wl`)

Перевірено:
- GTK4 child з `WAYLAND_DISPLAY` нашого сервера. Вікна на desktop немає.
- Child шле `linux-dmabuf`; host імпортує через `GdkDmabufTextureBuilder` → `Gtk.Picture`. Скріншот підтвердив.
- Input: `GtkEventControllerMotion` + `GtkGestureClick` → `wl_pointer`. Клік через `/dev/uinput` дає `clicks: N`.

Вимоги GTK 4.24.1 до сервера (`gdk/wayland/gdkdisplay-wayland.c`): `wl_compositor`, `wl_shm`, `wl_data_device_manager` (обов'язкові), далі `wl_subcompositor`, `xdg_wm_base`, `wl_seat`. Без них `Gdk-WARNING: does not provide one or more of the required interfaces`.

Пастки:
- GDK не закриває dup-fd, передані в builder → треба destroy-callback (інакше ~1 fd на кадр).
- `wl_pointer.enter` має піти перед motion/button.
- Без `wp_presentation` GDK тримає половину частоти (60 commit/s замість 144). Фікс: `presented` шлемо з host frame clock. Якщо слати одразу в commit — runaway ~4800 commit/s.
- Release-керування: замінений dmabuf відпускається на наступному тіку host-а (попередній кадр уже відправлено). Це не повний трекінг завершення рендера, але розривів не спостерігали.
- Не перевірено: child і host на одному GPU.

Стан спайку: завершено для демо. `comp_host.c` тримає кілька surface (один `Gtk.Picture` на surface), `child_elements.py` дає три елементи (button/slider/progress). Перевірено: dmabuf на всіх surface, input у потрібну surface (`SPIKE_SELFTEST=1`), release заміненого буфера на наступному тіку host-а, знищення однієї surface без падіння, рестарт child-а (`kill -9` → host живий, нові surface через 500 мс), стабільна кількість fd, ~144 commit/s на surface. Виправлено пастки: SIGPIPE при смерті клієнта, UAF у `pending_fb` (ітерація з кінця). Обмеження: host має бути видимим (інакше frame clock стоїть і child не отримує release), немає keyboard/subsurface/popup, release не трекає завершення рендера повністю.

## 6. Варіант превʼю «один елемент = одна surface»

- Child створює окремий toplevel на елемент, без CSD (`gtk_window_set_decorated(FALSE)`), розміром з елемент.
- Host — `Gtk.Picture` на surface, input маршрутизується в Picture під курсором.
- Перевірено в `spike-wl`: три toplevel-и → три Picture; `SPIKE_SELFTEST` клікає по кнопковій surface, child бачить клік саме в ній.
- Альтернативи (одна surface з host-crop; subsurface) відхилені: зайвий рендер, складна маршрутизація.

## 7. Відкриті рішення для Atlas

- Процес: in-process каталог (проста схема, без dmabuf) vs out-of-process через Wayland-surface (ізоляція, zero-copy). Спайк довів out-of-process шлях для кількох surface (dmabuf + input + release), але без hot swap ізоляція менш потрібна → за замовчуванням in-process.
- Модулі: core/ui-kit — один модуль з двома пакетами чи два Gradle-модулі.
- Розміщення runner-а: **окремий модуль-споживач** (як `shell-app`), залежить від `framework`. Не всередині `framework`: framework — бібліотека для зовнішніх розробників, Atlas — інструмент, окремий від неї.
- Фази: A — runner одного компонента (`Shell.run`), B — каталог з controls.

**Прийняті рішення (чинні, уточнюють пункти вище):**
- Процес — in-process; out-of-process через Wayland-surface відкладено.
- Модуль `atlas:core` (каталог `atlas/core`) (DSL, модель args, runner, UI) окремо від `framework`; `framework` не знає про Atlas.
- Runner — compilation `linuxX64Atlas` у `shell-app` (`shell-app/src/linuxX64Atlas`), а не окремий модуль; executable `atlas` (debug) з точкою входу `atlas.main`.

## 8. Headless-дисплей для MCP-демона (прийнято)

Контекст: `atlas/mcp` має керувати атласом невидимо, без вікна на робочому столі і без input-пристроїв.

- **Власний мінімальний композитор на Rust** (`atlas/mcp/src/compositor.rs`, протокольні биндинги
  `wayland-server`). Прийнято. Він дає лише handshake, `configure` і `wl_callback.done`; нічого не малює,
  а `wl_seat` без клавіатури й миші. Input іде через інспектор.
- **GPU через `linux-dmabuf`** (`src/compositor/dmabuf.rs`). З одним лише `wl_shm` GTK не міг показати кадр
  з GPU (`GskVulkanRenderer`: «Queue family does not support surface», GL падав на llvmpipe). Досить
  оголосити протокол v4 з render node у feedback: буфери приймаються й одразу звільняються без імпорту,
  графічні бібліотеки композитору не потрібні. Перевірено на NVIDIA (RTX 4060): `GskVulkanRenderer`,
  100 циклів «зміна → скріншот» без помилок.
- **KWin відхилено** як важку залежність: тягне весь desktop-стек заради headless-режиму.
- **Broadway відхилено** для цієї задачі: `gtk4-broadwayd` заморожував frame clock без браузерного клієнта,
  тож перекомпозиція і layout не відбувалися. Пункт 3 вище описує ту саму залежність від клієнта.
- Деталі демона, інструменти й обмеження: `docs/storybook/README.uk.md`, розділ «MCP».

## 9. MCP-тулза без привʼязки до проєкту (прийнято)

- Проєкт нічого не конфігурує для тулзи: вона питає Gradle власним init-скриптом, які модулі мають
  таску `runAtlas…Executable…`. Конфіг-файл і вгадування за структурою тек відхилені.
- Створення цієї таски винесено в плагін `dev.knshell.atlas` (`atlas/plugin`).
- Плагін компілюється `kotlin("jvm")`, а не `kotlin-dsl`: вбудований у Gradle 8.10 Kotlin старіший за
  метадані Kotlin Gradle plugin 2.x.
- Відкладено: publishing `framework` і `atlas:core`, без якого інший репозиторій не отримає бібліотеки.
