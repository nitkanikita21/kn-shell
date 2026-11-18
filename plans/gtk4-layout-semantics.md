# GTK4 layout: правильна роботта лейаутом (підхід `Modifier` як в Android збережено)

Статус: **дослідження завершено, код не змінено** (plan mode). План прийнято без блокуючих питань — варіанти з trade-off позначені як «рішення на відповідному кроці»; жоден із них не змінює публічного DSL- API руйнівним чином.

## 1. Факти, перевірені на встановленому GTK 4.24.1

Проби будувалися через `gtk_widget_measure()` + `gtk_widget_size_allocate()` **без display** — другий важливий побічний висновок: лейаут можна тестувати headless (див. K1).

| Перевірка | Результат | Наслідок для framework |
|---|---|---|
| CSS `padding: 20px` на `GtkBox` | H `63→103`, V `17→57` | padding **справді працює** на контейнерах: generic measure GTK4honors `padding/margin/border` (`gtk/gtksizerequest.c`). Тож `Modifier.padding()` не зламаний — його не треба емулювати маржинами дітей |
| CSS `min-width: 200px` + `gtk_widget_set_size_request(50)` | `H(200/200)` | `set_size_request` = **лише підлога** (doc: "Sets the minimum size"), CSS `min-*` піднімає **і min, і natural** |
| CSS `max-width: 50px` | `Theme parser error: No property named "max-width"` | у GTK4 **немає max-size API**. Тому `Modifier.maxWidth()/maxSize()` у чинному коді — clamp лише для явно заданого розміру, а не обмеження |
| `halign=CENTER` + `hexpand=TRUE`, allocate 600px | `x=264 w=13` | align на головній осі **працює**, але тільки коли дитина отримала комірку |
| `halign=FILL` без `hexpand` | `w=59` (natural) | зайвий простір на головній осі дістається **лише expand-дітям** (`extra / n_expand` у gtkboxlayout.c) |
| `GtkBoxLayout` | публічний, але `G_DECLARE_FINAL_TYPE`, measure/allocate — static | субкласифікація неможлива → для weight потрібен власний manager |
| `GtkCustomLayout` | доступний | **не дає per-child властивостей** → для `weight` не підходить |
| weight / % of parent у GtkBox, CenterBox, GridLayout | відсутні всюди | офіційна позиція GNOME (ebassi): percentage-of-parent «фундаментально несумісно з GTK flow children→parent» (discourse.gnome.org/t/8093). Реальні проєкти (libadwaita `AdwWrapLayout`, `AdwClampLayout`) пишуть власний `GtkLayoutManager` |
| expand-propagation | `GtkBox` = **OR** по дітях (gtkbox.c:181); власний manager **не успадковує** це — треба свій `GtkWidgetClass.compute_expand` | якщо робимо свій контейнер, propagation треба реалізувати свідомо |

## 2. Висновок: чому саме такі пропозиції

Модель GTK4 — **bottom-up** (діти → parent), Android/Compose — **top-down** (parent →Constraints→ дитина). Усі наявні «хаци» в framework — це спроби розійти саме цю різницю. Дослідження показало: **крім власного layout manager'а, інструменту немає** (фінальні класи, немає max-API, немає weight-API). Отже пропозиції зводяться до: *(а)* перестати емулювати те, що GTK і так робить (padding), *(б)* реалізувати там, де GTK нічого не дає, власний шар (weight, чесний align без expand-хакa, max-clamp), *(в)* виправити вже наявний C-manager, який є одночасно і джерелом кількох багів.

Конкретні дефекти, знайдені в коді (із посиланням, куди дивитись):

