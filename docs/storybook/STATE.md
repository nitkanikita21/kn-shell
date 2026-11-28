# Atlas і UI-кіт: стан роботи (передача на інший комп'ютер)

Знімок на 2026-11-27, гілка `atlas-ux` (від `ui-kit`), обидві запушені. Опис атласу — у
[`README.uk.md`](README.uk.md), кіта — у [`demo/shell-ui-kit/README.md`](../../demo/shell-ui-kit/README.md);
тут лише те, що потрібно, щоб продовжити.

## Запуск на новій машині

```bash
git checkout atlas-ux

./gradlew :demo:shell-ui-kit:runAtlas                      # каталог кіта у вікні
KN_SHELL_DEMO=1 ./scripts/run.sh                           # бар і вікно-демо кіта
cargo install --path atlas/mcp                             # MCP-тулза → ~/.cargo/bin/atlas-mcp
claude mcp add --scope user atlas -- atlas-mcp             # один раз на машину
```

Після реєстрації перезапустити Claude Code у корені репозиторію. Перший пошук атласу йде через Gradle;
якщо бінарника атласу ще немає, спершу викликати інструмент `reload`.

Перевірити машину: `atlas-mcp doctor`. Потрібно: GTK 4 з `gio-unix-2.0`, gtk4-layer-shell, librsvg,
fontconfig, libepoxy, Rust (cargo), JDK 21, `pkg-config`; для `a11y-check.py` ще `python-gobject` і
`at-spi2-core`. `local.properties` не комітиться (машинний шлях до konan).

## Тести

```bash
./gradlew :framework:linuxX64Test :atlas:core:linuxX64Test :icons:core:linuxX64Test :demo:shell-ui-kit:linuxX64Test
./gradlew :framework:linuxX64GraphicsTest :atlas:core:linuxX64GraphicsTest :icons:core:linuxX64GraphicsTest   # GTK
./gradlew :atlas:mcp:check :headless-compositor:check                                 # cargo test
./gradlew :demo:shell-ui-kit:atlasGoldenCheck                                         # вигляд і play, ~5 хв
./gradlew :demo:shell-ui-kit:linkAtlasDebugExecutableLinuxX64 && scripts/a11y-check.py
```

GTK-тести завжди йдуть під `headless-compositor`: на робочому столі нічого не з'являється, а тести
можуть натискати клавіші. Будь-який інший клієнт: `headless-compositor -- <команда>`.

## Що зроблено в гілці `ui-kit`

- `demo/shell-app` і `demo/shell-ui-kit`; дизайн-система перенесена в кіт, сервіси лишились у шелі.
- `framework`: ARIA-семантика, клавіатура й фокус, `Popup`, `OverlayHost`/`Portal`, `enabled`,
  поле пароля; інспектор бачить popover-и, ролі, стани й фокус.
- `headless-compositor`: віртуальна клавіатура.
- Атлас: `play { }`, кадри станів, середовища (Dark/Light), категорії, `docs`.
- Кіт: 23 компоненти, сторіси, еталони в `demo/shell-ui-kit/atlas-goldens/`.
- `shell-app` перейшов на кіт; його каталог і кореневу `atlas-goldens/` прибрано.

## Що зроблено в гілці `atlas-ux`

Пошук і клавіші; панель над превʼю (ширина, вимірювання, GTK Inspector), ховання панелей, памʼять
стану; перевірка однієї story чи групи, вкладка «Сценарій»; MCP-інструменти `search_stories`,
`check_story`, `check_status`, `set_width`. Мокап: `docs/storybook/mockups/atlas-ux.html`. Свідомо не
робились: панель і перевірка доступності, журнал подій.

### Виправлені баги `framework` (перевірити бар `shell-app`)

- `width(…).height(…)` затирали одне одного; так само два `align` чи `fillWidth().vExpand()`.
- Фокусовний `Box`/`Column` не був зупинкою Tab.
- Вміст кнопки не отримував її ширину.
- `Label(wrap = true, maxWidthChars = …)` обрізався до одного рядка.
- Порядок накладання в `Stack` / `Overlay` для пізніше доданих дітей.
- Давніші: sub-node стилі, порядок і перестановка дітей, `Icon` після створення, `ShaderCanvas`.

Бар `shell-app` після цих правок лише збирається: layer-shell у композиторі немає, **візуально бар не
перевірявся**.

## Далі

1. Переглянути бар і демо: `KN_SHELL_DEMO=1 ./scripts/run.sh`.
2. На ПК: `./gradlew :demo:shell-ui-kit:atlasGoldenCheck` (еталони записані на ноутбуці).
3. Прослухати кіт з Orca за чеклістом у README кіта.
4. Атлас: зум і панорама матриць (спайк полотна), вигляди Focus і Board — не вирішено.

## Відомі обмеження і неперевірене

- `reload` і `set_visible` перезапускають атлас, але вибрана story, вигляд матриці й значення args відновлюються демоном з нового атласу (`Session` у `atlas.rs`). Ті частини, яких у новій збірці немає, демон перелічує у відповіді.
- Відкритий список `DropDown` не потрапляє на скріншот і в `tree`; `set_state` не викликає
  `Modifier.onHover`.
- `atlas-mcp` на цій машині не встановлювався: нові інструменти (`press`, `set_environment`) перевірені
  тестами демона з фейковим атласом, не через MCP.
- Скріншот інспектора може не вдатися, поки у вікні йде нескінченна анімація («the window painted
  nothing»); у каталозі кіта анімації вимкнені.
- `UModal` і `UToasterHost` самі не створюють окреме вікно для layer-shell (як це зробити — у README кіта).
- `framework` і `atlas:core` не публікуються: інший репозиторій поки не отримає бібліотеки (відкладено).
  На другому справжньому проєкті тулза не перевірялась.
- Закриття видимого вікна рукою перевірено лише тестом із фейковим атласом.
- Тригер `HoverPopover` і `ShaderCanvas` виправлені без окремих тестів.

## Домовленості

- Баги у власному коді виправляти в причині, а не обходити.
- Не тягнути важкі залежності (KWin як headless-дисплей відхилено саме тому).
- Усе, що стосується атласу, живе в `atlas/`.
- Відповіді й документація — українською (README ще має російську копію).
