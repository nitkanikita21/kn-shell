# Atlas — каталог компонентів (kn-shell)

Atlas — робочий каталог для перегляду й перевірки компонентів `framework`: кожна story показує
компонент з актуальними значеннями контролів, а матриця variants — усі комбінації осей одразу.
Аналог `@Preview` у Compose Android або Storybook.

Мокапи (лише дизайн, CSS-імітація, не GTK): `docs/storybook/mockups/`.
Дослідження і прийняті рішення: `RESEARCH.md`.

## Модулі

```
framework     GTK4-міст, Compose DSL, UI-віджети, інспектор            ← бібліотека
atlas/core    DSL stories, модель args, runner, UI каталогу, команди   ← залежить від framework
atlas/plugin  Gradle-плагін dev.knshell.atlas: compilation і таски      ← included build
atlas/mcp     Rust-демон atlas-mcp (MCP)                              ← Gradle-модуль :atlas:mcp (cargo)
headless-compositor  Wayland-композитор без екрана                    ← Gradle-модуль (cargo)
shell-app     compilation linuxX64Atlas: stories і точка входу        ← застосунок
```

- **`atlas:core`** (`atlas/core/`) — `group`/`story`/`variants`, типи args (`Arg`), `LiveArgs`,
  `CellArgs`, `AtlasHostService`, `resolveStory`, `parseStorySpec`, `atlasMain`, UI каталогу
  (`runner/ui`). Пакет `dev.knshell.atlas`.
- **Stories** живуть у `shell-app`, у compilation `linuxX64Atlas` (`demo/shell-app/src/linuxX64Atlas/kotlin/atlas/`).
  Compilation і таски додає плагін `dev.knshell.atlas`. Вона залежить від `:atlas:core`, тож звичайний `main` (шелл) stories не містить.
  Точка входу — `fun main(args: Array<String>)` у `atlas/main.kt`, вона викликає `atlasMain(...)`.
- Runner — не окремий модуль і не Rust-процес: Atlas — звичайний Kotlin/Native executable
  (`atlas`, debug), in-process. Rust-демон `atlas/mcp` тільки керує цим executable з боку MCP;
  він не рендерить і не є runner-ом.
- **`atlas/mcp`** — демон `atlas-mcp`: cargo-крейт і Gradle-модуль `:atlas:mcp`. Приватний дисплей
  він бере з крейта `headless-compositor` (модуль `:headless-compositor`). Деталі в розділі «MCP»
  нижче.

## Підключення атласу до модуля

Gradle-плагін `dev.knshell.atlas` (`atlas/plugin`) додає атлас до Kotlin Multiplatform модуля з
таргетом `linuxX64`:

```kotlin
// settings.gradle.kts
pluginManagement { includeBuild("atlas/plugin") }

// build.gradle.kts (кореневий): спільний classloader із Kotlin plugin
plugins { id("dev.knshell.atlas") apply false }

// build.gradle.kts модуля
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("dev.knshell.atlas")
}

atlas {                                  // обидва поля необовʼязкові
    entryPoint = "atlas.main"            // типово
    core = project(":atlas:core")        // типово, якщо такий проєкт є
}
```

Плагін створює:

- compilation `atlas` (код у `src/linuxX64Atlas/kotlin`), яка бачить internal-и `main`, тож stories не
  потрапляють у бінарник застосунку;
- debug-executable `atlas` і таски `linkAtlasDebugExecutableLinuxX64`, `runAtlasDebugExecutableLinuxX64`;
- таску `runAtlas`; `-Pstory=Group/Entry` відкриває потрібну story.

Власні linker-опції модуля задавайте для всіх executable
(`binaries.withType<Executable>().configureEach { … }`), тоді їх отримає й атлас.

Бібліотеки `framework` і `atlas:core` поки ніде не публікуються: поза цим репозиторієм їх треба
підключити самому (publishing відкладено).

## DSL

Story описується в групі. Аргументи оголошуються як делеговані властивості; їх значення
передаються в render як `v[arg]`.