| # | Місце | Проблема | Чому це міняємо |
|---|---|---|---|
| L1 | `framework/src/nativeMain/kotlin/dev/knshell/compose/Modifier.kt:742` `reconcileMainAxisAlign()` | вмикає `hexpand`/`vexpand`, якщо align ∈ {CENTER, END} уздовж орієнтації батька, і **ніколи не вимикає**; doc-коментар сам визнає наслідок («icon with `align(h = Center)` made its 44px badge stretch across the whole card») | хач через відсутність комірки; з власним manager'ом дитина отримує комірку чесно → хач видаляється |
| L2 | `rust-core/src/kn_animated_size_layout.c`, `allocate()` | `if (gtk_widget_get_hexpand(c)) n_expand++` — рахує **hexpand навіть для vertical axis** (має бути vexpand) | `fillWidth()` у Column-контейнері з анімованою висотою фальшиво «розширює» по головній осі |
| L3 | те саме, `measure()`/`allocate()` | бере з дітей **natural**, ігнорує minimum (`kn_measure_child` повертає `natural`); `(void) for_size;` | при shrink-анімації контент обрізається замість стискатися; HfW (Label wrap) не підтримується |
| L4 | те саме, `allocate()` | `int m = kn_measure_child(...); if (m < 1) m = 1;` | **руйнує нульові спейсери**: `Box(Modifier.size(0, 2))` (divider у `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/ui/test.ui.kt:147`) і `Space(width = 0)` стають 1px |
| L5 | те саме, `allocate()` | `position = (n_expand == 0 && n == 1 && extra > 0) ? extra / 2 : 0` — анімований контейнер **центрує** одну дитину, звичайний `Row` пактує на початок | одна DSL-семика, дві різні поведінки |
| L6 | `compose/Modifier.kt` size-блок, `MaxSizeElement` | `maxWidth/maxHeight` clamp'яться лише коли явний `width/height` задано; інакше модифієр — no-op | semantyка «max» без backend'у; треба або clamp у manager, або прибрати з DSL |
| L7 | `compose/Widgets.kt:146-165` `GtkCenterBoxNode` | слот призначається **за порядковим лічильником** `attached` | `CenterBox { if (x) A; B }` → переплутані start/center/end |
| L8 | `compose/Widgets.kt:409` `Box` | `vertical GtkBox` = block flow, не Compose Box; «overlay» тільки через `Overlay` + хак з невидимим головним дитям (`Widgets.kt:123-141`) | рішення на кроці K7 (sumісність із docs/bar-api vs Compose-семантика) |
| L9 | `compose/Modifier.kt` `applyModifiers` | `hexpand/vexpand/halign/valign/margins` пишуться лише `if != null` → рекомпозиція **без** `fillWidth()` залишає попереднє значення (sticky state); те саме успадкує майбутній `weight` | у Android-семиці `Modifier`-ланцюг переобчислюється кожний прохід — властивість, якої немає в новому ланцюзі, мусить скинутись |
| L10 | `shell-app/.../ui/test.ui.kt:92` | `Box(Modifier.padding(16).radius(12).background(...).padding(16))` — один CSS-правил `padding`, другий переписує перший | зламаний приклад, який тиражують docs; фікс прикладу, не framework |
| L11 | `compose/Widgets.kt:123-141` `GtkOverlayNode` | невидимий 0×0 `GtkBox` як головна дитина, щоб оверлей-діти не входжали у measure | у GtkOverlay є рідний `gtk_overlay_set_measure_overlay()` / `clip-overlay` (4.0) — хач замінюється API |

## 3. Архітектура пропозиції

Єдиний **власний layout manager у C** (прецедент і обґрунтування «чому не Rust/gtk4-rs» уже є в коментарі `kn_animated_size_layout.c`: gtk4-rs sub-classing у Kotlin/Native-процесі псував GObject type state). Pipeline вже існує: `rust-core/build.rs` (cc-rs) → `rust-core/src/*.rs` (UniFFI, `#[uniffi::export]`, `u64` handle замість вказівника) → `dev.knshell.core.*` у framework.

```
rust-core/src/kn_box_layout.c
  KnBoxLayout      : GtkLayoutManager  (orientation, spacing, homogeneous, animated-size fields)
  KnBoxLayoutChild : GtkLayoutChild    (float weight)
  measure():  main: min = Σ min_i + spacing; nat = Σ natural_i; при for_size — HfW-прохід
              weight>0: cell_i = max(min_i, leftover × w_i / Σw)   ← Android Row/Column семантика
  allocate(): поділ зайвого за вагою, align через gtk_widget_size_allocate (обидві осі),
              нульова комірка лишається нульовою (L3/L4/L5)
  + gtk_widget_class_set_compute_expand() на контейнері: OR по дітях (щоб не втратити propagation)
```

Merging замість дублювання: `KnAnimatedSizeLayout` → тонкий набір полів у тому ж `KnBoxLayout` (вже існують `current/ready/fixed_size/fixed_cross/probe`), тож анімований розмір перестає бути окремою «острівною» реалізацією лейаута (L2–L5 зникають разом).

Kotlin-шар (API лишається Android-подібний):
- `Modifier.weight(f: Float, min: Int = 0, max: Int? = null)` → `WeightElement` — **завжди** проставляється в `applyModifiers` (0f = скидання, L9).
- `align(h/v)`: `Fill` ⇒ `halign=FILL` (+ expand **тільки** якщо явно `fillWidth()/hExpand()`), Center/End ⇒ менеджер сам дає комірку → `reconcileMainAxisAlign()` видаляється (L1).
- `Box`/`Stack`, `CenterBox` слоти (L7/L8), `Overlay` через рідні `measure-overlay`/`clip-overlay` (L11).

