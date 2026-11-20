# Швидка dev-ітерація (Tier 1 + Tier 2) для kn-shell

## Context

Dev-цикл зараз — `scripts/dev.sh --watch`: poll `find -newer` раз на секунду → `gradle :shell-app:linkDebugExecutableLinuxX64` → kill + повний restart процесу. Користувач: болить **тривалість** (edit→результат ≈ 20-60 с), обрано **Tier 1 (прискорити restart) + Tier 2 (UI-reload без restart)**.

## Ключова фізика (важливо прийняти до реалізації)

- Kotlin/Native не має eval/hot-patch (на відміну від JVM HotSwap). dlopen формально доступний, але для нашого reload непридатний (деталі в «Фізика: чому не dlopen-модулі»): код плагіна не може поділитися runtime/composition з хостом. Практичний висновок: code-reload = перевидати + перезапустити (або змінити модель виконання коду — Zipline/DSL)."

- Отже Tier 2 у K/N = **restart-lite**: процес перезавантажується швидко і без втрат — стани переживають перезапуск, важкі речі (Rust-сервіси) не деградують.
- Нічого живого в UI-процесі після exec не залишається (новий process image). Тиск на дві фази: **build-time** (link) і **restart-time** (init/сервіси/мерехтіння).

## Фізика: чому не dlopen-модулі (уточнення до «Ключової фізики»)

