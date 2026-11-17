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

- [ ] **K1. Headless layout-проба / тести** — `framework/src/nativeTest/.../LayoutProbeTest.kt` (+ мінімальний C/UniFFI-хелпер для `gtk_widget_size_allocate` на unmapped widget). Покрити: weight 1:2, min-vs-natural при shrink, `size(0, 2)` divider, align на обох осях, expand-propagation у вкладених `Row`/`Column`, sticky-рекомпозиция. Це захист перед правкою C.
- [x] **K2. Точечні фікси чинного C-manager'а** (L2 vexpand-for-vertical, L3 min+natural+for_size, L4 `m < 1`, L5 packing) — окремим комітом, щоб потім було з чим порівнювати візуально.
- [x] **K3. `KnBoxLayout` + per-child `weight`** у `rust-core` (C + `box_layout.rs` UniFFI + `compute_expand`), прикріплення з `GtkBoxNode`. Рішення на цьому кроці: merge з animated-size чи тонкий шар поверх спільної child-моделі.
- [ ] **K4. Видалення `reconcileMainAxisAlign()` + повний скид полів кожний прохід** в `applyModifiers` (L1, L9).
- [ ] **K5. Max-size**: clamp у `KnBoxLayout` (рекомендовано, бо інакше `maxWidth()` лишається брехнею в DSL) — з явним записом у docs, що це емуляція; альтернатива — прибрати `maxWidth/maxHeight/maxSize` (L6).
- [ ] **K6. CenterBox**: іменовані слоти замість лічильника (L7) + документація `shrink-center-last` (4.12).
- [ ] **K7. `Box` vs `Stack`** (L8): збереження block flow + новий `Stack`, чи перепис на стекінг. Вибір з урахуванням, що `docs/bar-api.md`/`docs/shell-api.md` уже описують `Box` як «div».
- [ ] **K8. Overlay без хаку**: рідні `measure_overlay`/`clip_overlay` (L11).
- [ ] **K9. Docs**: `docs/framework-api.md` (модифієр→GTK таблиця), `docs/shell-api.md:256-303`, `docs/bar-api.md:117-172`, новий `docs/layout-model.md` — що GTK гарантує, що емулюємо, чого не може бути (max-size, % of parent).
- [ ] **K10. Прибирання прикладів**: L10 у `test.ui.kt`; перевірка `ui/primitives/Image.kt:86` і `compose/ShaderCanvas.kt:145` (pin через `size_request` — тепер є чесний шлях).

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

**Виведені обмеження тестового хarness'у (щоб наступні читачі не витратили на це час):**
- display потрібен: companion `init { check(gtk_init_check() != 0) }` — без нього тест-процес падває
  з core dump;
-_widget без ancestors and no window never computes an allocation_ → `rooted()` загортає в carrier-box;
- `alloc<IntVar>().value` не резолвиться у nativeTest — використовуй `allocArray<IntVar>(4)` + `+ n`;
- тестовий клас, що чіпає GTK без init, ламає весь run — не додавати «швидких» тестових класів без
  ініціалізації.
