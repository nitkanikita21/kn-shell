# Задача: Compose-власна анімація content-size + vsync frame clock

**Статус:** реалізовано (підхід «Modifier» обрано одразу, без фази `AnimatedBox`)
**Оцінка:** 3.5–5 людино-днів (4 фази)
**Модулі:** `framework`, `rust-core`, `shell-app`

> **Рішення (реалізація):** замість компонента `AnimatedBox`/`AnimatedWidth`/`AnimatedHeight`
> зроблено `Modifier.animatedWidth` / `animatedHeight` / `animatedSize(axis, …)` на контейнері
> (`Row`/`Column`). Compose володіє твінов (spec, retarget, interruption), C-лейаут-менеджер
> `kn_animated_size_layout.c` — «тупий» (лише застосовує float і віддає натуральний розмір).
> `GtkAnimatedBoxNode` як `ComposeNode` видалено; стан твіну живе у `AnimatedSizeAnimator`,
> ключований за вказівником віджета. Див. `docs/framework-api.md` → Animated size.

## 1. Мотивація

Зараз «батько анімовано розширюється, коли в дітей змінилась натуральна ширина»
працює, але анімацію виконує C-код усередині `GtkLayoutManager`, а не Compose.
Це ламає Compose-ідеологію верстки: Compose не володіє анімованим значенням, не може
його перервати/перетаргетити, задати spring, скоординувати з іншими анімаціями або
протестувати. Додатково Compose-годинник — це `g_timeout_add(16)` (62.5 Гц, не vsync),
тоді як C-анімація тікає від GTK frame clock (vsync). Тобто зараз два незалежні годинники,
і плавність C-анімацій на 144 Гц краща, ніж будь-якої Compose-анімації.

**Мета:** зробити так, щоб:
1. значення розміру належало Compose (spec, стан, переривання, координація);
2. C-менеджер лише **застосовував** готове float-значення і віддавав натуральний розмір контенту;
3. був **один** годинник — GTK frame clock, з лінивими тіками (нуль пробуджень у простої);
4. публічний API лишався Compose-DSL, без жодного CSS у руках користувача.

## 2. Поточний стан (факти)

| Що | Де | Проблема |
|---|---|---|
| `AnimatedBox`/`AnimatedWidth`/`AnimatedHeight` + C `GtkLayoutManager` | `framework/src/nativeMain/kotlin/dev/knshell/compose/AnimatedSize.kt`, `rust-core/src/kn_animated_size_layout.c` | анімація в C: `duration_ms`, `easing`, `from/target`, `g_get_monotonic_time`, власний `gtk_widget_add_tick_callback`; spec вшитий при створенні, не змінюється |
| `widthBehavior()/heightBehavior()/sizeBehavior()` + `AutoSizeAnimator` | `framework/src/nativeMain/kotlin/dev/knshell/compose/Behavior.kt` | твін у Kotlin, але через хак `gtk_widget_set_size_request` і пробу 10 Гц; int-only; може осцилювати |
| `SizeAnimator` для явних розмірів | той самий файл | третій механізм для тієї ж задачі |
| `GLibFrameClock` | `framework/src/nativeMain/kotlin/dev/knshell/compose/ComposeWindow.kt` | `g_timeout_add(16)` замість GTK frame clock; `Snapshot.sendApplyNotifications()` опитується щотакту; вічні пробудження 62.5/с |
| Використання | `PlayerChip.kt`, `CenterIsland.kt`, `Clock.kt`, `Network.kt`, `modules/Workspaces.kt` | після міграції поведінка має лишитися ідентичною |

## 3. Цілі / не-цілі

**Goals**
- G1. Compose володіє анімованим значенням розміру (tween/spring/snap, retarget, interrupt).
- G2. «Натуральний розмір дітей змінився → контейнер анімовано розширюється/звужується» — першокласна фіча DSL.
- G3. Один годинник = GTK frame clock; тіки ліниві (є очікувачі/анімації → є тіки).
- G4. Публічний API — тільки Compose/Kotlin; CSS лишається виключно внутрішнім рендерером статичних стилів.
- G5. Ніяких регресій у плавності: візуально таймінги мають збігатися з поточною C-версією ±1 кадр.