## 4. Кроки

- [x] **K1. Headless layout-проба / тести** — `framework/src/nativeTest/.../LayoutProbeTest.kt` (+ мінімальний C/UniFFI-хелпер для `gtk_widget_size_allocate` на unmapped widget). Покрити: weight 1:2, min-vs-natural при shrink, `size(0, 2)` divider, align на обох осях, expand-propagation у вкладених `Row`/`Column`, sticky-рекомпозиция. Це захист перед правкою C.
- [x] **K2. Точечні фікси чинного C-manager'а** (L2 vexpand-for-vertical, L3 min+natural+for_size, L4 `m < 1`, L5 packing) — окремим комітом, щоб потім було з чим порівнювати візуально.
- [x] **K3. `KnBoxLayout` + per-child `weight`** у `rust-core` (C + `box_layout.rs` UniFFI + `compute_expand`), прикріплення з `GtkBoxNode`. Рішення на цьому кроці: merge з animated-size чи тонкий шар поверх спільної child-моделі.
- [x] **K4. Видалення `reconcileMainAxisAlign()` + повний скид полів кожний прохід** в `applyModifiers` (L1, L9).
- [x] **K5. Max-size**: clamp у `KnBoxLayout` (рекомендовано, бо інакше `maxWidth()` лишається брехнею в DSL) — з явним записом у docs, що це емуляція; альтернатива — прибрати `maxWidth/maxHeight/maxSize` (L6).
- [x] **K6. CenterBox**: іменовані слоти замість лічильника (L7) + документація `shrink-center-last` (4.12).
- [x] **K7. `Box` vs `Stack`** (L8): збереження block flow + новий `Stack`, чи перепис на стекінг. Вибір з урахуванням, що `docs/bar-api.md`/`docs/shell-api.md` уже описують `Box` як «div».
- [x] **K8. Overlay без хаку**: рідні `measure_overlay`/`clip_overlay` (L11).
- [x] **K9. Docs**: `docs/framework-api.md` (модифієр→GTK таблиця), `docs/shell-api.md:256-303`, `docs/bar-api.md:117-172`, новий `docs/layout-model.md` — що GTK гарантує, що емулюємо, чого не може бути (max-size, % of parent).
- [x] **K10. Прибирання прикладів**: L10 у `test.ui.kt`; перевірка `ui/primitives/Image.kt:86` і `compose/ShaderCanvas.kt:145` (pin через `size_request` — тепер є чесний шлях).

## 5. Ризики

- `gtk_widget_set_layout_manager()` на вбудованому `GtkBox`: технічно дозволено, але сеттери `GtkBox` (`gtk_box_set_spacing`) пишуть у мертвий `GtkBoxLayout`. Отже або власний widget-клас з `gtk_widget_class_set_layout_manager_type()` (рекомендовано), або spacing **виключно** через manager.
- Subclassing `GtkLayoutManager` через gtk4-rs у цьому процесі раніше ламала GObject type state → manager пишемо в C.
- K2/K4 зміни packing і приберуть expand-хак → **візуально зруshaють** чинний UI (bar islands, badge в `Card`, `fillWidth()` у `test.ui.kt`) — тому K1 іде першим, а K2/K4 окремимизь kомітами з порівнянням.
- Debug-попередження GTK («Allocating size … without calling gtk_widget_measure()», «natural size must be >= min size», «stale request mode») мають стати частиною тестів K1, а не логів у користувача.
- Встановлений GTK 4.24.1: усі використані API (`measure_overlay`, `shrink-center-last` 4.12, `min-width` CSS) старші — сумісність ок; нових (`GtkBoxLayoutChild`-подібних) у GTK немає, тому вони наші.

## 6. K1 — результати (headless-проба live)

Файл: `framework/src/nativeTest/kotlin/dev/knshell/compose/LayoutProbeTest.kt` (18 тестів, запускається
через `./gradlew :framework:linuxX64Test`; фільтр: `--tests '*LayoutProbeTest*'`).

**Тести-факти (усі зелені) — на них спираються K2–K8:**

