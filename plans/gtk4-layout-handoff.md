# Handoff: GTK4 layout semantics (K4 — continuation on the laptop)

Цей файл — точка входу після перерви. План-чекліст: `plans/gtk4-layout-semantics.md`
(виконується, 3/10 кроків позначено `plannotator_mark_done`).

## 0. Стан на момент зупинки

- K1 (тести), K2 (фікси C), K3 (`KnBoxLayout` + `weight`) — **зроблено**.
-_suite_: `28 tests, 2 failed`. Обидва red — це **навмисні** цілі K4:
  - `layout state does not survive a chain that dropped it` → `K4: an empty chain must clear the expand flag. Expected <0>, actual <1>`
  - `main-axis alignment does not travel through the expand flag` → `K4: align must not be smuggled through the expand flag`
- `:framework:compileKotlinLinuxX64`, `:shell-app:compileKotlinLinuxX64` — чисті.
- Усі виклики `Row`/`Column` у репо передають `spacing =` іменованим аргументом, тому
  вставка `homogeneous` другим позиційним параметром нікого не зламала (перевірено `rg`).

## 0.1 Пуш — ВИКОНАНО

`origin = https://github.com/nitkanikita21/kn-shell.git` (публічний, створений користувачем),
гілка `gtk4-layout-semantics` запушена (коміт коду + коміт plans), remote HEAD = ця гілка.

На ноуті достатньо:

```bash
git clone https://github.com/nitkanikita21/kn-shell.git KN-shell
cd KN-shell && git checkout gtk4-layout-semantics   # якщо clone не взяв гілку автоматом
```

`master` на GitHub немає — історія до 7dbac56 приїхала разом з цією гілкою як предки.
Твій незакомічений рефакторинг (shell-app DI/theme, `:icons:core`, docs) лишився на
десктопі, на ноуті його не буде (див. §7).

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
Повний прогін: `./gradlew :framework:linuxX64Test` → 51 testів, очікувані 2 red.

## 2. K4 — що робити

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

5. **Очистити документацію/коментарі від імені видаленої функції**:
   `rg -n 'reconcileMainAxisAlign' --glob '*.kt'` → залишаться тільки згадки в
   `LayoutProbeTest.kt` (коментарі, lines 199/330/500). Переформулювати їх у
   минулий час («the removed `reconcileMainAxisAlign` hack»), щоб тести не посилались на
   код, якого більше немає.

6. Після green: `plannotator_mark_done({ step: 4 })`.

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
  (≈ line 152), `Modifier.mainAxisWeight()`, `applyModifiers` (≈ 533–742).
- `framework/src/nativeMain/kotlin/dev/knshell/compose/Layout.kt` — `Modifier.weight(value)`.
- `framework/src/nativeMain/kotlin/dev/knshell/compose/GtkApplier.kt` — `GtkNode.modifier`,
  `open fun applyModifier`, `open fun pushWeight()`.
- `framework/src/nativeMain/kotlin/dev/knshell/compose/Widgets.kt` — `GtkBoxNode`
  (`configureBox`, `syncWeights`, `pushWeight`, `attachBoxLayout`), `gtkBox(...)`,
  `Row(spacing, homogeneous, modifier, content)`, `Column(...)`, `Box`.
- `framework/src/nativeTest/kotlin/dev/knshell/compose/LayoutProbeTest.kt` — 28 тестів,
  групи: 1 «GTK native semantics», 2 «DSL contract» (red → K4), 3 «The animated box»,
  4 «KnBoxLayout (K3)». Хелпери на file-scope: `box(..., root)`, `managedBox(..., root)`,
  `measure`, `Requested`, `cell`, `childrenOf`, `rooted`, `css`, `weight`, `label`,
  `wrappingLabel`, `applyModifier`, `attachAnimated`, `animatedContent`, `pushCurrent`,
  `allocRect`.
- `scripts/anim-bench.sh:326` — `C_LAYOUT` тепер вказує на `kn_box_layout.c` (gate:
  `gtk_widget_add_tick_callback|g_get_monotonic_time|kn_ease` — 0 хітів ✓).

## 5. Побіжна загроза для K4/K7 (не блокує, але не забути)

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

## 7. Стан git (на 2026-11-17 23:05)

Робота K1–K3 **закомічена** на окремій гілці, ваш рефакторинг лишився у робочому дереві
(індекс недоторканий: 13 staged-записів shell-app):

```
* gtk4-layout-semantics  HEAD   docs(plans): GTK4 layout plan and laptop handoff note
                          af82a6d feat(layout): GTK4-correct box layout via a single KnBoxLayout
  master                 7dbac56 build: bump Kotlin 2.1.0 -> 2.4.20, add Inara DI, ...
```

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
# очікування: 28 тестів, 2 red (обидва — цілі K4). Якщо red більше — не чіпати K4,
# а з'ясувати чому (зазвичай не перекомпільований rust-core або немає display).
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
