# Спільний compose-контекст для бару і вікон

## Context

Мета шела: **верхній бар + окремі вікна/острівці** (`Shell.run { barWindow { … }; window(…) { … } }`),
і все це має жити в одному compose-контексті.

Механіка вже є: `framework/src/nativeMain/kotlin/dev/knshell/shell/ShellRuntime.kt:101-102` — один
`CoroutineScope` (`GLibDispatcher` + `GtkFrameClock`) і один `Recomposer`; кожне вікно отримує свій
`Composition(applier = GtkApplier(root), parent = recomposer)` (`ShellRuntime.kt:209`), тобто один кадр
рекомпозиції на бар + усі вікна. `ShellScope.barWindow` / `window` (`ShellScope.kt:144`, `:160`)
реєструють поверхні й повертають `WindowHandle` (`close()`/`resize()`/`setMargin()`).

Проблема не в рекомпозиції, а в тому, що **`Composition` — це межа**: `CompositionLocal` і `remember`
не перетинають її. Три конкретні граблі:

- **G1 — DI через CompositionLocal.** `LocalKoin` (`shell-app/src/linuxX64Main/kotlin/dev/knshell/app/di/AppServiceProvider.kt:21`)
  заповнюється лише `ProvideKoin` (`:38`), а той у `main.kt:35` обгортає одне дерево. Друге вікно в
  `Shell.run` → `injectDi()` (`shell-app/src/linuxX64Main/kotlin/dev/knshell/app/ui/helpers/injectDi.kt:8`)
  кидає `error("No Koin container provided …")`. Контейнер при цьому глобальний — локал лише
  звужує його до одного `Composition`.
- **G2 — вкладені композиції.** `Popover` монтує контент через
  `ShellRuntime.mountCompose` (`framework/src/nativeMain/kotlin/dev/knshell/shell/Popover.kt:285` →
  `ShellRuntime.kt:337`, `parent = recomposer`) — це новий корінь, локали батька не успадковуються.
  Отже будь-який popover-контент із `injectDi()` падає так само.
- **G3 — `remember` як сховище стану.** У `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/ui/test.ui.kt`
  стан лежить у `remember { mutableStateOf(…) }`: `tab` (`:66`), `volume` (`:217`), `wifi/bluetooth/focus/scale`
  (`:293`-`:296`). Це per-composition: щойно бар і вікно стають різними поверхнями, спільний стан
  (активна вкладка, розкритий острів, тумблери) розсипається на два незалежні.

Наслідок: зараз `shell-app` узагалі не на `Shell.run` — `main.kt:27` іде legacy-шляхом
`runComposeWindow` з одним `Composition` і одним `TestUi()`.

## Approach

Три фікси, кожен знімає свою граблю; framework-рантайм (один `Recomposer`) не чіпаємо.

1. **F1 — DI без CompositionLocal.** `injectDi()` читає `KoinPlatform.getKoin()` напряму;
   `LocalKoin`/`ProvideKoin` видаляються. Контейнер і так один на процес (`startKoin` у `initKoin()`),
   тому локал не додавав нічого, крім scope-обмеження. Знімає G1 і G2 одним рухом.
2. **F2 — контракт поверхні.** Задокументувати в `docs/shell-api.md` (розділ про вікна) і
   `docs/bar-api.md` (per-island surfaces): CompositionLocal/remember не течуть між поверхнями;
   що робити замість них. За потреби — тонка обгортка `KnSurface { … }` на app-рівні для
   локального контексту (наприклад, майбутні per-surface провайдери).
3. **F3 — process-scoped стан.** `ShellStore` (Koin `single`) зі snapshot-полями
   (`var tab by mutableStateOf(0)`): читання/запис із будь-якого `Composition` іде через один snapshot,
   обидві поверхні рекомпозяться. Правило: `remember` — тільки для суто локальної транзієнтності
   (`hover`, `powerHover`), усе, що бачить інша поверхня — у store. Прецедент уже є: тема
   (`ThemeService.tokens/mode` — `StateFlow`, `collectTheme`/`collectThemeMode`).
