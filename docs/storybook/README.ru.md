# Atlas — каталог компонентов (kn-shell)

Atlas — рабочий каталог для просмотра и проверки компонентов `framework`: каждая story показывает
компонент с актуальными значениями контролов, а матрица variants — все комбинации осей сразу.
Аналог `@Preview` в Compose Android или Storybook.

Мокапы (только дизайн, CSS-имитация, не GTK): `docs/storybook/mockups/`.
Исследования и принятые решения: `RESEARCH.md`.

## Модули

```
framework     GTK4-мост, Compose DSL, UI-виджеты                     ← библиотека
atlas-core    DSL stories, модель args, runner, UI каталога           ← зависит от framework
shell-app     compilation linuxX64Atlas: stories и точка входа        ← приложение
```

- **`atlas-core`** (`atlas-core/`) — `group`/`story`/`variants`, типы args (`Arg`), `LiveArgs`,
  `CellArgs`, `AtlasHostService`, `resolveStory`, `parseStorySpec`, `atlasMain`, UI каталога
  (`runner/ui`). Пакет `dev.knshell.atlas`.
- **Stories** живут в `shell-app`, в compilation `linuxX64Atlas` (`shell-app/src/linuxX64Atlas/kotlin/atlas/`).
  Compilation зависит от `:atlas-core`, поэтому обычный `main` (шелл) stories не содержит.
  Точка входа — `fun main(args: Array<String>)` в `atlas/main.kt`, она вызывает `atlasMain(...)`.
- Runner — не отдельный модуль и не Rust-процесс: Atlas — обычный Kotlin/Native executable
  (`atlas`, debug), in-process.

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
| `variants(name, render?) { … }` | — | матрица |

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
- **Матрица** — для `variants`: сетка всех комбинаций axis (`Flow`); каждая ячейка — render с
  её комбинацией, подпись ячейки — значения axis.
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

## Тесты

```bash
./gradlew :atlas-core:linuxX64Test
```

Тесты покрывают только логику, без окна GTK: построение групп и матрицы (`GroupBuilderTest`),
`LiveArgs` (`LiveArgsTest`), `AtlasHostService` (сохранение args по story), `resolveStory` и
`parseStorySpec` (`StorySelectionTest`).

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