```kotlin
package atlas

import dev.knshell.atlas.dsl.group
import dev.knshell.compose.Button
import dev.knshell.compose.Color
import dev.knshell.compose.Label
import dev.knshell.compose.Modifier
import dev.knshell.compose.background
import dev.knshell.compose.padding
import dev.knshell.compose.radius

enum class ButtonSize(val scale: Double) { Small(0.3), Medium(0.6), Large(1.0) }

val buttonStories = group("Button") {
    val text by string("Save")                 // Arg.Text
    val enabled by boolean(true)               // Arg.Flag
    val size by enum(ButtonSize.Medium)        // Arg.Choice<ButtonSize>
    val tone by choice(listOf("ok", "warn"), "ok")   // Arg.Choice<String>
    val radius by int(16, 0..32)               // Arg.IntNum, контроль Slider
    val alpha by float(1f, 0f..1f)             // Arg.FloatNum, контроль Slider

    // render за замовчуванням для всіх story і variants групи
    render { v ->
        Button(
            modifier = Modifier
                .background(if (v[enabled]) Color.hex(0x00FF00) else Color.hex(0xFF0000))
                .padding((32 * v[size].scale).toInt(), (16 * v[size].scale).toInt())
                .radius(v[radius]),
            onClick = {},
        ) {
            Label(text = "${v[text]} ${v[tone]}")
        }
    }

    story("Primary")                           // використовує render групи
    story("Icon") { v -> /* власний render */ }

    variants("Size X State") {                 // матриця
        axis(size)                             // Arg.Choice: усі options
        axis(enabled)                          // Arg.Flag: true, false
        exclude { it[size] == ButtonSize.Large && !it[enabled] }
    }
}
```

Методи `GroupBuilder`:

| Метод | Тип arg | Примітка |
| --- | --- | --- |
| `string(default)` | `Arg.Text` | текст у контролі `Entry` |
| `boolean(default)` | `Arg.Flag` | `Switch` |
| `choice(options, default)` | `Arg.Choice<E>` | `DropDown` |
| `enum(default)` | `Arg.Choice<E>` | те саме, опції — всі значення enum |
| `int(default, range: IntRange)` | `Arg.IntNum` | `Slider` з діапазоном |
| `float(default, range: ClosedFloatingPointRange<Float>)` | `Arg.FloatNum` | `Slider` з діапазоном |
| `render { v -> }` | — | render групи за замовчуванням |
| `docs("…")` | — | опис компонента; показується над панеллю args |
| `story(name, states?, stateTarget?, play?) { v -> }` | — | одна story; блок необов'язковий, якщо є render групи |
| `variants(name, render?, view?, states?, stateTarget?, play?) { … }` | — | матриця; `view` — вигляд, з яким вона відкривається |

`group(title, category = "Form")` ставить групу під заголовок категорії в сайдбарі. Категорії йдуть у
порядку першої згадки; назва story лишається `Group/Entry`.

### Стани і сценарії

`states` і `play` виконуються лише прогоном golden (див. «Візуальна перевірка»).

```kotlin
story(
    "Keyboard",
    states = listOf(InteractionState.Hover, InteractionState.Focus),  // кадри з примусовим станом
    play = Play {
        focus("target")
        press(Key.Down)
        expect("menu").role("menu").focused()
        snapshot("open")
        press(Key.Escape)
        expectAbsent("menu")
        expect("target").focused()
    },
)
```

- Цілі — `testTag`. Стани ставляться на віджет із tag `target` (або `stateTarget`).
- Дії: `focus`, `click`, `hover`, `setText`, `press(Key, shift, ctrl, alt, meta)`, `type(text)`,
  `settle()`, `snapshot(name, target?)` (без `target` кадр сцени, з ним кадр одного віджета, зокрема в popover-і).
  Кожна дія чекає, поки UI стабілізується.
- Перевірки: `expect(tag)` з `role`, `name`, `description`, `text`, `focused`, `enabled`, `selected`,
  `expanded`, `checked("true" | "false" | "mixed")`, `state(назва, значення)`; `expectAbsent(tag)`.
