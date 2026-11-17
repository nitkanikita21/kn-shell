# Handoff: GTK4 layout semantics (K6 — continuation on the laptop)

Цей файл — точка входу після перерви. План-чекліст: `plans/gtk4-layout-semantics.md`
(виконується, 5/10 кроків: K1, K2, K3, K4, K5 позначені `[x]`).

## 0. Стан на момент зупинки
- K1 (тести), K2 (фікси C), K3 (`KnBoxLayout` + `weight`), **K4 (видалено `reconcileMainAxisAlign`,
  чесний скид полів), K5 (max-size як стеля в `KnBoxLayout`) — зроблено**.
- _suite_: `64 tests, 0 failed`. Навмисно червоних цілей більше немає: від тепер кожен крок
  починається з тесту, а green тримається весь час.
- K5 лишив після себе **єдиний канал per-child властивостей лейауту**: `KnChildLayout
  { weight; max_width; max_height; }` у `kn_box_layout.c` + `setBoxChildWeight` /
  `setBoxChildMax` (+ getter-и) у UniFFI, і `GtkNode.pushChildLayout()` (базовий клас, виклик з
  базового `applyModifier()`). K6 (CenterBox) і K7…K10 мають будуватися на цьому ж каналі, а не
  вигадувати новий. Деталі + чесна межа «min beats max» — `plans/gtk4-layout-semantics.md` §6.2.
- K4 зроблено через **ownership полів на самому віджеті**: `g_object_set_data` з ключем
  `kn-modifier-<field>` ставиться, коли ланцюг модифієрів записав поле, і знімається зі
  скиданням до дефолту GTK, коли перестав. Поле, якого ланцюг ніколи не писав, не чіпается
  взагалі — тому `Image.pinSize()` (`GTK_ALIGN_START`) і `GtkOverlayNode.attachChild()`
  (CENTER) лишилися цими. Стан живе на `GtkWidget`, тож `applyModifiers` можна викликати й без
  `GtkNode` (саме так робить тест). Деталі й виміряні дефолти — `plans/gtk4-layout-semantics.md` §6.1.
- ⚠️ Таблиця §2 нижче («`valign` за замовчуванням `GTK_ALIGN_START`») була **хибною**: на голому
  `GtkLabel` під GTK 4.24 обидві осі = `GTK_ALIGN_FILL`. Текст лижено як застереження.
- `:framework:compileKotlinLinuxX64`, `:shell-app:compileKotlinLinuxX64`,
  `:shell-app:linkDebugExecutableLinuxX64` — чисті.
- Усі виклики `Row`/`Column` у репо передають `spacing =` іменованим аргументом, тому
  вставка `homogeneous` другим позиційним параметром нікого не зламала (перевірено `rg`).

## 0.1 Пуш — ВИКОНАНО

`origin = https://github.com/nitkanikita21/kn-shell.git` (публічний, створений користувачем),
гілка `gtk4-layout-semantics` запушена (коміт коду + коміт plans), remote HEAD = ця гілка.

На ноуті достатньо:

```bash
git clone https://github.com/nitkanikita21/kn-shell.git KN-shell   # приходить master (default)
cd KN-shell && git checkout gtk4-layout-semantics
```

На GitHub дві гілки: `master` = 7dbac56 (твій, без нашого лейаута) і
`gtk4-layout-semantics` = 7dbac56 + af82a6d + 4519329 (наша робота). Твій
**незакомічений** рефакторинг (shell-app DI/theme, `:icons:core`, `docs/*`) не заїде
нікуди — він лишається на десктопі (див. §7).

## 1. Як прогнати тести (з того, що вже працює)

```bash
cd ~/CODING/KN-shell
source .tools/env.sh
./gradlew :framework:linuxX64Test --console=plain -q --tests '*LayoutProbeTest*'
# gradle XML плутає name↔message — читати напряму з бінарника:
./framework/build/bin/linuxX64/debugTest/test.kexe \
  --ktest_filter='dev.knshell.compose.LayoutProbeTest.*' --ktest_logger=SIMPLE
```