K/N вміє `-produce dynamic`/.so і хост теоретично може `dlopen` (platform.posix) — алеreload коду це не рятує:
1. Кожен K/N-бінар самодостатній: власна копія runtime+stdlib+транзитивних залежностей; «shared/thin runtime» — незакрита фіча (issue #2183, обговорення «thin frameworks» досі живі). У нашому випадку і хост, і ui-плагістатично лінкують `framework` (Compose Runtime) → дві копії глобального стану (`ShellRuntime.instance()`, mutableStateOf-коректність), Kotlin-об'єкти через C-ABI не передаються.
2. Reload вимагав би `dlclose`+`dlopen` на кожен цикл — неспецифіковано безпечно для runtime з GC/потоками, плюс уже запущені composition/замикання посилаються на старий код.
3. Реальні інструменти «Kotlin hot reload» — не для чистого K/N: Compose Hot Reload — лише JVM-таргет (IDE-плагін, вимагає jvmTarget); Touchlab — власний Kotlin-інтерпретатор (iOS-фокус); Zipline (Cash App) — єдиний робочий шлях для K/N-хоста: guest-код = Kotlin/JS у QuickJS, справжній миттєвий reload. Це і є Tier 3 з планом.

## Факти з коду

- `scripts/dev.sh` — уже оптимізований (incremental, без `rm -rf build`, TMPDIR у репо); НІЯКОГО таймінгу кроків не веде — перший крок плану саме вимірювання.
- `framework/src/nativeMain/kotlin/dev/knshell/shell/ShellRuntime.kt` — один GTK init / GLib loop / `Recomposer` (`:92-:143`); кожне вікно — своя `Composition(applier = GtkApplier(root), parent = recomposer)` (`:209`); `closeWindow` з `composition.dispose()` (`:287`); loop зупиняється коли `windows.isEmpty()` (`:293`) — тобто «graceful quit» уже існує як бічний ефект.
- `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/ipc/UnixIpcServer.kt` — повністю робочий Unix-socket IPC на K/N (`onCommand: (String) -> Unit`, Dispatchers.IO). **Ніде не інстанційований** (мертвий код) — ідеальна база для dev-сокета.
- `ThemeService.reloadTheme()` — прецедент reload даних; polyfill стану існує.
- `rust-core` сервіси живуть у процесі UI: MPRIS через дитину `playerctl --follow`, audio через `pactl`- polling, Hyprland IPC socket. Restart вбиває дитину `playerctl` → media-чіпи «мертві» на кілька секунд після кожного restart.
- `main.kt` — весь UI: `runComposeWindow { ProvideKoin { AppRoot() } }`.
- `gradle.properties`: `kotlin.incremental.native=true`, `konanDaemon=enabled`, configuration cache — все увімкнено; значить 20-60 с — це переважно K/N compile+link самого shell-app (треба підтвердити вимірюванням).

## Підхід

Рішення користувача: **Phase B (daemon) — опційно, за результатами вимірювання**. Порядок: A (виміряти) → C (restart-lite без daemon, завжди корисний) → A2 (прискорити build за цифрами) → B — тільки якщо фаза старту/сервісів домінує.

### Phase A — виміряти (без цього решта — навмання)
1. У `dev.sh` логувати тривалості фаз: `build` (gradle total), `stop_app`, `start_app→перший кадр` (перший лог `[shell-app] hello world` + окремий лог «перше composited-вікно»).
2. Розділити build на `compileDebugExecutableLinuxX64` vs `linkDebugExecutableLinuxX64` (`--console=plain` + `time`, або gradle scan), щоб побачити частину K/N-лінку.
3. Записати baseline у `docs/dev-loop.md` (таблиця: фаза, секунди) — це артефакт фази й база для «до/після».

### Phase C — restart-lite: dev-IPC + graceful restart + snapshot (ядро Tier 2 без daemon)
- Під'єднати мертвий `UnixIpcServer` у `main.kt` за умови `KN_DEV=1`: сокет `$XDG_RUNTIME_DIR/kn-shell-dev.sock`, команди:
  - `status` — pid, build-mark (хеш exe / timestamp лінку) — dev.sh використовує для healthy-check і щоб не робити restart, якщо exe не змінився;
  - `restart` — graceful: `ShellRuntime.closeAllForRestart()` (dispose compositions → destroy windows → природний шлях `windows.isEmpty()` → quit, `ShellRuntime.kt:278-293`) → новий exec самого себе; dev.sh чекає новий `status` з новим build-mark замість сліпого SIGTERM+spawn;
  - `snapshot` — серіалізація process-scoped стану (Koin-singletons: вкладки TestUi, scaling, theme overrides) у `$XDG_STATE_HOME/kn-shell/dev-snapshot.json`; новий процес при `KN_DEV=1` відновлює перед першою composition.
- Hoisting стану: `remember`/`mutableStateOf` у `TestUi`/палеті, які хочемо бачити живими, — у Koin-singleton-холдери.
- Що це дає без daemon: чистий shutdown (без orphan-дитини `playerctl`/залилих сокетів), відновлення стану, детермінований dev-цикл, таймінг у логу. Чого НЕ дає: MPRIS-стрім і Wayland-surface усе одно перестворюються (кілька секунд «немає програвача», remap-мерехтіння бару) — це ціна відсутності daemon.

### Phase A2 — швидкіші build (за цифрами фази A)
- Якщо домінує link: дослідити прапорці K/N debug-лінку (рівень debug-info, `kotlin.native.binary.*`), переконатись, що `kotlin.incremental.native` реально спрацьовує (порівняти edited-single-file link vs touch-noop link).
- Розділити watcher-шляхи: `.kt`-лише зміни не чіпають cargo (inputs уже коректні) — перевірити, що cargo задачі UP-TO-DATE у циклі.
- Якщо домінує старт (і B не обрано): lazy-ініціалізація сервісів у `ShellRuntime`/Koin-модулях, паралельний старт.

### Phase B — опційно: Rust-сервізи у daemon (za рішенням після A)
Тільки якщо вимірювання показало, що старт/сервіси — домінанта, або коли болітиме remap-мерехтіння:
- Новий bin-таргет у `rust-core` (`kn-shell-daemon`) + Unix-socket RPC; Kotlin-клієнт замість in-process UniFFI, API `Services` незмінний.
- MPRIS (`playerctl --follow`), PipeWire-підписка, Hyprland-івенти переживають UI-restart повністю; exec-self у Phase C автоматично стає «миттєвим reload».

### Phase D — документація
- README «Developer workflow»: новий цикл, таймінги до/після, K/N-обмеження (чому це «restart-lite», а не справжній hot reload).

## Files to modify

| Файл | Зміна |
|---|---|
| `scripts/dev.sh` | таймінги фаз, restart через dev-сокет, fallback |
| `rust-core/` (Cargo.toml + src/bin) | (опційно, фаза B) daemon binary + Unix-socket RPC |
| `framework/.../services/*.kt` | (опційно, фаза B) IPC-клієнт замість in-process UniFFI (API `Services` незмінний) |
| `framework/.../shell/ShellRuntime.kt` | `closeAllForRestart()` (reeusing dispose-path `:278-293`) |
| `shell-app/.../main.kt` + `ipc/UnixIpcServer.kt` | dev-сокет за `KN_DEV=1`, команди status/restart/snapshot |
| `shell-app/.../ui/test.ui.kt` (+ді-модуль) | hoisting dev-стану у process-scoped holder |
| `docs/dev-loop.md` (новий) + README | baseline-таблиця та опис |

## Reuse

- `UnixIpcServer` (K/N AF_UNIX сервер уже написаний, просто не під'єднаний).
- `ShellRuntime.closeWindow`/dispose-шлях і `windows.isEmpty()`→quit (`:278-:293`) — база graceful restart.
- `GLibDispatcher`/`GtkFrameClock` — маршувати IPC-команди на GTK-потік.
- `ThemeService.reloadTheme()` + kotlinx.serialization (вже в deps) — snapshot.
- Gobley/UniFFI-контракт `rust-core` — RPC-шар daemon будується навколо наявних сервісів без переписування логіки.

## Steps

- [ ] A1: таймінг фаз у dev.sh (build / stop / start→перший кадр) + розділення compile vs link
- [ ] A2: baseline-таблиця у `docs/dev-loop.md`; висновок що домінує (build / старт / мерехтіння)
- [ ] C1: dev-сокет у shell-app за `KN_DEV=1` (status/restart/snapshot, база — `UnixIpcServer`)
- [ ] C2: `ShellRuntime.closeAllForRestart()` + graceful exec-self
- [ ] C3: snapshot/restore dev-стану + hoisting стану TestUi у process-scoped holder
- [ ] C4: dev.sh — restart через сокет (healthy-check, build-mark), fallback на поточний шлях
- [ ] A3: build-швидкість за цифрами (K/N debug-лінк прапорці / lazy-старт сервісів)
- [ ] (опційно, за рішенням після A2) B: rust-core daemon + IPC-клієнт Services
- [ ] D: README + docs/dev-loop.md (можливості/обмеження, цифри до/після)

## Verification

- `scripts/dev.sh --watch`: edit `.kt` → лог показує фази (build Ns / restart Ms); edit→результат < 20 с (ціль після A2/B: орієнтир — половина від baseline).
- `pgrep -a playerctl` — дитина daemon, PID сталий через UI-restart; `playerctl metadata` без вікна «немає програвача» під час restart.
- После `restart` через сокет: новий PID UI, старий PID daemon; layer-shell бар перемапився без «чорного вікна» на весь екран; вкладка/стан TestUi відновився із snapshot.
- Ctrl+C у dev.sh — без orphan-процесів (daemon лишається свідомо, документація пояснює як зупинити).
- 10 reload-циклів поспіль: RSS shell-app і daemon не росте > ~10% (немає витоків composition).

## Відкладено (свідомо)

- Tier 3 (interpreted UI-шар / JS-рантайм) — миттєвий reload ціною іншої моделі програмування; окреме рішення.
- Повне усунення remap-флікера — обмеження Wayland surface lifecycle.