- `press` і `type` — справжні натискання через клавіатуру `headless-compositor`.

### Середовища (теми)

Каталог сам задає, чим обгорнути stories; атлас нічого не стилізує.

```kotlin
atlasMain(args, stories, title = "My kit", environments = listOf(
    AtlasEnvironment("Dark") { story -> MyTheme(dark) { story() } },
    AtlasEnvironment("Light") { story -> MyTheme(light) { story() } },
))
```

Якщо середовищ більше одного, у сайдбарі є перемикач (`environment:<назва>`), а golden знімаються
для кожного: `Dark/Group/Entry.png`, `Light/Group/Entry.png`.

Усередині `variants { }`:

- `axis(arg: Arg.Choice<E>)` — усі options; `axis(arg: Arg.Flag)` — `true`/`false`;
  `axis(arg, values: List<T>)` — явний список значень.
- `exclude { snapshot -> Boolean }` — викидає комбінації. Предикат отримує `ArgSnapshot`.

Порядок комбінацій: перша axis змінюється найповільніше.

Помилки (кидаються не пізніше завершення `group { }`):

- `IllegalStateException`: дубль назви arg; дубль назви story або variants у групі (без урахування
  регістру); axis, якого немає серед args групи; story або variants без render (ні свого, ні групи).
- `IllegalArgumentException`: default поза `range`; більше 500 комбінацій у матриці.

## UI

- **Сайдбар** — групи і їхні stories/variants; активний пункт підсвічено.
- **Превʼю** — story по центру.
- **Матриця** — для `variants`; угорі перемикач вигляду. Кожна комірка — render зі своєю комбінацією.
  - `MatrixView.Compose` (типовий): групи за першою axis, що згортаються, з назвою над кожним превʼю.
  - `MatrixView.Table`: перша axis — рядки, друга — колонки; лише для рівно двох axis, інакше
    `IllegalArgumentException` під час опису story. Прибрана через `exclude` комбінація лишає «—».
  - Типовий вигляд задається в story: `variants("Variant X Size", view = MatrixView.Table) { … }`.
    Вибір і згорнуті групи памʼятаються для кожної матриці до закриття атласу.
  - Значок у куті іконки матриці в сайдбарі показує поточний вигляд.
- **Панель args** праворуч — контрол для кожного arg: `Entry` для тексту, `Switch` для flag,
  `DropDown` для choice, `Slider` із значенням для int/float. У матриці панель показує лише
  не-axis args: вони діють на всі комірки, axis задає сама комбінація.
- **Reset** у панелі повертає всі args story до default.
- **Значення памʼятаються окремо для кожної story** до закриття атласу: перемикання між stories
  не скидає контроли. Значення живуть у `LiveArgs`, які `AtlasHostService` кешує за story.
- Старт: відкривається перша story; порожній каталог показує порожній стан.

### Ізоляція preview

Стилі атласу не доходять до story: кожна рендериться в контейнері `StyleReset` (`framework`), який
скидає успадковані колір і шрифт до стану порожнього вікна. Той самий контейнер використовує прогін
golden. Правила, що явно називають нащадків (`labelStyle`), успадкуванням не є, тож атлас не ставить
їх на предків preview.

## Запуск

```bash
./gradlew :demo:shell-app:runAtlas
```

Обрати story при старті:

```bash
# через Gradle
./gradlew :demo:shell-app:runAtlas -Pstory="Button/Primary"
# або те саме через змінну середовища
KN_ATLAS_STORY="Button/Primary" ./gradlew :demo:shell-app:runAtlas
# бінарник
demo/shell-app/build/bin/linuxX64/atlasDebugExecutable/atlas.kexe --story "Button/Size X State"
# або змінна середовища
KN_ATLAS_STORY="Button/Primary" demo/shell-app/build/bin/linuxX64/atlasDebugExecutable/atlas.kexe
```