Потрібен display (`init { check(gtk_init_check() != 0) }` у класі — інакше core dump).
`--ktest_logger=PLAIN` кидає `IllegalArgumentException`, використовуй `SIMPLE`.
Повний прогін: `./gradlew :framework:linuxX64Test` → 54 тести, очікування 0 red.

## 1.1 Середовище (досвід Pure Arch, ноут)

`rust-toolchain.toml` у репо немає, а cargo 1.100 ламає gobley 0.3.7 (читає legacy
`target/debug/build/<pkg>/<hash>/output`, новий cargo пише `…/<pkg>-<hash>/output`):

```bash
rustup toolchain install 1.90.0 --profile minimal && rustup default 1.90.0
./gradlew :shell-app:linkDebugExecutableLinuxX64 --console=plain   # ~2 хв з чистого кешу
KN_SHELL_MODE=windowed shell-app/build/bin/linuxX64/debugExecutable/shell-app.kexe
```

Симптом без фіксу: `Execution failed for task ':rust-core:cargoBuildLinuxX64Debug'` +
`FileNotFoundException: …/build/quote/<hash>/output`.

## 2. K4 — ВИКОНАНО (лишається як опис механіки, на якій будувати K5+)

Файл: `framework/src/nativeMain/kotlin/dev/knshell/compose/Modifier.kt`

1. **Видалити** виклик `reconcileMainAxisAlign(widget)` (≈ line 742) і саму функцію
   `internal fun reconcileMainAxisAlign(widget: CPointer<GtkWidget>)` (≈ lines 756–772,
   разом із її KDoc). Причина (L1): вона вмикала `hexpand`/`vexpand` батькового осі для
   будь-якого `align(CENTER|END)`, тобто підміняла семантику клітинки прапорцем expand.
   Тепер клітинку дає `KnBoxLayout` (`kn_box_layout.c`, `main_pos` у `kn_box_layout_allocate`),
   а тест `main-axis align positions a child inside its cell without touching the expand flag`
   (group 4) вже це підтверджує без expand.

2. **Повний скид полів щопроходу** (L9) у кінці `applyModifiers` (≈ lines 726–741):
   зараз `hexpand?.let { … }`, `vexpand?.let { … }`, `halign?.let { … }`, `valign?.let { … }`
   роблять запис тільки коли значення є → стан «прилипає» після рекомпозиції.
   Замінити на безумовні `else`-гілки зі **стандартними значеннями GTK**:

   | поле | дефолт GTK | чим скидати |
   |---|---|---|
   | `hexpand`/`vexpand` | `FALSE` | `gtk_widget_set_hexpand(widget, 0)` |
   | `halign` | `GTK_ALIGN_FILL` | `gtk_widget_set_halign(widget, GTK_ALIGN_FILL)` |
   | `valign` | `GTK_ALIGN_START` | `gtk_widget_set_valign(widget, GTK_ALIGN_START)` |
   | `opacity` | `1.0` | `gtk_widget_set_opacity(widget, 1.0)` |

   `margin*` і `size_request` уже пишуться безумовно — їх чіпати не треба.
   `MaxSizeElement` у K4 не змінюємо (це K5).

3. **Порядок правки C-vs-Kotlin**: `reconcileMainAxisAlign` викликається всередині
   `applyModifiers`, який виконується при кожному `applyModifier`. Скидання `halign`
   **не повинне** перетерти те, що пишуть інші місця (див. п.4) — тож спочатку зроби
   скидання, потім видали reconcile, потім прогнати тести; обидва red мають стати green
   без жодного нового red.