| Тест | Що задокументовано |
|---|---|
| `size request is a floor and never a ceiling` | `set_size_request` не має upper bound → причина K5 |
| `css min-width raises both the minimum and the natural size` | CSS `min-*` = єдиний важіль, `103 vs 63` на контейнері |
| `a stale size cache is only dropped by the next pass` | GTK кешує negotiate за `(orientation, for_size)`; CSS після measure не видно → всі стилі в тесті застосовуються ДО measure |
| `main-axis leftover goes only to the expanding children` | зайве дістається лише expand-дітям |
| `main-axis alignment positions the child inside the space it claims` | GtkBox **не розширює allocation** вирівняної дитини: `w=6, x=143`, комірку видно лише як проміжок до наступної дитини → L1 справді хач, а чесне рішення — у manager'а (K3) |
| `gtk measures container padding so padding must not be emulated with child margins` | padding рахується generic CSS → емулювати маржинами не треба |
| `expand propagates from children to a box` | GtkBox = OR по дітям → власний контейнер зобов'язаний мати свій `compute_expand` |
| `a label answers height-for-width only when it can wrap` | `for_size` працює лише з wrap → L3 |
| `CenterBox cannot express which side is squeezed first` | `shrink-center-last` **default TRUE**, обидва значення дають однакову алокацію, центр завжди `width − (start + end)` → K6 робить іменовані слоти, а не сподівається на цю властивість |
| `align only touches the axis it was asked about` / `a cross-axis size does not make the child expand` | чинна поведінка DSL, яку K4 не повинен зламати |

**Червоний набір = робочий список (7 тестів, у повідомленні вказано крок):**

- K4: `layout state does not survive a chain that dropped it` (L9), `main-axis alignment does not
  travel through the expand flag` (L1)
- K2: `shrinking container gives children their minimum not their natural size` (L3, `31 vs 89`),
  `zero sized child stays zero sized` (L4, `0 vs 1`), `animated container packs like a plain box`
  (L5, `x 0 vs 188`), `vertical animation distributes leftover by the vertical expand flag`
  (L2, `17 vs 200`), `content probe reports the natural size without the one-pixel floor`
  (L3/L4, `0.0 vs 2.0`)

Умовного skip у Kotlin/Native немає (раннер розуміє лише статичний `@Ignore`), тож тести залишаються
червоними й самі називають крок, який їх зеленить.

## 6.1 K4 — результати (2026-11-18, ноут)

_Suite_: **54 тести, 54 green** (`./gradlew :framework:linuxX64Test`). Обидва навмисно
червоні K4-тести зелені; жодного нового red.

- **L1 закрито**: `reconcileMainAxisAlign()` видалена разом із викликом; `align()` більше ніколи
  не чіпає `hexpand`/`vexpand`. Клітинку для Center/End дає `KnBoxLayout` (`main_pos`), що
  підтверджено группою 4 ще в K3.
- **L9 закрито чесно**: `applyModifiers` тепер відстежує, кому *належить* поле. Для кожного з
  `hexpand/vexpand/halign/valign/opacity` — якщо ланцюг написав поле, ставиться запис у GLib data
  віджета (`g_object_set_data`, ключ `kn-modifier-*`); на наступному проході, якщо ланцюг поле
  не згадав, поле повертається до дефолту GTK і запис знімається. Поле, якого ланцюг ніколи не
  писав, не чіпается взагалі — це й є захист прямих записів поза каналом модифієрів
  (`ui/primitives/Image.kt` `pinSize()` → `GTK_ALIGN_START`; `compose/Widgets.kt`
  `GtkOverlayNode.attachChild` → CENTER).
  Стан живе на самому віджеті (вмирає разом з ним), тому не потрібна ніяка реєстрація в `GtkNode`,
  і `applyModifiers` лишається придатним для виклику без вузла (саме так його викликає тест).
- **Виміряний дефолт GTK 4.24** (ctypes-проба на голому `GtkLabel`): `halign = valign =
  GTK_ALIGN_FILL`, `hexpand = vexpand = 0`, `opacity = 1.0`. Тобто таблиця handoff §2 («`valign`
  за замовчуванням `GTK_ALIGN_START`») була хибною — `valign` скидається у `FILL`, а не `START`.
- Два нові тести-захисти: `a dropped field is reset on its own axis only` (_ALIGNElement_ несе
  осі окремо → `align(v=…)` не чіпає `halign`) і `the reset never touches a field the modifier
  chain never wrote` (пін іконки й центрування overlay-дитини переживають прохід з іншим ланцюгом).
- Візуал: `KN_SHELL_MODE=windowed` — вікно малюється як на `master` (різниця скриншотів — лише
  анімований shader-фонд, 0.2% пікселів).


**Виведені обмеження тестового хarness'у (щоб наступні читачі не витратили на це час):**
- display потрібен: companion `init { check(gtk_init_check() != 0) }` — без нього тест-процес падває
  з core dump;