4. **F4 — демо на `Shell.run`** як доказ схеми: `initKoin()` → `Shell.run { barWindow { KnBar() };
   window(WindowSpec(…)) { Panel() } }`, де кнопка в барі перемикає вікно через store + `WindowHandle`,
   а зміна теми у вікні перефарбовує бар у тому ж кадрі.

## Files to modify

- `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/ui/helpers/injectDi.kt` — `KoinPlatform.getKoin()`.
- `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/di/AppServiceProvider.kt` — видалити `LocalKoin`/`ProvideKoin`.
- `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/di/Koin.kt` — `single { ShellStore() }`.
- `shell-app/src/linuxX64Main/kotlin/main.kt` — `Shell.run` замість `runComposeWindow`+`ProvideKoin`.
- `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/ui/test.ui.kt` — спільний стан у `ShellStore`.
- `docs/shell-api.md`, `docs/bar-api.md` — контракт «поверхня = своя Composition».

## Reuse

- `KoinPlatform.getKoin()` — уже викликається в `ProvideKoin` (`AppServiceProvider.kt:39`); просто
  переносимо точку доступу в `injectDi()`.
- `collectTheme()` / `collectThemeMode()` (`shell-app/src/linuxX64Main/kotlin/dev/knshell/app/ui/helpers/collectTheme.kt`)
  — зразок «сервіс → `collectAsState` → обидві поверхні»; G3 вирішується тим самим патерном.
- `WindowHandle.close()/resize()` (`ShellScope.kt:110-130`) — керування поверхнями зі стану замість
  `if (open) window(…)`.
- `ShellRuntime.mountCompose` (`ShellRuntime.kt:337`) — не змінюємо; достатньо, що DI більше не залежить від локалів.
- `Services.startAll()` у `Shell.run` (`framework/src/nativeMain/kotlin/dev/knshell/shell/Shell.kt:56`) —
  сервіси піднімає framework, дублювання з Koin-модулями лишається як є.

## Steps

- [x] F1: `injectDi()` → `KoinPlatform.getKoin().inject<T>()`; видалити `LocalKoin`/`ProvideKoin` та їх імпорти.
  (зроблено: `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/ui/helpers/injectDi.kt`, `AppServiceProvider.kt` видалено,
  `main.kt:33` без `ProvideKoin`)
- [x] F1: перевірити, що `initKoin()` у `main.kt` викликається до першої композиції — `initKoin()` стоїть перед `runComposeWindow`.
- [ ] F3: `ShellStore` (app/di) зі станом, який зараз у `remember` і потрібен двом поверхням.
- [ ] F3: `test.ui.kt` читає/пише `ShellStore`; `remember` лишається лише для hover/транзієнтів.
- [ ] F4: `main.kt` → `Shell.run`, бар + панель-вікно, перемикання панелі через `WindowHandle`.
- [ ] F4: popover із `injectDi()` у контенті — доказ, що G2 закрито.
- [ ] F2: документація контракту в `docs/shell-api.md` + `docs/bar-api.md`.

## Verification

- `./gradlew :shell-app:linkDebugExecutableLinuxX64` — збірка без помилок.
- Запуск на Hyprland (`./scripts/dev.sh` або бінарник): бар і вікно з'являються одночасно, `hyprctl layers`
  показує namespace бару.
- Спільний стан: перемикання режиму теми у вікні перефарбовує бар у тому ж кадрі; тумблер, змінений у
  вікні, видно в барі; popover відкривається без `No Koin container provided`.
- Закриття вікна не вбиває процес, поки живий бар; закриття останнього вікна виходить із GLib loop.

## Відкриті питання

- Обсяг: чи входить F4 (перевід демо на `Shell.run`) у цей план.
- Другий варіант F1: не прибирати `ProvideKoin`, а обгортати ним кожну поверхню.
- Стиль F3: `mutableStateOf` у холдері чи `StateFlow` + `collectAsState`.
- Чи додавати framework-hook для per-surface локальних провайдерів зараз.