4. **Ризики, які треба перевірити grep'ом перед мерджем** (хто пише ці поля напряму,
   не через Modifier — безумовний reset у `applyModifiers` їх перетирає):
   - `framework/src/nativeMain/kotlin/dev/knshell/ui/primitives/Image.kt:87-88` —
     `pinSize()` ставит `GTK_ALIGN_START` + `size_request`, щоб 28x28 іконка не
     розтягувалась на висоту рядка. Якщо для цього віджета потім проїде `applyModifiers`,
     `halign` скинеться у `FILL` → іконка знову розтягнеться.
   - `framework/src/nativeMain/kotlin/dev/knshell/compose/Widgets.kt:199-200` —
     `GtkOverlayNode.attachChild` центрує дітей (`halign/valign = CENTER`), а
     `attachModifier` викликається **після** `attachChild` → скидання перетерти б центрування.
     Це й K8-хак одночасно.
   - `GtkCenterBoxNode` (Widgets.kt, `attachChild`, лічильник `attached`) — K6.
   Рішення, яке виглядає чесним: не робити «скидання до глобального дефолту» для
    віджетів, яким щойно присвоєно стан ззовні, а записувати значення **якщо елемент був
    у ланцюзі АБО поле раніше ставив цей самий ланцюг**. Тобто тримати в `GtkNode`
    останній набір `layoutState` і скидати лише ті поля, які раніше писалися з Modifier
    (це прибирає і L9, і конфлікт ззовні). Але спочатку перевір, чи тест
    `layout state does not survive a chain that dropped it` вимагає саме повного скидання:
    він перевіряє `expand` після ланцюга, де `expand()` зник → «писали раніше» там істинне,
    тож варіант «скидати тільки те, що писав modifier» задовольняє обидва red і не чіпає
    Overlay/Image. Це і є рекомендована реалізація.

5. **Очистити документацію/коментарі від імені видаленої функції** — зроблено: у
   `LayoutProbeTest.kt` лишилися три згадки в коментарях, усі в минулому часі («the removed
   `reconcileMainAxisAlign()` hack»), як і просив цей пункт.

6. ~~Після green: `plannotator_mark_done({ step: 4 })`~~ — зроблено; K4 позначено `[x]` у плані.

### 2.1 Як K4 виглядає в коді (щоб не читати diff заново)

```kotlin
// Modifier.kt, в кінці applyModifiers — по одному такому блоку на поле:
if (halign != null) {
    gtk_widget_set_halign(widget, halign)
    setFieldWritten(widget, HALIGN_KEY, true)
} else if (fieldWritten(widget, HALIGN_KEY)) {
    gtk_widget_set_halign(widget, GTK_ALIGN_FILL)
    setFieldWritten(widget, HALIGN_KEY, false)
}

private const val HALIGN_KEY = "kn-modifier-halign"   // + VEXPAND/HEXPAND/VALIGN/OPACITY

private fun fieldWritten(widget: CPointer<GtkWidget>, key: String): Boolean =
    g_object_get_data(widget.reinterpret<GObject>(), key) != null

private fun setFieldWritten(widget: CPointer<GtkWidget>, key: String, written: Boolean) {
    val value = if (written) widget.reinterpret<GObject>() else null
    g_object_set_data(widget.reinterpret<GObject>(), key, value)
}
```

Значення — вказівник на сам віджет: `g_object_set_data` не бере destroy-кобек, нічого не
frees, а читається лише факт наявності ключа. Спроба пакувати `Int`-у-покажчик
 (`interpretCPointer<UnitVar>(…)`) провалилась: `interpretCPointer` приймає `NativePtr`, а не
 `Long`. Записи `margin*` і `size_request` лишилися як були (умовний `size_request` — це
 майбутня K5, не L9).

## 2'. K5 — ВИКОНАНО (лишається як опис механіки, на якій будувати K6)