-_widget без ancestors and no window never computes an allocation_ → `rooted()` загортає в carrier-box;
- `alloc<IntVar>().value` не резолвиться у nativeTest — використовуй `allocArray<IntVar>(4)` + `+ n`;
- тестовий клас, що чіпає GTK без init, ламає весь run — не додавати «швидких» тестових класів без
  ініціалізації.

## 6.2 K5 — результати (2026-11-18, ноут)

_Suite_: **64 тести, 64 green** (`./gradlew :framework:linuxX64Test`). +10 тестів проти K4.

- **L6 закрито**: `maxWidth()/maxHeight()/maxSize()` більше не брехня в DSL. Стелю зберігає
  `KnBoxLayout` поосі в child'а (`KnChildLayout { weight; max_width; max_height; }`,
  `KN_NO_MAX = -1`), застосовує її в `kn_measure_child` (де GTK уже робить request мінімумом →
  стеля вгору не тягне) і в `allocate()` на головному й поперечному полі — тож cap перемагає
  `expand`, `homogeneous` і `weight`-частку. API: `setBoxChildMax(widget, child, max_width,
  max_height)` (rust-core/src/box_layout.rs) + `getBoxChildMaxWidth/Height` для тестів; запис
  чиститься, коли вага дефолтна й обидві стелі `-1` (правило L9: «absent = default», не лишає
  сліду).
- **Канал виштовхування — не лише для box'ів**: `GtkNode.pushChildLayout()` (було `pushWeight()`
  на `GtkBoxNode`) перенесено в базовий клас і викликається з базового `applyModifier()`. Дитина
  `Text`/`Image` у `Row` тепер сама передає свою вагу й стелю контейнеру; раніше вагу знав лише
  box-подібний вузол (латентний ґап, знайдений тут же).
- **Чесна межа емуляції (записана в docs і в тест):** стеля ніколи не перемагає *власний мінімум*
  дитини — як CSS, де `min-width` сильніший за `max-width`. Примусова алокація нижче мінімуму в
  GTK не обрізає віджет: `GtkLabel` малює весь рядок *поверх сусідів* (на пробовому скріншоті
  клітинки лізли одна на одну) і height-for-width-негаціація відповідає
  `Gtk-WARNING **: Trying to measure GtkLabel … for width of 203, but it needs at least 249`. Тому
  cap діє, коли дитина *може* віддати ширину (`Label(ellipsize = true)` / `wrap = true`, у яких
  мінімум = слово або «…»), і не діє на однорядковому label без ellipsize (мін = натюр). Для
  тексту з багаторядковим обмеженням і далі кращий `Label(maxWidthChars = N)` — він знижує сам
  мінімум.
- Нові тести (група 4): `a cap clamps a child below its own natural size`, `a cap never squeezes a
  child below its own minimum`, `a cap above the natural size changes nothing`, `a cap shrinks the
  natural size the container reports`, `a weighted share respects the cap`, `a cap on one axis does
  not touch the other`, `a cross-axis cap clamps the height of a child`, `a cross-axis cap stops at
  the height the child asked for`, `clearing a cap restores the natural size`, `a maxWidth on a
  child reaches the box that sizes it` (останній — DSL-шлях: `GtkBoxNode` + `Modifier.maxWidth(140)`
  на `GtkNode`-дитині).
- Візуал: тимчасовий probe-UI в `shell-app/main.kt` (три `Label` з cap 140/120 + `ellipsize`) —
  жодного `Gtk-WARNING`, текст обрезается «…», клітинки не лізуть одна на одну; після перевірки
  `main.kt` повернуто до стану commit (проба не потрапляє в історію).
- Документація: KDoc `MaxSizeElement` у `framework/.../Modifier.kt` (тепер «two places» + межа
  мінімуму + `min beats max`); `docs/shell-api.md` — рядок таблиці модифієрів і нотатка про
  triкацию тексту. `docs/framework-api.md` переліковий, змін не потребував.

## 6.3 K6 — результати (2026-11-18, ноут)

_Suite_: **71 тест, 71 green** (`./gradlew :framework:linuxX64Test`). +7 тестів проти K5.

- **L7 закрито**: `GtkCenterBoxNode` більше не має mutable-лічильника `attached`. Слот береться з
  вузла — `GtkCenterRegionNode.slot` (`enum class CenterSlot { Start, Center, End }`); для дитини,
  що не є регіоном, — з її позиції в `children`, не з кількості викликів `attachChild()`. Тому
  `GtkApplier.rebuildOrder()` (detach усього → attach усього; викликається на Compose `move`) не
  перевертає бар: раніше регіони мінялися сторонами саме там.
