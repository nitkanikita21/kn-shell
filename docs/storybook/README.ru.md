# Atlas — каталог компонентов (kn-shell)

Atlas — рабочий каталог для просмотра и проверки компонентов `framework`: каждая story показывает
компонент с актуальными значениями контролов, а матрица variants — все комбинации осей сразу.
Аналог `@Preview` в Compose Android или Storybook.

Мокапы (только дизайн, CSS-имитация, не GTK): `docs/storybook/mockups/`.
Исследования и принятые решения: `RESEARCH.md`.

## Модули

```
framework     GTK4-мост, Compose DSL, UI-виджеты, инспектор            ← библиотека
atlas/core    DSL stories, модель args, runner, UI каталога, команды   ← зависит от framework
atlas/plugin  Gradle-плагин dev.knshell.atlas: compilation и таски      ← included build
atlas/mcp     Rust-демон atlas-mcp (MCP) и headless-композитор        ← cargo, не Gradle
shell-app     compilation linuxX64Atlas: stories и точка входа        ← приложение
```

- **`atlas:core`** (`atlas/core/`) — `group`/`story`/`variants`, типы args (`Arg`), `LiveArgs`,
  `CellArgs`, `AtlasHostService`, `resolveStory`, `parseStorySpec`, `atlasMain`, UI каталога
  (`runner/ui`). Пакет `dev.knshell.atlas`.
- **Stories** живут в `shell-app`, в compilation `linuxX64Atlas` (`shell-app/src/linuxX64Atlas/kotlin/atlas/`).
  Compilation и таски добавляет плагин `dev.knshell.atlas`. Она зависит от `:atlas:core`, поэтому обычный `main` (шелл) stories не содержит.
  Точка входа — `fun main(args: Array<String>)` в `atlas/main.kt`, она вызывает `atlasMain(...)`.
- Runner — не отдельный модуль и не Rust-процесс: Atlas — обычный Kotlin/Native executable
  (`atlas`, debug), in-process. Rust-демон `atlas/mcp` только управляет этим executable со стороны
  MCP; он не рендерит и не является runner-ом.
- **`atlas/mcp`** — демон `atlas-mcp` и приватный headless-композитор. Отдельный cargo-крейт, в
  `settings.gradle.kts` его нет. Подробности в разделе «MCP» ниже.

## Подключение атласа к модулю

Gradle-плагин `dev.knshell.atlas` (`atlas/plugin`) добавляет атлас в Kotlin Multiplatform модуль с
таргетом `linuxX64`:

```kotlin
// settings.gradle.kts
pluginManagement { includeBuild("atlas/plugin") }

// build.gradle.kts (корневой): общий classloader с Kotlin plugin
plugins { id("dev.knshell.atlas") apply false }

// build.gradle.kts модуля
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("dev.knshell.atlas")
}

atlas {                                  // оба поля необязательны
    entryPoint = "atlas.main"            // по умолчанию
    core = project(":atlas:core")        // по умолчанию, если такой проект есть
}
```

Плагин создаёт:

- compilation `atlas` (код в `src/linuxX64Atlas/kotlin`), которая видит internal-ы `main`, поэтому
  stories не попадают в бинарник приложения;
- debug-executable `atlas` и таски `linkAtlasDebugExecutableLinuxX64`, `runAtlasDebugExecutableLinuxX64`;
- таску `runAtlas`; `-Pstory=Group/Entry` открывает нужную story.

Собственные linker-опции модуля задавайте для всех executable
(`binaries.withType<Executable>().configureEach { … }`), тогда их получит и атлас.

Библиотеки `framework` и `atlas:core` пока нигде не публикуются: вне этого репозитория их нужно
подключить самому (publishing отложен).

## DSL