Дефект L6 закритий варіантом 1 (clamp у `KnBoxLayout`). Стелю зберігає та сама table
`KnChildLayout`, що й `weight`, поосі дитини (`max_width`/`max_height`, `KN_NO_MAX = -1`), тож
переорієнтація контейнера не переписує сенс `maxWidth(220)`. Застосування — у двох місцях:
`kn_measure_child()` (натюр тиснеться до стелі, але не нижче мінімуму дитини) і фінальний цикл
`allocate()` (головне поле `child_main`/`m` і поперечне `cross_own`). `weight`, `homogeneous` і
surplus-пази проходять через той самий clamp, тому cap має останнє слово.

DSL-канал: `Modifier.maxSizeCaps(): Pair<Int, Int>` → `GtkNode.pushChildLayout()` shoves
`setBoxChildMax(parent, child, w, h)`; попереду `pushChildLayout()` викликається з базового
`applyModifier()`, тому cap доходить і до `Text`/`Image` (раніше вагу знав лише `GtkBoxNode`).
Скидання — `-1` по осі (запис видаляється, коли вага дефолтна й обидві стелі `-1`) — те саме
правило L9 «absent = default».

**Обмеження, яке не виправляється (документоване в KDoc `MaxSizeElement`, `docs/shell-api.md` і
в тестах):** стеля не може бути нижчою за *власний мінімум* дитини. GTK не кліпає — алокація
нижче мінімуму змушує `GtkLabel` малювати поверх сусідів і породжує
`Gtk-WARNING **: Trying to measure GtkLabel … for width of 203, but it needs at least 249` (обидва
відтворені на живому вікні з probe-UI). Тож `maxWidth` чесний на child'ах, що *можуть* стиснутись
(`Label(ellipsize = true)` / `wrap = true`), і сумний на однорядковому label — там капітал
`squeeze` неможливий у принципі. Це той самий компроміс, що й у CSS.

Не чіпано: `Label(maxWidthChars=…)` (`compose/Widgets.kt:547-570`) — Pango-еліпсис, інший
механізм (знижує сам мінімум).

## 2''. K6 — CenterBox: іменовані слоти замість лічильника (наступний крок, L7)

Факт із групи 3: `shrink-center-last` у `GtkCenterBox` = **default TRUE**, але на практиці обидва
значення дають однакову алокацію, бо центр завжди `width − (start + end)`, і жоден із них не
висловлює «що стискається першим». Дефект L7 — `GtkCenterBoxNode` (`compose/Widgets.kt:146-165`)
визначає слот за **лічильником `attached`**: перший приєднаний = start, другий = center, третій =
end; порядок обходу/перекомпозиції тому змінює семантику, а `detachChild` лічильник не відкочує
(проверити в коді — це й буде перший червоний тест).

Пропозиція плану: іменовані слоти (`slot = Slot.Start | Center | End`) в DSL-етикеті вузла, що
гарантує `gtk_center_box_set_start_widget/…_center_widget/…_end_widget` поза Dependence на
порядку; документація `shrink-center-last` (4.12) — чому на ньому не можна будувати.

Порядок дій (той самий, що працював K3/K4/K5):
1. Червоні тести в групі 3 `LayoutProbeTest.kt` (перекомпозиція/перестановка не змінює слотів;
   видалення start-дитини не зсуває center в end; …) — 64 green мають лишитися, нові — red.
2. Правка `compose/Widgets.kt` (+ DSL-параметр, якщо треба новий `enum` — див., як зроблено
   `Align`/`AlignElement`).
3. `rg -n 'attached' framework/src/nativeMain/kotlin/dev/knshell/compose/Widgets.kt` — переконатись,
   що лічильник не лишився єдиним джерелом правди.
4. Візуал — K5 показав, що probe-UI в `shell-app/main.kt` найкращий інструмент (потім повернути
   `main.kt` до стану commit! резервна копія: `cp main.kt /tmp/main.kt.bak` ДО правки).

## 3. Рішення, які вже ухвалено (не переписувати)