**Non-goals (окремі задачі)**
- N1. Compose-власний float-лейаут (`MeasurePolicy` + Pango-метрики) — ця задача лише готує ґрунт.
- N2. Анімація кольорів/фону без CSS (`transitionMs`, `Modifier.transition`) — окрема задача; у цій лишаються як є (див. Q2).
- N3. Rust-лейаут-движок (taffy).
- N4. Будь-які зміни візуального вигляду віджетів.

## 4. Архітектура

Поділ відповідальності:

| Питання | Відповідальний |
|---|---|
| Який **натуральний** розмір контенту? | GTK: `gtk_widget_measure(child, MAIN, -1)` по дітях (C helper) |
| Яке **поточне** (анімоване) значення? | Compose: `AnimationSpec` + `SizeTween` + GTK frame clock |
| Алоцируй рівно стільки | C `GtkLayoutManager`: «тупий», `current`, `fixed`, `spacing`, `axis` |
| Коли тікати? | GTK frame clock (`GdkFrameClock`) → Compose `withFrameNanos` |

**Інваріант стабільності:** target для content-driven режиму — натуральний розмір при
`for_size = -1`, тобто він **не залежить** від поточного анімованого значення. Тому немає
feedback loop і осциляцій; діти аллоцюються в натуральному розмірі, контейнер клипає
(`GTK_OVERFLOW_HIDDEN`) — контент «відкривається», а не переформатовується щокадру.

### 4.1 Фаза 1 — Frame clock на GTK frame clock

Новий файл `framework/src/nativeMain/kotlin/dev/knshell/compose/GtkFrameClock.kt`
(або переписати `GLibFrameClock` у `ComposeWindow.kt`).

- `MonotonicFrameClock` реалізація, що тримає список waiters (broadcast, як зараз).
- Час кадру: `gdk_frame_clock_get_frame_time(clock)` (мікросекунди) → наносекунди.
  Джерело — `GdkFrameClock*`, який приходить у тік-колбек; fallback — `TimeSource.Monotonic`.
- Тік: `gtk_widget_add_tick_callback(rootWidget, cb, ref, destroy)` на кореневий віджет вікна.
  - **Ліниво**: колбек додається, коли з'являється перший waiter або перший анімований вузол;
    знімається (`gtk_widget_remove_tick_callback`), коли їх не лишилось. У простої — нуль тіків.
  - Один екземпляр на вікно (multi-monitor бар → кілька вікон, кожне зі своїм віджетом).
  - На кожному тіку: `Snapshot.sendApplyNotifications()` → розбудити waiters → `SizeAnimationRegistry.tick(nanos)`
    → якщо після цього є робота — лишити колбек, інакше зняти.
- `Snapshot.sendApplyNotifications()` більше не опитується таймером. Замість цього:
  `Snapshot.registerApplyObserver { _, _ -> scheduleNotify() }`, де `scheduleNotify()` ставить
  **один** дедуплікований `g_idle_add`, який викликає `sendApplyNotifications()`.
  Обов'язково перевірити, що записи стану з Rust-колбеків (інші потоки) досі доходять до UI.
- `dt` між кадрами — клампити 1..50 мс (як у `Spring.kt`); після unmap/remap — resync (не
  застосовувати гігантський dt).
- Видалити `g_timeout_add(16)`. `GLibDispatcher` не чіпати.

### 4.2 Фаза 2 — «Тупий» C layout manager

`rust-core/src/kn_animated_size_layout.c`:

**Прибрати:** `duration_ms`, `easing`, `from`, `target`, `animating`, `start_us`, `kn_ease`,
`kn_update`, `kn_tick`, `kn_tick_destroy`, `gtk_widget_add_tick_callback`.

**Лишити:** `current` (double), `fixed_size`, `fixed_cross`, `spacing`, `axis`, `ready` (bool),
`probe`-стабілізацію першого кадру (див. нижче), `gtk_widget_set_overflow(HIDDEN)`,
allocate (діти в натуральному розмірі, кліп).

**Семантика `measure()`:** головна вісь → `ready ? lround(current) : lround(content())`;
поперечна → `fixed_cross >= 0 ? fixed_cross : max(children cross)`.
Тобто до першого `set_current` контейнер поводиться як звичайний контентний, без стрибка.

**Нові C-функції (extern "C"):**
- `void kn_animated_size_layout_set_current(GtkWidget *w, double current)` — основна;
  ставить `current`, `ready = TRUE`, `gtk_widget_queue_resize(w)`; пропускати
  `queue_resize`, якщо `|new - old| < 0.01`.