Story описывается в группе. Аргументы объявляются как делегированные свойства; их значения
передаются в render как `v[arg]`.

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
    val radius by int(16, 0..32)               // Arg.IntNum, контрол Slider
    val alpha by float(1f, 0f..1f)             // Arg.FloatNum, контрол Slider

    // render по умолчанию для всех story и variants группы
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

    story("Primary")                           // использует render группы
    story("Icon") { v -> /* свой render */ }

    variants("Size X State") {                 // матрица
        axis(size)                             // Arg.Choice: все options
        axis(enabled)                          // Arg.Flag: true, false
        exclude { it[size] == ButtonSize.Large && !it[enabled] }
    }
}
```

Методы `GroupBuilder`:

| Метод | Тип arg | Примечание |
| --- | --- | --- |
| `string(default)` | `Arg.Text` | текст в контроле `Entry` |
| `boolean(default)` | `Arg.Flag` | `Switch` |
| `choice(options, default)` | `Arg.Choice<E>` | `DropDown` |
| `enum(default)` | `Arg.Choice<E>` | то же, опции — все значения enum |
| `int(default, range: IntRange)` | `Arg.IntNum` | `Slider` с диапазоном |
| `float(default, range: ClosedFloatingPointRange<Float>)` | `Arg.FloatNum` | `Slider` с диапазоном |
| `render { v -> }` | — | render группы по умолчанию |
| `story(name, render?)` | — | одна story |
| `variants(name, render?, view?) { … }` | — | матрица; `view` — вид, с которым она открывается |

Внутри `variants { }`:

- `axis(arg: Arg.Choice<E>)` — все options; `axis(arg: Arg.Flag)` — `true`/`false`;
  `axis(arg, values: List<T>)` — явный список значений.
- `exclude { snapshot -> Boolean }` — отбрасывает комбинации. Предикат получает `ArgSnapshot`.

Порядок комбинаций: первая axis меняется медленнее всех.

Ошибки (бросаются не позднее завершения `group { }`):

- `IllegalStateException`: дубль имени arg; дубль имени story или variants в группе (без учёта
  регистра); axis, которой нет среди args группы; story или variants без render (ни своего, ни группы).
- `IllegalArgumentException`: default вне `range`; больше 500 комбинаций в матрице.

## UI

- **Сайдбар** — группы и их stories/variants; активный пункт подсвечен.
- **Превью** — story по центру.
- **Матрица** — для `variants`; сверху переключатель вида. Каждая ячейка — render со своей комбинацией.
  - `MatrixView.Compose` (по умолчанию): сворачиваемые группы по первой axis, название над каждым превью.
  - `MatrixView.Table`: первая axis — строки, вторая — колонки; только для ровно двух axis, иначе
    `IllegalArgumentException` при описании story. Убранная через `exclude` комбинация оставляет «—».
  - Вид по умолчанию задаётся в story: `variants("Variant X Size", view = MatrixView.Table) { … }`.
    Выбор и свёрнутые группы запоминаются для каждой матрицы до закрытия атласа.
  - Значок в углу иконки матрицы в сайдбаре показывает текущий вид.
- **Панель args** справа — контрол для каждого arg: `Entry` для текста, `Switch` для flag,
  `DropDown` для choice, `Slider` с текущим значением для int/float. В матрице панель показывает
  только не-axis args: они действуют на все ячейки, axis задаёт сама комбинация.
- **Reset** в панели возвращает все args story к default.
- **Значения запоминаются отдельно для каждой story** до закрытия атласа: переключение между
  stories не сбрасывает контролы. Значения живут в `LiveArgs`, которые `AtlasHostService` кеширует по story.
- Старт: открывается первая story; пустой каталог показывает пустое состояние.

## Запуск

```bash
./gradlew :shell-app:runAtlas
```

Выбрать story при старте:

```bash
# через Gradle
./gradlew :shell-app:runAtlas -Pstory="Button/Primary"
# или то же через переменную окружения
KN_ATLAS_STORY="Button/Primary" ./gradlew :shell-app:runAtlas
# бинарник
shell-app/build/bin/linuxX64/atlasDebugExecutable/atlas.kexe --story "Button/Size X State"
# или переменная окружения
KN_ATLAS_STORY="Button/Primary" shell-app/build/bin/linuxX64/atlasDebugExecutable/atlas.kexe
```

- `--story Group/Entry` или `--story=Group/Entry` имеет приоритет над `KN_ATLAS_STORY`.
- `Group/Entry` — без учёта регистра, разделение по первому `/`.
- Неизвестное имя — предупреждение со списком доступных story, открывается первая story.

### Инспектор: `--inspect`

```bash
atlas.kexe --inspect=/run/user/1000/atlas.sock
# или переменная окружения
KN_ATLAS_INSPECT=/run/user/1000/atlas.sock atlas.kexe
```

- Сервер стартует **только** с `--inspect=<путь>` (или `--inspect <путь>`) либо `KN_ATLAS_INSPECT`.
  Без них сокета нет, и обычный запуск атласа не меняется.
- Сокет — unix-сокет с правами `0600`, обслуживается в GTK main loop (инспектор трогает виджеты
  напрямую, без потоков).
- Протокол — один JSON-объект на строку, один ответ на строку в том же порядке:

```
→ {"id":1,"method":"tools/list"}
← {"id":1,"result":{"tools":[{"name","description","inputSchema"}]}}
→ {"id":2,"method":"tools/call","params":{"name":"screenshot","arguments":{"target":"preview"}}}
← {"id":2,"result":{"content":[{"type":"image","data":"<base64>","mimeType":"image/png"}],"isError":false}}
← {"id":3,"error":{"message":"..."}}        # неизвестный method
```

  Форма `result` повторяет MCP, поэтому демон передаёт её без преобразований. Ошибка инструмента
  (неизвестный инструмент, плохой аргумент, неоднозначный `target`) возвращается как `result` с
  `isError:true`, а не как `error`.

## Тесты

```bash
./gradlew :atlas:core:linuxX64Test
./gradlew :framework:linuxX64Test
cargo test --manifest-path atlas/mcp/Cargo.toml
cargo clippy --manifest-path atlas/mcp/Cargo.toml
```

Тесты `atlas:core` покрывают только логику, без окна GTK: построение групп и матрицы
(`GroupBuilderTest`), `LiveArgs` (`LiveArgsTest`), `AtlasHostService` (сохранение args по story),
`resolveStory` и `parseStorySpec` (`StorySelectionTest`).

`cargo test` для `atlas/mcp` проверяет демон на фейковом атласе (`examples/fake_atlas.rs`): проксирование
`tools/call`, ошибки, таймаут, пересборку, застарелость бинарника, остановку и конфликт имён. Запускайте
через `cargo test`, потому что тест находит пример в каталоге сборки. Композитор можно поднять отдельно:
`cargo run --manifest-path atlas/mcp/Cargo.toml --example headless -- <имя-сокета>`.

## MCP: управление атласом из Claude

`atlas/mcp` — демон `atlas-mcp` (Rust, официальный SDK `rmcp`, транспорт stdio). Он даёт Claude
инструменты, которыми можно невидимо открыть story, изменить args, сделать скриншот, прочитать дерево
виджетов и выполнить действие (клик, текст, переключатель, слайдер, dropdown, принудительные
hover/pressed/focus). По умолчанию окно атласа не появляется на рабочем столе и не забирает фокус.

### Архитектура

```
Claude ──MCP/stdio──> atlas-mcp (Rust) ──JSON-строки / unix socket──> atlas.kexe --inspect=<socket>
                        │                                              │
                        ├─ поиск атласа, reload / status               └─ framework: дерево, действия, PNG
                        └─ приватный headless Wayland-композитор
                           (atlas/mcp/src/compositor.rs)
