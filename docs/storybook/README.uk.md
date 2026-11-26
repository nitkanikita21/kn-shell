# Atlas — каталог компонентів (kn-shell)

Atlas — робочий каталог для перегляду й перевірки компонентів `framework`: кожна story показує
компонент з актуальними значеннями контролів, а матриця variants — усі комбінації осей одразу.
Аналог `@Preview` у Compose Android або Storybook.

Мокапи (лише дизайн, CSS-імітація, не GTK): `docs/storybook/mockups/`.
Дослідження і прийняті рішення: `RESEARCH.md`.

## Модулі

```
framework     GTK4-міст, Compose DSL, UI-віджети                     ← бібліотека
atlas-core    DSL stories, модель args, runner, UI каталогу           ← залежить від framework
shell-app     compilation linuxX64Atlas: stories і точка входу        ← застосунок
```

- **`atlas-core`** (`atlas-core/`) — `group`/`story`/`variants`, типи args (`Arg`), `LiveArgs`,
  `CellArgs`, `AtlasHostService`, `resolveStory`, `parseStorySpec`, `atlasMain`, UI каталогу
  (`runner/ui`). Пакет `dev.knshell.atlas`.
- **Stories** живуть у `shell-app`, у compilation `linuxX64Atlas` (`shell-app/src/linuxX64Atlas/kotlin/atlas/`).
  Compilation залежить від `:atlas-core`, тож звичайний `main` (шелл) stories не містить.
  Точка входу — `fun main(args: Array<String>)` у `atlas/main.kt`, вона викликає `atlasMain(...)`.
- Runner — не окремий модуль і не Rust-процес: Atlas — звичайний Kotlin/Native executable
  (`atlas`, debug), in-process.

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
| `story(name, render?)` | — | одна story |
| `variants(name, render?) { … }` | — | матриця |

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
- **Матриця** — для `variants`: сітка всіх комбінацій axis (`Flow`); кожна комірка — render з
  її комбінацією, підпис комірки — значення axis.
- **Панель args** праворуч — контрол для кожного arg: `Entry` для тексту, `Switch` для flag,
  `DropDown` для choice, `Slider` із значенням для int/float. У матриці панель показує лише
  не-axis args: вони діють на всі комірки, axis задає сама комбінація.
- **Reset** у панелі повертає всі args story до default.
- **Значення памʼятаються окремо для кожної story** до закриття атласу: перемикання між stories
  не скидає контроли. Значення живуть у `LiveArgs`, які `AtlasHostService` кешує за story.
- Старт: відкривається перша story; порожній каталог показує порожній стан.

## Запуск

```bash
./gradlew :shell-app:runAtlas
```

Обрати story при старті:

```bash
# через Gradle
./gradlew :shell-app:runAtlas -Pstory="Button/Primary"
# або те саме через змінну середовища
KN_ATLAS_STORY="Button/Primary" ./gradlew :shell-app:runAtlas
# бінарник
shell-app/build/bin/linuxX64/atlasDebugExecutable/atlas.kexe --story "Button/Size X State"
# або змінна середовища
KN_ATLAS_STORY="Button/Primary" shell-app/build/bin/linuxX64/atlasDebugExecutable/atlas.kexe
```

- `--story Group/Entry` або `--story=Group/Entry` має пріоритет над `KN_ATLAS_STORY`.
- `Group/Entry` — без урахування регістру, поділ по першому `/`.
- Невідома назва — попередження зі списком доступних story і відкривається перша story.

## Тести

```bash
./gradlew :atlas-core:linuxX64Test
```

Тести покривають лише логіку, без GTK-вікна: побудову груп і матриці (`GroupBuilderTest`),
`LiveArgs` (`LiveArgsTest`), `AtlasHostService` (збереження args за story), `resolveStory` і
`parseStorySpec` (`StorySelectionTest`).

## Відкладено

Це рішення для майбутніх етапів, у коді їх немає.

- **Rust-runner і KN-бібліотеки.** Ідея: `shell-kn` / `atlas-kn` як KN-бібліотеки з `@CName`,
  тонкий Rust-runner з CLI, watch і relink. Причина відкладання: поточний Kotlin/Native
  executable достатній; hot reload = перезапуск процесу, і цього досить.
- **Декоратор теми.** Ідея: тема stories задається ззовні лямбдою (`KnTheme` споживача), а
  Atlas не знає про токени ui kit. Перемикач Light/Dark — через цю лямбду.
- **Пошук** по stories у сайдбарі.
- **Kotlin-сніпет** під контролами, що оновлюється разом із значеннями.
- **Pan/zoom** канвас (як у `mockups/overview.html`) і групування варіантів.
- **UI kit** (`Button`/`Label`/`Slider` з варіантами) і переведення stories на нього.
- **KSP** для генерації args; **golden PNG** для критичних компонентів.
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