- `double kn_animated_size_layout_content(GtkWidget *w)` — натуральний розмір по головній
  осі (сума натуральних розмірів дітей + `spacing`); використовується для проби з Kotlin.
- `gboolean kn_animated_size_layout_is_ready(GtkWidget *w)` — (опційно, для діагностики).

`kn_animated_size_layout_set_fixed()` лишається (явний target: `>= 0` px, `-1` — content-driven);
`set_cross()` лишається без змін; `kn_attach_animated_size_layout()` більше не приймає
`duration_ms`/`easing` (лишити `spacing`, `axis` та сумісність сигнатури — за потреби новий
`kn_attach_animated_size_layout2`).

> Увага: `set_fixed()` зараз скидає `have_probe` — після рефакторингу переконатися, що
> перемикання content ↔ explicit не призводить до стрибка (перехід має бути через анімацію
> з поточного значення).

### 4.3 Фаза 2 — Rust FFI

`rust-core/src/animated_layout.rs` — експорти (UniFFI, `u64` = `GtkWidget*`, main thread only):

```rust
#[uniffi::export]
pub fn set_animated_size_current(widget: u64, current: f64);

#[uniffi::export]
pub fn animated_size_content(widget: u64) -> f64;   // 0.0 якщо не той тип
```

Плюс зберегти `attach_animated_size_layout` / `set_animated_size_fixed` / `set_animated_size_cross`.
У debug — assert, що виклик на GTK main thread (`g_main_context_is_owner(g_main_context_default())`),
інакше попередження в лог.

### 4.4 Фаза 2 — Kotlin-движок

Нові файли в `framework/src/nativeMain/kotlin/dev/knshell/animation/`:

- `AnimationSpec.kt`
  ```kotlin
  sealed interface AnimationSpec
  data class Tween(val durationMs: Int = 180, val easing: Easing = Easing.OutCubic) : AnimationSpec
  data class Spring(val stiffness: Float = 500f, val dampingRatio: Float = 0.7f,
                    val visibilityThreshold: Float = 0.5f) : AnimationSpec
  object Snap : AnimationSpec
  ```
- `SizeTween.kt` — node-level аніматор (без `mutableStateOf`, без рекомпозиції):
  `current: Double`, `velocity: Double`, `target: Double`, `lastNanos: Long`;
  `fun retarget(t: Double, spec: AnimationSpec)`; `fun advance(nowNanos: Long): Boolean` —
  рахує нове значення (tween від `current` до `target`; spring зі збереженням velocity),
  повертає «чи є робота». Перше кадрування після створення — `snap` до target.
- `SizeAnimationRegistry` — множина активних вузлів; `tick(nanos)` ітерує, для кожного:
  проба контенту (див. нижче) → retarget → advance → `setAnimatedSizeCurrent`.
  Реєстр повідомляє frame clock, що потрібні кадри (лінива підписка, §4.1).

`framework/.../compose/AnimatedSize.kt` — переписати `GtkAnimatedBoxNode`:

- Поля: `spec: AnimationSpec`, `axis`, `spacing`, `mainSize: Int?`, `crossSize: Int?`,
  `tween: SizeTween`, `dirty: Boolean`, `initFrames: Int`.
- `attachAnimatedSizeLayout(widget, spacing, axis)`; `mainSize/crossSize` → `setAnimatedSizeFixed/Cross`.
- `update {}`: `spec` і `mainSize` можуть змінюватися на кожній композиції → retarget з поточного
  значення (не «вшивати» spec при створенні, як зараз).
- `attachChild/detachChild/moveChild` → `dirty = true`.
- Проба контенту: `animatedSizeContent(widget)`.
  - `dirty == true` → проба обов'язково; скинути `dirty`.
  - інакше — проба раз на кадр (GTK кешує `gtk_widget_measure`, дешево) або раз на 2–3 кадри;
    заміряти обидва варіанти і лишити дешевший без втрати реакції на зміну тексту.
  - під час `!ready` — ініціалізація: чекати, поки `widget` mapped і натуральний розмір
    стабільний 2 кадри поспіль; потім `current = target`, `set_current` → готово.
- `getPreferredSize()`/`Modifier.size()` не чіпати: явні розміри йдуть через `fixed`, як зараз.

Новий файл `framework/.../compose/Modifier.kt` (або `Sizing.kt`) — публічний API (Фаза 3):