- `--story Group/Entry` або `--story=Group/Entry` має пріоритет над `KN_ATLAS_STORY`.
- `Group/Entry` — без урахування регістру, поділ по першому `/`.
- Невідома назва — попередження зі списком доступних story і відкривається перша story.

### Інспектор: `--inspect`

```bash
atlas.kexe --inspect=/run/user/1000/atlas.sock
# або змінна середовища
KN_ATLAS_INSPECT=/run/user/1000/atlas.sock atlas.kexe
```

- Сервер стартує **лише** з `--inspect=<шлях>` (або `--inspect <шлях>`) чи `KN_ATLAS_INSPECT`.
  Без них сокета немає, і звичайний запуск атласу не змінюється.
- Сокет — unix-сокет з правами `0600`, обробляється в GTK main loop (інспектор торкається віджетів
  напряму, без потоків).
- Протокол — один JSON-об'єкт на рядок, одна відповідь на рядок у тому ж порядку:

```
→ {"id":1,"method":"tools/list"}
← {"id":1,"result":{"tools":[{"name","description","inputSchema"}]}}
→ {"id":2,"method":"tools/call","params":{"name":"screenshot","arguments":{"target":"preview"}}}
← {"id":2,"result":{"content":[{"type":"image","data":"<base64>","mimeType":"image/png"}],"isError":false}}
← {"id":3,"error":{"message":"..."}}        # невідомий method
```

  Форма `result` повторює MCP, тому демон передає її без перетворень. Помилка інструмента (невідомий
  інструмент, поганий аргумент, неоднозначний `target`) повертається як `result` з `isError:true`, а
  не як `error`.

## Візуальна перевірка (golden)

Кожна story і кожна комбінація матриці рендериться без екрана й порівнюється зі збереженою картинкою
(еталоном) піксель у піксель, без допуску.

```bash
./gradlew :demo:shell-app:atlasGoldenRecord      # зняти або оновити еталони
./gradlew :demo:shell-app:atlasGoldenCheck       # порівняти; вихід ≠ 0, якщо щось відрізняється
```

| Властивість | Дія |
|---|---|
| `-Pstory=Group/Entry` | лише одна story (або комірка `Group/Entry/caption`) |
| `-Pgoldens=<тека>` | інша тека еталонів; типово `<модуль>/atlas-goldens` або `atlas { goldenDir = … }` |

**Що знімається.** Тільки story, на сталому тлі, з типовими args. Перший кадр — у початковому стані;
далі по кадру на кожен стан із `states` (`Entry@hover.png`) і на кожен `snapshot` сценарію
(`Entry@open.png`). Значення з панелі в еталон не потрапляють. Кадр обрізається по story, тому вміст
відкритого popover-а в кадр сцени не входить: його знімають через `snapshot(name, target)`. Нескінченна анімація не
дасть двох однакових кадрів і буде помилкою.

**Сценарій упав** (`▷` у вікні атласу, `Entry@play` у звіті) — перевірка `play` не пройшла; повідомлення
називає крок. Така перевірка не проходить, а запис нічого не пише.

**Результат.** `Button/Toolbar.png`, `Button/Variant X Size/Primary · Small.png`, поруч
`environment.txt`. Різниця складається в `<модуль>/build/atlas-golden/`: `*.expected.png`,
`*.actual.png`, `*.diff.png` і `report.json`.

- Перевірка не проходить, якщо картинка змінилась, еталона немає, story впала або еталон не
  відповідає жодній story. Зайві еталони лише називаються, не видаляються.
- Запис пишеться, тільки якщо всі story відрендерились, і переписує лише змінені PNG.
- Якщо story ламає композицію, прогін на ній закінчується: далі нічого не рендериться.

### Середовище еталонів

Прогін завжди йде під `headless-compositor` з програмним рендером (`GSK_RENDERER=cairo`, відеокарта
не бере участі), масштабом 1 і без налаштувань робочого столу. Список змінних — у
`atlas/plugin/.../AtlasGolden.kt`; плагін записує його поруч з executable
(`atlas-golden.properties`).