- **Регіон — справжній box** (`class GtkCenterRegionNode(slot) : GtkBoxNode(gtk_box_new(HORIZONTAL))`).
  Наслідок: у регіоні можна тримати кілька віджетів без обгортки `Row` —
  `start = { Clock(); Space(8); Workspaces() }`. DSL `CenterBox(...)` тепер компонує три
  `CenterRegion(slot) { … }` замість послідовних `start(); center(); end()`, тобто слот структурний:
  умовний контент в одному регіоні не зсуває інші.
- **Обмеження GTK, задокументоване й затестоване**: у `GtkCenterBox` рівно три слоти, по одному
  віджету. Четверта *звичайна* (не-регіон) дитина замінює END-віджет, а не зникає безслідно
  (`a CenterBox holds one widget per region`). З DSL цей випадок недосяжний: три регіони завжди
  створюються, а «більше трьох модулів» — це кілька дітей у регіоні.
- **`shrink-center-last` (4.12) — виміряно, не припущено**: дефолт TRUE означає, що центр тримає
  свою натюр-ширину, а вільне місце йде регіону, у якому є expand-дитина. Тест
  `a region grows only when its content expands`: `start` 19 px → 182 px з `hexpand`-дитиною,
  центр і інший бік не міняються. Тобто `Row(Modifier.fillWidth())` усередині `start` тягнеться як і
  до K6: `GtkBox` пропагує expand дітей, тож регіон передає запит далі в `GtkCenterBox`.
- **Мутаційна перевірка тестів** (тимчасово повернутий лічильник): падають рівно два тести
  ідентичності слотів — `a region lands in the slot it names whatever the attach order`,
  `a region keeps its slot after the applier rebuilds the child order` (70/71 з мутантом). Решта
  тестів регіонів кодують семантику GTK і від лічильника не залежать.
- Нові тести (група 3): `a region lands in the slot it names whatever the attach order`,
  `a region keeps its slot after the applier rebuilds the child order`,
  `a CenterBox holds one widget per region`, `one region holds several widgets without a wrapper`,
  `a plain child still goes by composition order` (сумісність із руками зібраним GTK-деревом),
  `a region is measured and allocated as one cell`, `a region grows only when its content expands`.
- Візуал: probe-UI в `shell-app/main.kt` — `CenterBox(Modifier.fillWidth().height(36))` з двома
  `Text` у `start` (без `Row`), центром-лейблом і `Row(Modifier.fillWidth())` в `end`. Лог без
  `Gtk-WARNING`/`CRITICAL`; скріншот: «1 2» ліворуч, «|centre|» по центру, «end-start end-finish»
  праворуч. `main.kt` повернуто до стану commit.
- Документація: `docs/shell-api.md` (абзац про регіони + структурний слот + `shrink-center-last`),
  `docs/bar-api.md` (те саме, коротко), KDoc `GtkCenterRegionNode`/`GtkCenterBoxNode`; старий
  коментар тесту `CenterBox cannot express which side is squeezed first` переписано в минулий час.

## 6.4 K7 — результати (2026-11-18, ноут)

_Рішення_: **варіант 1** — `Box` лишається block flow, а стекінг отримує окремий віджет `Stack`.

- Чому не переписувати `Box` на стекінг: його block-flow семантика вже описана в
  `docs/shell-api.md`, `docs/bar-api.md` і в KDoc, на ній побудовані `Spacer`/`Space`
  (`ui/primitives/Utilities.kt:29,35` — `Box(Modifier.size(width, height))`) і весь наявний
  UI-код бару. Зміна семантики мовчки змінила б розкладку кожного `Box` у проєктах користувача,
  не даючи натомість нічого, чого не дає новий `Stack`.
- `Stack` = Compose `Box`: **контейнер розміром з найбільшу дитину**, кожна дитина отримує всю
  клітинку. Реалізація — `GtkOverlay` з `gtk_overlay_set_measure_overlay(child, TRUE)` на кожній
  дитині; перша дитина стає main child (`gtk_overlay_set_child`), решта — overlay-список.
  Невидима 0×0 заглушка тут **не потрібна** — саме тому `Stack` існує окремо від `Overlay`.
- Позицію всередині клітинки задає `Modifier.align`: `Stack` сам нічого не вирівнює, а GTK за
  замовчуванням тягне дитину на обидві осі, тож дитина, яка має зберегти натюр-розмір, просить
  `Align.Start`/`Center`. Порядок малювання = порядок композиції (бейдж після іконки лягає зверху).