```

- Демон сам **не знает о компонентах**. Инструменты атласа описаны в атласе (`tools/list`), демон их
  проксирует; собственные инструменты демона — `list_atlases`, `use_atlas`, `set_visible`,
  `reload`, `status`, `logs`, `stop`.
- Атлас запускается с `WAYLAND_DISPLAY=atlas-mcp-<pid>` и `GDK_BACKEND=wayland`. Так он рендерится на
  приватный сокет демона, а не на твой Hyprland-дисплей.

### Зачем приватный headless-композитор

- **Невидимость.** Атлас подключается только к приватному сокету `WAYLAND_DISPLAY`, поэтому на твоём
  рабочем столе окна нет и фокус не меняется. Что `hyprctl clients` его не видит, проверяется в
  сквозном тесте, а не в коде.
- **Без системных зависимостей.** Не нужны KWin, weston, cage, broadway, браузер или sudo. Композитор —
  небольшой Rust-код в `atlas/mcp/src/compositor.rs`, без рендеринга: он даёт handshake, `configure` и
  поток `wl_callback.done`, без которых GTK 4 перестаёт перекомпоновывать.
- **Нет устройств ввода.** Композитор не объявляет `wl_seat` с клавиатурой или мышью. Действия выполняет
  инспектор (`dev.knshell.inspect.Inspector`): он вызывает те же GTK-сигналы, что и пользователь, поэтому
  `Modifier.onClick`, `onValueChange` и `onCheckedChange` срабатывают.
- Скриншоты рисует сам клиент (GTK-рендерер атласа), композитор ничего не рисует.

### Установка и подключение

Один раз на машину:

```bash
cargo install --path atlas/mcp                       # → ~/.cargo/bin/atlas-mcp
claude mcp add --scope user atlas -- atlas-mcp       # для всех проектов
```

После изменений в `atlas/mcp` повторите `cargo install`. Утилита ничего не знает о конкретном проекте,
поэтому в репозитории для неё нет ни конфига, ни `.mcp.json`.

### Как утилита находит атлас

- **Корень** — ближайшая папка от рабочей директории вверх, где есть `settings.gradle(.kts)`; или `--root`.
- **Атласы** — демон запускает Gradle проекта со своим init-скриптом (`-I`, вшит в бинарник), который
  перечисляет модули с таской `runAtlas…ExecutableLinux…` и отдаёт link-таску, путь к executable,
  рабочую директорию и папки исходников. Менять проект не нужно: подходит и плагин, и описанный вручную
  executable с именем `atlas`.
- **Кеш** — ответ хранится в `$XDG_CACHE_HOME/atlas-mcp/`, пока не изменится какой-нибудь build-скрипт.
- **Проекты без атласа** — если ни один build-файл не упоминает «atlas», Gradle на старте не
  запускается; доступны только собственные инструменты демона (`list_atlases` спросит Gradle принудительно).
- **Несколько атласов** — нужно выбрать модуль через `use_atlas`.

### Инструменты демона

| Инструмент | Аргументы | Что делает |
| --- | --- | --- |
| `set_visible` | `visible` | Показывает окно атласа на рабочем столе или снова прячет его (по умолчанию скрыто). Атлас перезапускается, поэтому story и args возвращаются к начальным. Закрытое окно возвращает невидимый режим. |
| `list_atlases` | — | Заново спрашивает Gradle и перечисляет модули с атласом; активный отмечен `*`. |
| `use_atlas` | `module` (Gradle path, напр. `:app`) | Переключает на атлас другого модуля. |
| `reload` | — | Link-таска активного атласа, затем перезапуск. При ошибке компиляции ответ содержит строки `e:`, а старый атлас продолжает работать. После успеха приходит `tools/list_changed`. |
| `status` | — | Корень проекта, найденные атласы, модуль, pid, пути, последняя ошибка запуска и предупреждение об устаревании. |
| `logs` | `lines?` (целое ≥ 1, по умолчанию 50) | Последние строки stdout и stderr атласа. |
| `stop` | — | Останавливает атлас; следующий вызов запустит его снова. |

Если у атласа есть инструмент с тем же именем, что и у демона, демон его скрывает (строка в stderr).

### Видимый режим

По умолчанию атлас невидим. `set_visible {visible: true}` перезапускает его в обычном окне текущей
графической сессии, чтобы человек видел то же, чем управляет Claude. Помните:

- окно получает фокус, и ваши нажатия клавиш и клики меняют то же состояние, что и инструменты;
- если закрыть окно, следующий вызов запустит атлас невидимым и сообщит об этом;
- скриншоты работают и тогда, когда окно на скрытом workspace (проверено на Hyprland 0.57).

### Инструменты атласа

Список собирает атлас, демон его отдаёт; имена и аргументы:

| Инструмент | Аргументы | Примечание |
| --- | --- | --- |
| `list_stories` | — | Все story в формате `Group/Entry`. |
| `select_story` | `story: "Group/Entry"` | Открывает story; args кешируются по story. |
| `get_args` | — | Текущие значения args story. |
| `set_args` | `values: {name: value}` | Всё или ничего: тип, enum по имени и диапазон проверяются до применения. |
| `reset_args` | — | Возвращает args story к default. |
| `set_view` | `view`: `compose` \| `table` | Вид выбранной матрицы; `list_stories` показывает текущий и доступные. |
| `tree` | `target?`, `depth?` | Дерево виджетов (id, тип, testTag, bounds, CSS-классы, текст, состояние) от корня или от `target`. |
| `screenshot` | `target?` | PNG окна или прямоугольника ноды (с фоном соседей); без `target` — корень композиции. |
| `click` | `target` | Кнопка или `Modifier.onClick`. |
| `set_text` | `target`, `text` | `Entry`; вызывает `onValueChange`. |
| `set_checked` | `target`, `checked` | `Switch`; вызывает `onCheckedChange`. |
| `set_value` | `target`, `value` | `Slider`; значение вне диапазона отклоняется. |
| `select` | `target`, `index` | `DropDown`; индекс вне опций отклоняется. |
| `set_state` | `target`, `state: "hover" \| "pressed" \| "focus"`, `on` | Принудительное состояние GTK; см. ограничения. |

Действия, меняющие UI, ждут, пока атлас стабилизируется (перекомпозиция и layout), прежде чем ответить;
так же `screenshot` и `tree`.

### Цели (`target`)

- **Число** — id ноды из `tree`.
- **Строка** — testTag, его задаёт `Modifier.testTag("name")` (см. `docs/framework-api.md`). Если tag
  отсутствует или его имеют несколько нод, ответ `isError` с объяснением: используй id.

Встроенные testTag атласа:

| testTag | Что это |
| --- | --- |
| `sidebar` | Сайдбар каталога |
| `story:<Group>/<Entry>` | Пункт сайдбара конкретной story |
| `preview` | Превью текущей story |
| `cell:<подпись>` | Ячейка матрицы с такой подписью |
| `args-panel` | Панель args |
| `arg:<name>` | Контрол конкретного arg |
| `reset` | Кнопка Reset |
| `view:compose`, `view:table` | Кнопки переключателя вида матрицы |
| `group:<значение>` | Заголовок группы в Compose-виде |

### Запуск и устаревание

- **Автостарт.** Атлас стартует, когда Claude Code запрашивает список инструментов (при подключении),
  или при первом вызове инструмента. Сам демон бинарник не собирает: если его ещё нет, вызовите
  `reload`. Готовым атлас считается, когда сокет отвечает на `tools/list`; на это есть 20 с. Если процесс
  умер раньше, ответ содержит хвост лога.
- **Смерть атласа.** Следующий вызов запускает его снова (сообщение в stderr демона).
- **Таймаут.** Один запрос длится не дольше 60 с. Если атлас завис, его убивают, и следующий вызов
  стартует новый.
- **Устаревание.** Если файл в `src` какого-нибудь модуля или build-скрипт новее max(mtime бинарника,
  начало последнего `reload`), к ответу атласа добавляется предупреждение `call reload to rebuild`.
  Оно есть и в `status`.
- **Выход.** По EOF stdin, SIGTERM или SIGINT демон убивает атлас, останавливает композитор, убирает
  сокет и лог. Атлас получает `PR_SET_PDEATHSIG` от демона, поэтому не останется висеть, если демон упал.

### Ограничения

- **Popup-ы и popover-ы — отдельные поверхности.** Открытый список `DropDown` и содержимое
  `HoverPopover` (отдельная Compose-композиция) не попадают на скриншот и в `tree`. Опции `DropDown`
  видны в поле `options` его ноды.
- **`set_state` не вызывает `Modifier.onHover`.** Он только выставляет GTK state flags. Hover-callback-и
  идут из GTK motion controller, и `set_state` его не затрагивает.
- **Без CSS-переходов.** Под инспектором GTK-переходы отключены, чтобы снимок показывал конечное
  состояние; Compose-анимации не затрагиваются.
- **GPU.** Композитор объявляет `linux-dmabuf` и первый render node (`/dev/dri/renderD128+`), поэтому
  GTK рендерит на GPU тем же путём, что и на рабочем столе (проверено: `GskVulkanRenderer` на NVIDIA).
  Без render node клиент переходит на программный рендер через `wl_shm`. Другие драйверы и машины с
  несколькими GPU не проверялись; шейдеры отдельно тоже.
- **Реального ввода нет.** Клавиатура и мышь до атласа не доходят. Фокус и фокусные состояния только
  выставляются принудительно (`set_state focus`).
- **Golden-скриншотов нет.** Скриншоты MCP — для просмотра и проверки, а не для сравнения пикселей.

## Отложено

Это решения для будущих этапов, в коде их пока нет.

- **Rust-runner и KN-библиотеки.** Идея: `shell-kn` / `atlas-kn` как KN-библиотеки с `@CName`,
  тонкий Rust-runner с CLI, watch и relink. Почему отложено: текущий Kotlin/Native executable
  достаточен; hot reload = перезапуск процесса, и этого хватает.
- **Декоратор темы.** Идея: тема stories задаётся извне лямбдой (`KnTheme` потребителя), а Atlas
  не знает о токенах ui kit. Переключатель Light/Dark — через эту лямбду.
- **Поиск** по stories в сайдбаре.
- **Kotlin-сниппет** под контролами, который обновляется вместе со значениями.
- **Pan/zoom** канвас (как в `mockups/overview.html`) и группировка вариантов.
- **UI kit** (`Button`/`Label`/`Slider` с вариантами) и перевод stories на него.
- **KSP** для генерации args; **golden PNG** для критичных компонентов.
- **Node-тесты** на основе stories (дамп дерева GTK для каждой комбинации).

Сохраняем из дизайна: Atlas изолирован от ui kit и его тем, stories не тянут сервисы
потребителя, а `core` не импортирует `services/`.

## Ограничения

- **Нет reflection** в Kotlin/Native: args объявляются вручную в DSL.
- **Дрейф контролов:** `int`/`float`/`enum` могут разойтись с сигнатурой компонента. Компилятор
  ловит большинство расхождений, потому что story вызывает компонент напрямую.
- **Компоненты должны экспонировать параметры** (`enabled`, `size`, `radius`), иначе контролировать
  нечего. Например, `Button(text, modifier, onClick)` не имеет `enabled`/`size`, поэтому stories
  задают стиль через `Modifier`.
- **Hot reload = перезапуск процесса.** Изменение кода требует повторного link Kotlin/Native.