**Шрифти належать проєкту**, атлас не постачає жодних:

| Налаштування | Які шрифти бачить прогін | Між машинами |
|---|---|---|
| нічого | системні | еталони можуть відрізнятися |
| `atlas { goldenFonts = file("fonts") }` або `atlas.goldenFonts=<тека>` у `gradle.properties` | тільки ця тека | збігаються |

У цьому репозиторії це `golden-fonts/`. Еталони кіта лежать у `demo/shell-ui-kit/atlas-goldens/`, а `shell-app` тримає свої в кореневій `atlas-goldens/` (`atlas { goldenDir = … }`). **Тека має містити всі шрифти, які використовують stories.**
Story без явного шрифту в preview показує системний шрифт, а в еталоні — той, що є в теці.

`environment.txt` зберігає версії GTK, Pango, cairo, рендерер і відбиток шрифтів. Якщо вони інші,
прогін каже про це першим рядком, запис однієї story відхиляється, а в UI можна лише «Прийняти все».

### У вікні атласу

Кнопка «Перевірити» в сайдбарі запускає той самий прогін окремим процесом (вікно саме не знімає: у
нього інший рендерер). Змінені story позначені `●`, нові `+`, з помилкою `✕`; фільтр «лише змінені».
Вкладка «Різниця» показує еталон, нову картинку й різницю (режими «Поруч», «Різниця», «Перемикати»).
«Прийняти» копіює вже зняту картинку в еталон. Звіт від іншої збірки атласу вважається застарілим і
не приймається.

## Тести

```bash
./gradlew :atlas:core:linuxX64Test
./gradlew :atlas:core:linuxX64GraphicsTest                # прогін golden у справжньому вікні
./gradlew :framework:linuxX64Test
./gradlew :atlas:mcp:check :headless-compositor:check     # cargo test обох крейтів
cargo clippy --manifest-path atlas/mcp/Cargo.toml
```

`GoldenRunnerGraphicsTest` перевіряє стани, клавіші, popover і `play`. Решта тестів `atlas:core`
покриває логіку без GTK-вікна: побудову груп і матриці
(`GroupBuilderTest`), `LiveArgs` (`LiveArgsTest`), `AtlasHostService` (збереження args за story),
`resolveStory` і `parseStorySpec` (`StorySelectionTest`).

`cargo test` для `atlas/mcp` перевіряє демон на фейковому атласі (`examples/fake_atlas.rs`): проксі
`tools/call`, помилки, таймаут, перебудову, застарілість бінарника, зупинку та конфлікт імен. Запускай
його через `cargo test`, бо тест знаходить приклад у каталозі збірки. Композитор можна підняти окремо:
`cargo run --manifest-path headless-compositor/Cargo.toml -- <імʼя-сокета>`.

## MCP: керування атласом з Claude

`atlas/mcp` — демон `atlas-mcp` (Rust, офіційний SDK `rmcp`, транспорт stdio). Він дає Claude
інструменти, якими можна невидимо відкрити story, змінити args, зробити скріншот, прочитати дерево
віджетів і виконати дію (клік, текст, перемикач, слайдер, dropdown, примусовий hover/pressed/focus).
Типово вікно атласу не з'являється на робочому столі й не забирає фокус.

### Архітектура

```
Claude ──MCP/stdio──> atlas-mcp (Rust) ──JSON-рядки / unix socket──> atlas.kexe --inspect=<socket>
                        │                                              │
                        ├─ пошук атласу, reload / status               └─ framework: дерево, дії, PNG
                        └─ приватний headless Wayland-композитор
                           (headless-compositor/)
```

- Демон сам **не знає про компоненти**. Інструменти атласу описані в атласі (`tools/list`), демон
  їх проксіює; власні інструменти демона — `list_atlases`, `use_atlas`, `set_visible`,
  `reload`, `status`, `logs`, `stop`.