- Нюанс, задокументований у KDoc: `insertChild(0)` у **непорожній** стек додає дитину в
  overlay-список, а не витісняє main child — витіснення означало б `set_child(null)`, тобто
  unparent живого віджета (GTK тримає рівно одну головну дитину).
- Тести (4, група `3b. Stack`): `a stack is as large as its largest child` (обидва порядки),
  `every stack child is given the whole cell`, `a stack of one child is that child`,
  `removing the first child keeps the rest measured`. Мутаційна перевірка: без
  `measure_overlay` падають рівно 2 з них (`…largest child`, `removing the first child…`) — тобто
  тести справді тримають вимірювання, а не просто «компілюється».
- Візуал: probe в `shell-app/main.kt` — три `Stack` у `Row`: (а) `Text("24", size=32)` під
  `Text("12:00:00", size=10)`, (б) картка `size(48,48)` з бейджем `Text("3")` через
  `align(h = End, v = Start)`, (в) стек з однією дитиною. Бейдж намальовано поверх картки
  у правому верхньому куті, широкий текст не обрізано до вужчого, лог без
  `Gtk-WARNING`/`CRITICAL`; `main.kt` повернуто до стану commit.
  Скріншот: `/tmp/k7.png` (вертикальне розтягування карток — це `valign = FILL` від `Row`,
  не поведінка `Stack`).
- Документація: `docs/shell-api.md` (список layout-віджетів + абзац «`Box` — block flow, не
  Compose `Box`; стек — `Stack`/`Overlay`»), `docs/bar-api.md` (таблиця модулів),
  `docs/framework-api.md` (перелік віджетів), KDoc `Box`/`Stack`/`GtkStackNode`/`Overlay`.

## 6.5 K8 — результати (2026-11-18, ноут)

_Suite_: **79 тестів, 79 green**. Хак L11 закрито — і не тим способом, який припускав план.

- **Виправлення припущення плану**: `gtk_overlay_set_measure_overlay()` не усуває потребу в
  головній дитині, а `gtk_overlay_set_child()` **завжди** її міряє. Але головна дитина й не
  потрібна: якщо **всі** діти лежать в overlay-списку з `measure_overlay = FALSE`, `GtkOverlay`
  сам міряється 0×0. Тобто невидимий 0×0 `GtkBox` був не «вимогою API», а просто зайвим
  віджетом — і саме він робив так, що `Overlay` міг репортити expand через дитину, яку ніхто не
  компонував.
- Реалізація: `abstract class GtkOverlayBaseNode(measureChildren, centerChildren)` —
  `attachChild` кладе дитину в overlay-список, ставить `measure_overlay` за прапорцем і (для
  `Overlay`) центрує; `detachChild` = `gtk_overlay_remove_overlay`. `GtkOverlayNode` =
  `(measureChildren = false, centerChildren = true)`, `GtkStackNode` =
  `(measureChildren = true, centerChildren = false)`. Викликів `gtk_overlay_set_child` у коді
  більше немає взагалі — отже й нюансу «`insertChild(0)` у непорожній стек» більше не існує
  (K7-примітка про це знята).
- Нові тести (група `3c. Overlay`): `an overlay child does not grow the container` (0×0 при
  широкому лейблі), `an overlay child keeps its natural size inside the cell` (центрування з
  округленням угору — GTK ділить з ceil, 7px у 200px клітинці починається з 97, не 96),
  `removing an overlay child detaches it` (батько = null, інакше наступна дитина малюється поверх
  попередньої), `an overlay does not expand because of its child`.
- `clip_overlay` не знадобився: `clipOverflow()` уже мапиться на `gtk_widget_set_overflow(HIDDEN)`,
  який GtkOverlay поважає — тому рідної опції не додавали (менше API, той самий результат).
- Візуал: probe в `shell-app/main.kt` — 16px рожевий чип із `Overlay { Text("2", size = 10) }`
  усередині (чип лишився 16×16, лейбл не розтягнув його) поруч зі `Stack` із тим самим «2» і
  текстом «stack grows» (стек виріс під більшу дитину, обидві дитини в одній клітинці). Лог без
  `Gtk-WARNING`/`CRITICAL`; `main.kt` повернуто до стану commit. Скріншот: `/tmp/k8b.png`.
- Документація: `docs/shell-api.md` — абзац «Verified semantics» переписано (заглушка не потрібна,
  `Stack` — для контейнера розміром з найбільшу дитину); KDoc `Overlay`/`GtkOverlayNode`/`Stack`
  і `GtkOverlayBaseNode`.