```kotlin
// primary
AnimatedBox(vertical, spacing, spec = Tween(180, OutCubic), axis, mainSize, crossSize, modifier) { }
AnimatedWidth(spacing, spec) { }        // = AnimatedBox(vertical = false)
AnimatedHeight(spacing, spec) { }       // = AnimatedBox(vertical = true)

// зручність
Modifier.animateContentSize(spec)        // stretch: sugar для Box/Row/Column-вузлів
Modifier.animateSize(targetPx: Int, spec) // явний target
AnimatedContentSize(axis, spec) { }       // аліас контейнера, якщо хочемо Compose-неймінг
```

Семантика (задокументувати в KDoc):
- content-driven target = натуральний розмір; explicit `mainSize` override;
- **дефолт — монотонний `Tween`**, бо overshoot контейнера = тимчасове обрізання контенту;
  `Spring` — опт-ін, для листків/індикаторів;
- retarget посеред анімації — від поточного значення, без стрибка;
- nested animated-контейнери рухаються до власних натуральних цілей; координація — спільним
  `spec` (або існуючим `Animate {}` для спільного прогресу);
- клип, а не reflow; округлення float→int — лише в allocation.

### 4.5 Фаза 3 — міграція і видалення старого

- `Behavior.kt`: видалити `AutoSizeAnimator`, `SizeAnimator`, `SizeBehaviorRegistry`,
  `BehaviorElement` і `applyModifiers`-гілку, що їх обслуговує. Лишити deprecated-аліаси:
  ```kotlin
  @Deprecated("Use AnimatedWidth/AnimatedHeight", ReplaceWith("AnimatedWidth(...)"))
  fun Modifier.widthBehavior(...) // => не робити нічого, лише попередження? 
  ```
  Рішення: аліаси або прибрати зовсім і оновити call-sites (`Workspaces.kt`), або лишити
  як `@Deprecated` мапінг на новий API — обрати на ревʼю (рекомендація: прибрати, бо
  Modifier не може підмінити власника лейауту; замінити call-site на `AnimatedWidth`).
- `ComposeWindow.kt`: прибрати `g_timeout_add`-годинник і `SizeBehaviorRegistry.tick`.
- Оновити `docs/framework-api.md` (розділ про анімації/розміри) і README (deviation #2:
  тепер у фреймворку власний animation-движок на Compose Runtime + GTK frame clock).
- Перевірити всі call-sites: `PlayerChip.kt`, `CenterIsland.kt`, `Clock.kt`, `Network.kt`,
  `modules/Workspaces.kt`.

## 5. Критерії приймання

**Збірка**
- `./gradlew :shell-app:linkDebugExecutableLinuxX64` зелений; `./scripts/dev.sh` запускається;
  `rust-core` збирається без нових попереджень (`-Wall` для C).
- `./gradlew build` (debug+release+тести) зелений.

**Паритет поведінки (ручні сценарії)**
1. `PlayerChip`: іконка ↔ еквалайзер — ширина анімується в обидва боки, без стрибка.
2. `Clock`/`Network`: зміна тексту → ширина анімується; оновлення цифр не дає тремтіння.
3. `CenterIsland`: розгортання/згортання вниз — як зараз.
4. `Workspaces`: після заміни `widthBehavior()` результат візуально ідентичний.
5. Швидке перемикання 5 разів/с: без снапів, фінальний розмір точний.
6. Вставка/видалення дитини під час анімації: новий target, без осциляцій.
7. Перший показ вікна/popover: без стрибка на першому кадрі.

**Плавність (вимірювано)**
- `GDK_DEBUG=frames` під час анімації: інтервал тіків ≈ період монітора (16.7 мс @60 Гц,
  6.9 мс @144 Гц), std < 1 мс, максимальний розрив ≤ 2 періоди.
- Серія скріншотів під час анімації (`grim` у циклі) → дельти ширини монотонні (для `OutCubic`),
  без плато > 1 кадру.
- Візуальний таймінг збігається з поточною C-версією ±1 кадр (порівняти записані серії).

**Ефективність**
- Idle: нуль 16-мс пробуджень (перевірити `voluntary_ctxt_switches` за 10 с у статиці або
  `perf trace`): очікувано падіння з ~62.5/с до ~0.
- Під час анімації: CPU ≤ поточної C-версії +10 %.
- Лічильник рекомпозицій під час анімації == 0 (додати debug-лічильник у `Recomposer`-обсервері).