- Атлас запускається з `WAYLAND_DISPLAY=atlas-mcp-<pid>` і `GDK_BACKEND=wayland`. Так він рендериться
  на приватний сокет демона, а не на твій Hyprland-дисплей.

### Чому приватний headless-композитор

- **Невидимість.** Атлас підключається лише до приватного сокета `WAYLAND_DISPLAY`, тож на твоєму
  робочому столі вікна немає і фокус не змінюється. Що `hyprctl clients` його не бачить, — перевіряється
  в наскрізному тесті, а не в коді.
- **Без системних залежностей.** Не потрібні KWin, weston, cage, broadway, браузер чи sudo. Композитор
  — невеликий Rust-код у `headless-compositor/src/lib.rs`, без рендерингу: він дає handshake, `configure` і
  потік `wl_callback.done`, без яких GTK 4 перестає перекомпонувати.
- **Клавіатура без миші.** Композитор дає віртуальну клавіатуру (`HEADLESS_COMPOSITOR_KEYS`), миші
  немає. Кліки та інші дії виконує інспектор (`dev.knshell.inspect.Inspector`): він викликає ті самі
  GTK-сигнали, що й користувач, тож `Modifier.onClick`, `onValueChange` і `onCheckedChange`
  спрацьовують.
- Скріншоти малює сам клієнт (GTK-рендерер атласу), композитор нічого не малює.

### Встановлення і підключення

Один раз на машину:

```bash
cargo install --path atlas/mcp                       # → ~/.cargo/bin/atlas-mcp
claude mcp add --scope user atlas -- atlas-mcp       # для всіх проєктів
```

Після змін у `atlas/mcp` або `headless-compositor` повторіть `cargo install`
(те саме робить `./gradlew :atlas:mcp:cargoInstall`). Тулза нічого не знає про конкретний проєкт, тож у
репозиторії для неї немає ні конфігу, ні `.mcp.json`.

### Як тулза знаходить атлас

- **Корінь** — найближча тека від робочої директорії вгору, де є `settings.gradle(.kts)`; або `--root`.
- **Атласи** — демон запускає Gradle проєкту з власним init-скриптом (`-I`, вшитий у бінарник), який
  перелічує модулі з плагіном `dev.knshell.atlas` і таскою `runAtlas…ExecutableLinux…` та віддає
  link-таску, шлях до executable, робочу директорію й теки джерел Kotlin-модулів. Executable `atlas`,
  описаний вручну без плагіна, не враховується.
- **Кеш** — відповідь зберігається в `$XDG_CACHE_HOME/atlas-mcp/`, доки не зміниться якийсь build-скрипт.
- **Проєкти без атласу** — якщо жоден build-файл не згадує `dev.knshell.atlas`, Gradle на старті не
  запускається;
  доступні лише власні інструменти демона (`list_atlases` спитає Gradle примусово).
- **Кілька атласів** — треба обрати модуль через `use_atlas`.

### Інструменти демона

| Інструмент | Аргументи | Що робить |
| --- | --- | --- |
| `set_visible` | `visible` | Показує вікно атласу на робочому столі або знову ховає (типово сховане). Атлас перезапускається, а story, вигляд матриці й args відновлюються в новому. Закрите вікно повертає невидимий режим. |
| `list_atlases` | — | Заново питає Gradle і перелічує модулі з атласом; активний позначено `*`. |
| `use_atlas` | `module` (Gradle path, напр. `:app`) | Перемикає на атлас іншого модуля. |
| `reload` | — | Link-таска активного атласу, потім перезапуск. Story, вигляд матриці й значення args відновлюються в новому атласі. При помилці компіляції відповідь містить рядки `e:`, а старий атлас лишається запущеним. Після успіху прилітає `tools/list_changed`. |
| `status` | — | Корінь проєкту, знайдені атласи, модуль, pid, шляхи, остання помилка запуску і попередження про застарілість. |
| `logs` | `lines?` (ціле ≥ 1, типово 50) | Останні рядки stdout і stderr атласу. |
| `stop` | — | Зупиняє атлас; наступний виклик запустить його знову. |