## 6.6 K9 — результати (2026-11-18, ноут)

- Новий `docs/layout-model.md` — контракт розкладки: хто міряє (таблиця контейнер → менеджер: `Row`/`Column`/`Box` → `KnBoxLayout`, `CenterBox` → три внутрішні `GtkBox`, `Stack`/`Overlay` → `GtkOverlay`, animated → `KnAnimatedSizeLayout`, решта — сток GTK), чотири «ручки розміру» (`size` = мінімум, `minSize` = CSS-мінімум, `maxSize` = емуляція стелі з підлогою по `min`, `weight` = поділ залишку), ownership полів модифікатора (qdata-ключі `kn-modifier-*`, звідси `Image`/`Overlay`/`CenterRegion` зберігають своє), вибір контейнера, чесні обмеження (`weight` лише в `KnBoxLayout`; `maxSize` поза менеджером клампить лише явний `size()`; `align` без клітинки нічого не робить; відсотків від батька немає; без baseline/intrinsic-ratio/другого проходу; текст не reflow-иться на shrink) і розділ «Debugging» (`GTK_DEBUG=layout`, хелпери `LayoutProbeTest`).
- Лінк на новий документ із `docs/framework-api.md` (після таблиці `dev.knshell.compose`), `docs/bar-api.md` (перед animated-size) і `docs/shell-api.md` (у розділі «Text that does not affect layout»).
- `docs/shell-api.md` виправлено: застаріле «`GtkOverlay` measures only its main child» (неправда після K8) → «діти малюються поверх і не впливають на вимір»; відновлено зіпсоване виділення «where the text *should* size its container».
- Таблиця модифієрів у `docs/shell-api.md` уже містила K5-рядок `maxWidth/maxHeight/maxSize` (емуляція, підлога по `min`) — не дублювали.
- Тести після правок: `:framework:linuxX64Test` BUILD SUCCESSFUL, 79/79 (код не змінювався, лише docs).

## 6.7 K10 — результати (2026-11-18, ноут)

- **Pin переїхав у канал модифієрів.** `ui/primitives/Image.kt` більше не пише GTK-поля напряму:
  `pinSize()` видалено, натомість вузол тримає `userModifier` і на кожен `applyModifier` передає
  `pinned().then(modifier)`, де `pinned() = Modifier.size(w, h).align(h = Align.Start, v = Align.Start)`.
  `resize()` переapplicовує ланцюг перед `load()`, тому пін переживає зміну розміру.
  `compose/ShaderCanvas.kt` — так само (`size(canvasWidth, canvasHeight).then(userModifier)`).
  Пастка: поле треба оголошувати як `private var userModifier: Modifier = Modifier` — без явного
  типу компілятор виводить `Modifier.Companion` і присвоєння падає.
- **`size_request` став таким самим полем із власником**, як `hexpand/halign/…`: ланцюг із
  `size()` ставить `kn-modifier-size`, ланцюг без нього повертає `-1 × -1` і знімає ключ. Це те, що
  робить пін чесним: якщо автор прибрав `size()` — картинка повертається до натурального розміру,
  а якщо автор не чіпав розмір — пін лишається.
- Тести (група `3d. intrinsic pins`): `a size request does not survive a chain that dropped it`,
  `an image keeps its pin when a chain drops an override`, `a shader canvas keeps its own size when
  a chain drops a size`. Разом **82/82**.
- Мутаційна перевірка: вимкнене скидання `size_request` валить рівно один тест
  (`Expected <(-1, -1)>, actual <(120, 40)>`) — тобто нові тести справді тримають цю гілку.
- **L10 (`test.ui.kt`) на цьому клоні недосяжний**: файл живе у WIP-гілці користувача, у цьому
  дереві його немає. Крок лишається відкритим для того дерева.
- **Нова знахідка (поза K10, перевірено на `9817588` — тобто не регресія K10)**: `Image` у
  `Row` з'їдає весь залишок ряду — наступні діти притискаються до правого краю, а сама картинка
  малюється 28×28 біля лівого. Відтворюється і без K10-змін; з `Box(Modifier.size(28, 28))` замість
  `Image` ряд пакується правильно. Гіпотези (не підтверджені): `GtkPicture` віддає `compute_expand`
  незалежно від властивості `hexpand` (тому пін `ExpandElement(false, false)` нічого не змінив —
  його прибрано), або ж справа в `for_size` при першому вимірі без текстури. Кандидат у L12 для
  окремого кроку K11.