**Пам'ять**
- 30 хв безперервних тоглів: дрейф RSS < 5 МБ.

## 6. План по фазах

| Фаза | Зміст | Файли | Оцінка |
|---|---|---|---|
| 1 | Frame clock на GTK tick callback + apply-observer замість опитування | `framework/.../compose/ComposeWindow.kt`, новий `GtkFrameClock.kt` | 1–1.5 дня |
| 2 | «Тупий» C-менеджер + FFI + Kotlin `AnimationSpec`/`SizeTween`/реєстр + `AnimatedBox` | `rust-core/src/kn_animated_size_layout.c`, `rust-core/src/animated_layout.rs`, `framework/.../compose/AnimatedSize.kt`, нові `animation/AnimationSpec.kt`, `animation/SizeTween.kt`, `animation/SizeAnimationRegistry.kt` | 1.5–2 дні |
| 3 | Міграція call-sites, видалення `Behavior.kt`-механізмів, docs | `framework/.../compose/Behavior.kt`, `shell-app/...` (5 файлів), `docs/framework-api.md`, `README.md` | 0.5–1 день |
| 4 | Бенчмарки/візуальна перевірка, 144 Гц, документація семантики | скрипт `scripts/anim-bench.sh` (новий), docs | 1 день |

**Разом: 3.5–5 днів.** Фази 1 і 2 можна робити паралельно (різні файли), але merge — 1 → 2,
бо тіки реєстру залежать від нового годинника.

## 7. Ризики та мітигація

| Ризик | Мітигація |
|---|---|
| Реентрантність `gtk_widget_measure` з тіку | викликати пробу лише поза layout-пасом (у тік-колбеку), не в `allocate`; тест «вставити дитину під час анімації» |
| Тіки не приходять, коли вікно невидиме → анімація «зависає» | на map — resync: `snap` до target або кламп dt; задокументувати |
| Втрата `sendApplyNotifications` після відмови від таймера | apply-observer + дедуплікований idle; тест: оновлення гучності/медіа з Rust-потоку в простої |
| Два годинники під час перехідного періоду | у фазі 3 видалити `g_timeout_add` і `SizeBehaviorRegistry` повністю; тимчасово — feature-flag у build |
| Kotlin/Native `StableRef` для tick `user_data` | тримати ref до зняття тік-колбека; знімати на destroy вікна; тест закриття/відкриття popover |
| Spring overshoot у контейнері → обрізання | дефолт `Tween`; `Spring` лише листки; задокументувати |
| Nested animated-контейнери «сперечаються» | target завжди натуральний (`for_size=-1`), кліп; тест вкладеності 2 рівні |
| Дробове масштабування / RTL | округлення лише в allocation; тест на scale=1.5 (якщо доступно) і RTL-тексті |
| ABI/сумісність C↔Rust | `f64` через UniFFI, `u64` — хендл; assert main thread; smoke-тест з `Debug` |

## 8. Поза скоупом (наступні задачі)

- Compose-власний float-лейаут: `MeasurePolicy`, `Constraints`, Pango-метрики тексту
  (прибирає залежність від GTK-вимірювання і дає anchored/weighted лейаут).
- Анімація кольорів/фону без CSS: квантовані CSS-класи (Compose обирає крок) або
  cairo-рендер для гарячих декорацій; видалення `transitionMs`/`Modifier.transition`.
- Rust/tuffy-лейаут-движок, Qt/QML-порівняння.

## 9. Відкриті питання (рішення на ревʼю)

- **Q1.** `Modifier.animateContentSize(spec)` — робити одразу чи лише контейнер
  `AnimatedBox`/`AnimatedWidth`? *Рекомендація: контейнер зараз (фаза 2), Modifier — stretch
  у фазі 3, бо Modifier має підміняти layout manager у `update {}` і тягнути `spacing`.*
- **Q2.** `Modifier.transition(ms)` / `transitionMs` (CSS-анімації кольорів) — лишити як є
  до окремої задачі? *Рекомендація: так, але додати `@Deprecated` у KDoc із посиланням на
  задачу N2.*
- **Q3.** Чи підтримувати явний `mainSize` (Behavior on explicit width) одразу?
  *Рекомендація: так — це ~20 рядків, закриває `Behavior on explicit width`.*
- **Q4.** Перейменувати `AnimatedBox` → `AnimateContentSize`? *Рекомендація: лишити
  `AnimatedBox`, додати аліас `AnimatedContentSize`.*