Якщо атлас має інструмент з такою самою назвою, як у демона, демон його ховає (лог-рядок у stderr).

### Видимий режим

Типово атлас невидимий. `set_visible {visible: true}` перезапускає його у звичайному вікні поточної
графічної сесії, щоб людина бачила те саме, чим керує Claude. Памʼятайте:

- вікно отримує фокус, і твої натискання клавіш та кліки змінюють той самий стан, що й інструменти;
- якщо закрити вікно, наступний виклик запустить атлас невидимим і повідомить про це;
- скріншоти працюють і тоді, коли вікно на прихованому workspace (перевірено на Hyprland 0.57).

### Інструменти атласу

Список збирає атлас і віддає демон; назви та аргументи:

| Інструмент | Аргументи | Примітка |
| --- | --- | --- |
| `list_stories` | — | Усі story у форматі `Group/Entry`. |
| `select_story` | `story: "Group/Entry"` | Відкриває story; args кешуються за story. |
| `get_args` | — | Поточні значення args story. |
| `set_args` | `values: {name: value}` | Усе або нічого: тип, enum за назвою і діапазон перевіряються до застосування. |
| `reset_args` | — | Повертає args story до default. |
| `set_view` | `view`: `compose` \| `table` | Вигляд вибраної матриці; `list_stories` показує поточний і доступні. |
| `set_environment` | `name` | Середовище preview (тема каталогу); `list_stories` показує поточне. |
| `focus` | `target` | Клавіатурний фокус на віджет або першу фокусовну дитину. |
| `press` | `key`, `shift?`, `ctrl?`, `alt?`, `meta?` | Натискання клавіші; лише в невидимому атласі. |
| `type` | `text` | Набір тексту (літери, цифри, пробіл, `- . , /`) у віджет у фокусі; лише в невидимому атласі. |
| `tree` | `target?`, `depth?` | Дерево віджетів (id, тип, testTag, bounds, CSS-класи, текст, роль, назва, ARIA-стани, фокус) від кореня або від `target`. |
| `screenshot` | `target?` | PNG вікна або прямокутника ноди (з фоном сусідів); без `target` — корінь композиції. |
| `click` | `target` | Кнопка або `Modifier.onClick`. |
| `set_text` | `target`, `text` | `Entry`; викликає `onValueChange`. |
| `set_checked` | `target`, `checked` | `Switch`; викликає `onCheckedChange`. |
| `set_value` | `target`, `value` | `Slider`; значення поза діапазоном відхиляється. |
| `select` | `target`, `index` | `DropDown`; індекс поза опціями відхиляється. |
| `set_state` | `target`, `state: "hover" \| "pressed" \| "focus"`, `on` | Примусовий стан GTK; див. обмеження. |

Дії, що змінюють UI, чекають, поки атлас стабілізується (перекомпозиція і layout), перш ніж
відповісти; так само `screenshot` і `tree`.

### Цілі (`target`)

- **Число** — id ноди з `tree`.
- **Рядок** — testTag, його ставить `Modifier.testTag("name")` (див. `docs/framework-api.md`).
  Якщо tag відсутній або його мають кілька нод, відповідь `isError` з поясненням: використай id.

Вбудовані testTag атласу:

| testTag | Що це |
| --- | --- |
| `sidebar` | Сайдбар каталогу |
| `story:<Group>/<Entry>` | Пункт сайдбару конкретної story |
| `preview` | Превʼю поточної story |
| `cell:<підпис>` | Комірка матриці з таким підписом |
| `args-panel` | Панель args |
| `arg:<name>` | Контрол конкретного arg |
| `reset` | Кнопка Reset |
| `view:compose`, `view:table` | Кнопки перемикача вигляду матриці |
| `group:<значення>` | Заголовок групи в Compose-вигляді |

### Запуск і застарілість