- **Вага = пропорційний expand.** Невзбиті (безвагові) діти беруть natural; весь залишок
  ділиться за вагами; частка не менша за minimum дитини і не більша за `main_size`;
  дефіцит зрізається пропорційно `room = size - min` (спочатку без expand-дів, потім усі).
  Див. `kn_box_layout.c` blocks `weighted children claim the leftover` / `deficit`.
  Це ж треба задокументувати в K9 (`weight` ≈ Android `layout_width=0dp`).
- **Homogeneous** = комірка `(main_size - spacing) / n` (+1 px першим `remainder` дітям),
  як у GtkBox, а не «як найбільша дитина».
- **Cross-вісь**: кожна дитина отримує повний `cross_size` (як GtkBox); власний align дитини
  вирішує, що з цим робити.
- **Align на головній осі**: `child_main = child_fill ? cell : child_nat` — CENTER/END дитина
  може бути ширшою за комірку (стоковий GtkBox так само). Кламп `m > main_size` прибрано
  свідомо; clamp у weighted-pass лишився только на `wanted > main_size`.
- **transfer-full пастка**: `gtk_widget_set_layout_manager()` забирає ownership — у
  `kn_box_attach()` НЕ можна робити `g_object_unref` на створеному менеджері
  (зламаний менеджер → `assertion 'GTK_IS_LAYOUT_MANAGER (manager)' failed`, діти 0x0).
- **Один батько**: вкладений бокс для тестів створювати з `managedBox(..., root = false)`,
  інакше `gtk_box_append` мовчки не спрацює (`assertion 'gtk_widget_get_parent (child) == NULL'`).
- **`measure(..., for_size)`**: для головні осі `for_size` = cross-розмір.
  wrapping label: `H(-1)=(31,237)`, `H(60)=(89,89)`; тому floor у тесті shrink — 89, не 31.
- **GtkWidget abstract** — `g_object_new(gtk_widget_get_type())` абортує; dead-код
  `kn_box_new_widget()` видалено.
- **Rust-менеджер неможливий**: `gtk4-rs` subclass отримує псований вказівник у C-host
  процесі (`invalid unclassed pointer in cast to 'GObject'`), менеджер лишається на C.
  Це вже записано в `docs/bar-api.md`.

## 4. Файли (щоб не шукати)

- `rust-core/src/kn_box_layout.c` — єдиний C-менеджер (708+ рядків). Компільується:
  `gcc -c rust-core/src/kn_box_layout.c $(pkg-config --cflags gtk4) -Wall -Wextra -Wno-unused-parameter`
- `rust-core/src/box_layout.rs` — UniFFI-шейм (13 export-ів). `rust-core/src/lib.rs:14` → `mod box_layout;`
  Видалено: `rust-core/src/kn_animated_size_layout.c`, `rust-core/src/animated_layout.rs`.
  Анімований API лишився сумісним як façades: `attachAnimatedSizeLayout`,
  `setAnimatedSizeFixed/Cross/Current`, `animatedSizeContent`, `animatedSizeIsReady`.
- `framework/src/nativeMain/kotlin/dev/knshell/compose/Modifier.kt` — `WeightElement`
  (≈ line 152), `Modifier.mainAxisWeight()`, `applyModifiers` (≈ 533–742). З K4: п'ять
  `kn-modifier-*` ключів і `fieldWritten`/`setFieldWritten` — у кінці файлу.
- `framework/src/nativeMain/kotlin/dev/knshell/compose/Layout.kt` — `Modifier.weight(value)`.
- `framework/src/nativeMain/kotlin/dev/knshell/compose/GtkApplier.kt` — `GtkNode.modifier`,
  `open fun applyModifier`, `open fun pushChildLayout()` (з K5 — вага **і** стеля, у базовому
  класі, виклик з базового `applyModifier()`), `fun CPointer<GtkWidget>.asContainerHandle()`.