- **Автостарт.** Атлас стартує, коли Claude Code запитує список інструментів (під час підключення), або
  при першому виклику інструмента. Сам демон бінарник не збирає: якщо його ще немає, викличте `reload`.
  Готовим атлас вважається, коли сокет відповідає на `tools/list`; на це є 20 с. Якщо процес помер до
  цього, відповідь містить хвіст логу.
- **Смерть атласу.** Наступний виклик запускає його знову (повідомлення в stderr демона).
- **Таймаут.** Один запит триває не довше 60 с. Якщо атлас завис, його вбивають, і наступний виклик
  стартує новий.
- **Застарілість.** Якщо файл у `src` якогось модуля чи build-скрипт новіший за max(mtime бінарника,
  початок останнього `reload`), до відповіді атласу додається попередження `call reload to rebuild`.
  Воно є і в `status`.
- **Вихід.** На EOF stdin, SIGTERM чи SIGINT демон убиває атлас, зупиняє композитор, прибирає сокет
  і лог. Атлас отримує `PR_SET_PDEATHSIG` від демона, тож не лишиться висіти, якщо демон упав.

### Обмеження

- **Popup-и і popover-и — окремі поверхні.** Вміст `Popup` і `HoverPopover` є в `tree`, а скріншот
  його ноди малює саму поверхню popover-а; у скріншот вікна він не входить. Відкритий список
  `DropDown` — внутрішній віджет GTK, його опції видно лише в полі `options`.
- **`set_state` не викликає `Modifier.onHover`.** Він лише виставляє GTK state flags. Hover-callback-и
  йдуть з GTK motion controller, а його не чіпає.
- **Без CSS-переходів.** Під інспектором GTK-переходи вимкнені, щоб знімок показував кінцевий стан;
  Compose-анімації не зачіпаються.
- **GPU.** Композитор оголошує `linux-dmabuf` і перший render node (`/dev/dri/renderD128+`), тож GTK
  рендерить на GPU тим самим шляхом, що й на робочому столі (перевірено: `GskVulkanRenderer` на NVIDIA).
  Без render node клієнт переходить на програмний рендер через `wl_shm`. Інші драйвери та машини з
  кількома GPU не перевірялись; шейдери окремо теж.
- **Миші немає.** Клавіатура є лише в невидимому атласі (`press`, `type`); hover і pressed ставляться
  примусово (`set_state`).
- **MCP-скріншоти не порівнюються:** вони для перегляду. Порівняння пікселів — у розділі
  «Візуальна перевірка»; MCP-інструмента для його звіту немає. Контейнера й CI для нього теж немає.

## Відкладено

Це рішення для майбутніх етапів, у коді їх немає.

- **Rust-runner і KN-бібліотеки.** Ідея: `shell-kn` / `atlas-kn` як KN-бібліотеки з `@CName`,
  тонкий Rust-runner з CLI, watch і relink. Причина відкладання: поточний Kotlin/Native
  executable достатній; hot reload = перезапуск процесу, і цього досить.
- **Пошук** по stories у сайдбарі.
- **Kotlin-сніпет** під контролами, що оновлюється разом із значеннями.
- **Pan/zoom** канвас (як у `mockups/overview.html`) і групування варіантів.
- **KSP** для генерації args.
- **Node-тести** на основі stories (дамп дерева GTK для кожної комбінації).

Зберігаємо з дизайну: Atlas ізольований від ui kit і його тем, stories не тягнуть сервіси
споживача, а `core` не імпортує `services/`.

## Обмеження

- **Немає reflection** в Kotlin/Native: args оголошуються вручну в DSL.
- **Дрейф контролів:** `int`/`float`/`enum` можуть розійтися з сигнатурою компонента. Компілятор
  ловить більшість розбіжностей, бо story викликає компонент напряму.
- **Компоненти мають експонувати параметри** (`enabled`, `size`, `radius`), інакше контролювати
  нічого. Наприклад, `Button(text, modifier, onClick)` не має `enabled`/`size`, тому stories
  задають стиль через `Modifier`.
- **Hot reload = перезапуск процесу.** Зміна коду вимагає повторного link Kotlin/Native.