- `framework/src/nativeMain/kotlin/dev/knshell/compose/Widgets.kt` — `GtkBoxNode`
  (`configureBox`, `syncChildLayouts`, `attachBoxLayout`), `gtkBox(...)`,
  `Row(spacing, homogeneous, modifier, content)`, `Column(...)`, `Box`.
- `framework/src/nativeTest/kotlin/dev/knshell/compose/LayoutProbeTest.kt` — 64 тестів,
  групи: 1 «GTK native semantics», 2 «DSL contract» (red → K4), 3 «The animated box»,
  4 «KnBoxLayout (K3)» (тут і кап-тести K5). Хелпери на file-scope: `box(..., root)`,
  `managedBox(..., root)`,
  `measure`, `Requested`, `cell`, `childrenOf`, `rooted`, `css`, `weight`, `label`,
  `wrappingLabel`, `truncatableLabel`, `minimumWidth`, `minimumHeight`, `maxSize`,
  `applyModifier`, `attachAnimated`, `animatedContent`, `pushCurrent`,
  `allocRect`.
- `scripts/anim-bench.sh:326` — `C_LAYOUT` тепер вказує на `kn_box_layout.c` (gate:
  `gtk_widget_add_tick_callback|g_get_monotonic_time|kn_ease` — 0 хітів ✓).

## 5. Побіжна загроза для K6/K7 (не блокує, але не забути)

`docs/bar-api.md` / `docs/shell-api.md` описують `Box` як «div», а код робить з нього
вертикальний `GtkBox` (block flow). Це L8 = K7. Не лікувати в K4.

## 6. Правила безпеки правок (досвід цього session)

- Ніколи не писати джерельні файли heredoc'ами, у яких є `$(...)` або backticks —
  `$(date +%s)` всередині `<<PY` розкрився і зламав `LayoutProbeTest.kt` (відновлення
  коштувало ~1 год). Тільки `write`/`edit` tools.
- Вивід bash обрізає відступи й може змінити перший символ рядка — дивитись точний
  контент через `read` або python `repr()`.
- Робоче дерево брудне ДО нашої роботи: ваш рефакторинг (`shell-app` DI/theme, `:icons:core`,
  `services/*.kt` → public constructor, `docs/{framework-api,shell-api,bar-api}.md`) — його
  **не** чіпали. Тому правки наших файлів комітити лише з явним переліком шляхів
  (`git commit -- <paths>`) — `git add -A` схопив би їхній staged-рефакторинг.

## 7. Стан git (на 2026-11-18 01:10)

K1–K4 **закомічені** на окремій гілці (`af82a6d` код K1–K3, `896b552`/`4519329`/`f4e894d` plans,
`fd20e54` = K4), ваш рефакторинг лишився у робочому дереві (індекс недоторканий: 13
staged-записів shell-app). K5 — черга наступного коміту (`feat(layout): cap a child's size in the
box layout manager`, файли:
`rust-core/src/kn_box_layout.c`, `rust-core/src/box_layout.rs`, `compose/{Modifier,GtkApplier,Widgets}.kt`,
`LayoutProbeTest.kt`, `docs/shell-api.md`, `plans/*.md`).

```
* gtk4-layout-semantics  HEAD   fd20e54 feat(modifier): own the layout fields a chain wrote, reset only those
                          f4e894d docs(plans): master is pushed too — the laptop clone lands on master, not the work branch
                          4519329 docs(plans): record the git incident — `commit --amend -- <path>` commits the whole index
                          896b552 docs(plans): GTK4 layout plan and laptop handoff note
                          af82a6d feat(layout): GTK4-correct box layout via a single KnBoxLayout
  master                 7dbac56 build: bump Kotlin 2.1.0 -> 2.4.20, add Inara DI, ...
```
Обидві гілки є на `origin`; default = `master`. Тобто `git clone` дає чистий `master`,
наш лейаут — тільки в `gtk4-layout-semantics`.

У коміт `af82a6d` свідомо **не** входять (це ваш icons/DI-workstream, не лейаут):
`compose/AnimatedSize.kt` (тільки KDoc-приклад з `Icon(Lucide.Clock)`),
`animation/Spring.kt` (`Easing.eased` → public), `services/*.kt` (public constructor),
`shell/Popover.kt`, `ui/primitives/Utilities.kt`, `docs/framework-api.md`, `docs/shell-api.md`,
`docs/bar-api.md` (містить і наші абзаци про анімований менеджер — тому не комітився).

**Remote НЕ налаштований** — репо створює користувач (токен `gh` = акаунт `Nitkanikita21`,
Valerii Hrokhotskyi). Після того як репо з'явиться:

```bash
git remote add origin git@github.com:Nitkanikita21/<repo>.git   # або gh repo set-default
git push -u origin gtk4-layout-semantics        # тільки наша гілка; master локально
```

На ноуті:

```bash
git clone -b gtk4-layout-semantics git@github.com:Nitkanikita21/<repo>.git KN-shell
cd KN-shell && git checkout gtk4-layout-semantics
```

⚠️ Ноут **не** отримає ваш icons/DI-рефакторинг (він не закомічений) — тож на ноуті
`framework`/`shell-app` буде компілюватися зі старим `Icon(glyph: String)` API. Це нормально
для K4: тести лейаута (`:framework:linuxX64Test`) від цього не залежать. Якщо потрібен і
рефакторинг — окремо `git add shell-app ... && git commit` на `master` і пуш туди.

Перевірка на ноуті після клону (обов'язково):

```bash
source .tools/env.sh
./gradlew :rust-core:compileReleaseKotlinAndroid :framework:compileKotlinLinuxX64 --console=plain -q
./gradlew :framework:linuxX64Test --console=plain -q --tests '*LayoutProbeTest*'
# очікування: 64 тестів, 0 red (стан після K5). Якщо red > 0 — не чіпати наступний крок,
# а з'ясувати чому (зазвичай не перекомпільований rust-core, не той toolchain або немає display).
# `.tools/env.sh` у репо нема — `source` дасть помилку, вона безневинна (див. §1.1 про rust 1.90.0).
```

Контекст з цією сесією не переноситься: на ноуті прочитай `plans/gtk4-layout-handoff.md`
повністю й починай з §2 (K4).

## 8. Інцидент з git (записано, щоб не повторити)

Під час першого пуша в коміт потрапив **чужий staged-рефакторинг** (13 файлів `shell-app/…` +
`plans/icons-lucide.md`). Причина — одна конкретна команда:

```bash
git commit --amend --no-edit -- plans     # НЕБЕЗПЕЧНО при забрудненому індексі
```

`git commit <pathspec>` без `--amend` працює в режимі `--only` (бере лише вказані шляхи —
саме так чистий `af82a6d`). Але `--amend` із pathspec'ом игнорує `--only` і бере **весь
індекс**, тож усі staged-записи користувача поїхали у мій коміт.

Правила на майбутнє (на цій гілці індекс користувача містить його WIP):
1. Ніколи не роби `git commit --amend` при нечистому індексі. Замість цього — новий коміт
   `git commit -- <paths>` (без `--amend`), або plumbing:
   `GIT_INDEX_FILE=/tmp/x git read-tree <base>^{tree} && git update-index --add -- <paths> &&
   TREE=$(git write-tree) && C=$(git commit-tree $TREE -p <base> -m "...") &&
   git update-ref refs/heads/<branch> $C` — індекс при цьому не чіпається взагалі.
2. Перед будь-яким пушем: `git diff --name-status <base> HEAD` і перевірити, що там тільки наші шляхи.
3. Історія вже переписана (force-with-lease): гілка = `af82a6d` + `896b552`. Старі
   `3d563ea`/`92cf8fc` доступні за SHA доки GitHub не запустив GC — **користувач свідомо
   обрав «залишити як є»**. На ноуті брати саме `896b552` (remote HEAD).
