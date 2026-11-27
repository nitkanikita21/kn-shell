# Як писати компоненти `shell-ui-kit`

Еталони, з яких копіювати: [`UButton`](src/commonMain/kotlin/dev/knshell/uikit/components/element/UButton.kt)
(простий), [`UTabs`](src/commonMain/kotlin/dev/knshell/uikit/components/navigation/UTabs.kt)
(клавіатура з roving-фокусом), [`UModal`](src/commonMain/kotlin/dev/knshell/uikit/components/overlays/UModal.kt)
(шар поверх вікна й фокус). Їхні сторіси лежать у `src/linuxX64Atlas/kotlin/stories/`.

Вигляд, сигнатура API, ARIA-роль і таблиця клавіш кожного компонента — у
[`mockup/index.html`](mockup/index.html) (масив `C` у `<script>`). Це специфікація.

## Де що лежить

```
src/commonMain/kotlin/dev/knshell/uikit/
├── theme/        токени, UTheme, UThemeConfig, USurface
├── core/         UColor, UVariant, USize, UAction; кольори варіантів, розміри, uFill/uFont/…
├── a11y/         uRovingItem, UTypeahead, uFieldControl
├── overlay/      UPopupSurface, toPopupSide/toPopupAlign
└── components/<element|feedback|form|navigation|overlays>/U<Name>.kt
src/linuxX64Atlas/kotlin/stories/<та сама категорія>/U<Name>Stories.kt
```

Один компонент — один файл разом зі своїми `U<Name>Ui` і `U<Name>Defaults`. Залежності йдуть лише
в один бік: `theme → core → a11y / overlay → components`. Компоненти використовують один одного
тільки через публічний API.

## Правила API

```kotlin
data class UBadgeUi(val base: Modifier = Modifier, val label: Modifier = Modifier)

data class UBadgeDefaults(
    val color: UColor = UColor.Primary,
    val variant: UVariant = UVariant.Soft,
    val size: USize = USize.Md,
    val ui: UBadgeUi = UBadgeUi(),
) : UComponentDefaults

@Composable
fun uBadgeDefaults(): UBadgeDefaults = UTheme.config.find() ?: UBadgeDefaults()

@Composable
fun UBadge(
    label: String,
    modifier: Modifier = Modifier,
    color: UColor = uBadgeDefaults().color,
    …
    ui: UBadgeUi = UBadgeUi(),
)
```

- Порядок параметрів: обов'язкові дані, `modifier`, вигляд (`color`, `variant`, `size`), поведінка,
  `accessibleLabel`, `ui`, слот вмісту останнім.
- Стан контрольований: `value` + `onValueChange`, `open` + `onOpenChange`, `selected` + `onSelect`.
- Списки — даними (`items: List<U…Item>`) плюс слот вмісту.
- Слоти зливаються в порядку «тема → варіант → `defaults.ui` → `ui` екземпляра → `modifier`»; пізніший
  модифікатор перемагає: `.….then(themed.base).then(ui.base).then(modifier)`.
- `accessibleLabel` обов'язковий там, де немає видимого тексту (`require` із поясненням).
- Тексти, які чує скрінрідер і яких не передає викликач (наприклад «Закрити»), лежать у `Defaults`.
- Публічні лише компоненти, їхні типи й тема. Решта `private` або `internal`.
- KDoc на компоненті: що робить, які клавіші, коли потрібен `accessibleLabel`.

## Тема

- Кольори, радіуси, відступи, шрифти — лише з `UTheme.tokens`. Жодних захардкоджених кольорів і
  жодного `remember` з кольором без ключа.
- Фарбувати через `uFill`, `uTextColor`, `uVariant`, `uFont`: вони плавно переходять при зміні теми.
  Власні `border`/`textColor` передають `transitionMs = tokens.motion.themeMs`.
- `(color, variant)` → кольори: `UTheme.tokens.colors.variantStyle(color, variant)`; три кольори
  значення — `colors.colorSet(color)`. Для тексту на фоні вікна беруть `…Text` (`colorSet(c).text`),
  а не заливку.
- Розміри: `size.metrics` (висота, шрифт, горизонтальний padding, іконка, проміжок).
- Мапи й обчислення кольорів не кешувати: тема змінюється, поки компонент відкритий.

## Доступність і клавіатура

- Роль, назву й стани задає `Modifier.semantics(Semantics(role = Role.…, label = …, checked = …))`.
  Поле, лишене `null`, не заявляється. Зв'язки: `SemanticsRef` + `Modifier.semanticsRef(ref)`,
  далі `labelledBy = ref`, `describedBy`, `controls`, `activeDescendant`. Повідомлення:
  `ref.announce("…", assertive = false)`.
- Те, на що можна натиснути, — `Button(modifier, onClick) { … }` (Enter, Space і клік уже є) з
  потрібною роллю, або `Modifier.focusable()` + `onKey` + `onClick`.
- Складений віджет (вкладки, радіогрупа, меню) — одна зупинка Tab: `rememberURoving()` +
  `Modifier.uRovingItem(state, key, keys, selected, orientation, onMove = …)`.
- `Modifier.onKey { event -> … }` повертає `true`, лише якщо клавішу використано.
- Недоступний контрол: `Modifier.enabled(false)` + `uDimmed(true)` + `disabled = true` у semantics.
- Рамка фокуса: `Modifier.uFocusRing()` на кожному фокусовному. Пункти меню й списку додатково
  заливаються `Modifier.uFocusFill(color)`: у popover-і рамки `:focus-visible` GTK не малює.
- Поле форми: контрол застосовує `Modifier.uFieldControl(Semantics(…))` замість `semantics` і
  `uInvalid(invalid)` для вигляду; тоді `UFormField` сам зв'язує назву, опис і помилку.

## Поверх вмісту

- Меню, список, popover, tooltip: `dev.knshell.shell.Popup(open, onDismiss, side = side.toPopupSide(),
  align = align.toPopupAlign(), content = { UPopupSurface { … } }) { trigger() }`. Escape і клік
  зовні викликають `onDismiss`; фокус повертається сам.
- Тригер меню, popover чи tooltip — слот `trigger: @Composable (UTrigger) -> Unit`. Компонент віддає в
  `UTrigger.modifier` ARIA-стан, клавіші й фокус, а в `UTrigger.onClick` — дію; викликач застосовує
  обидва до фокусовного віджета.
- Tab у меню чи списку: закрити й `triggerRequester.moveFocus(backwards = event.shift)`. У popover-і:
  `FocusScope(autoFocus = false, restoreFocus = false, onTabOut = { backwards -> … })`.
- Діалог і тости: `Portal { … }` (потрібен `OverlayHost` вище; атлас його дає).
- Вміст popover-а й порталу бачить тему місця, де його оголошено.

## Особливості фреймворку

- `minWidth`/`minHeight` — CSS-розміри вмісту, **без** padding і рамки.
- `Label` успадковує колір і шрифт від предків; `align = 0f` притискає текст ліворуч.
- `Row`, `Column`, `Box` — `GtkBox`: щоб дитина зайняла весь залишок, `Modifier.weight()` або
  `hExpand()`; вирівнювання — `Modifier.align(h = …, v = …)`.
- Модифікатори: `framework/src/nativeMain/kotlin/dev/knshell/compose/Modifier*.kt`. Іконки:
  `Icon(Lucide.Name, size = …, color = …)`; потрібні іконки пакуються з джерел самі.
- `Entry` (`GtkEntry`) сам обробляє Enter і Escape: щоб почути їх, `onKey(capture = true)`.
  `password = true` ховає символи.
- Усе, що рухається саме (пульс, відлік, невизначений прогрес), перевіряє
  `UTheme.tokens.motion.animate` і при `false` стоїть: у сторісах він `false`, бо кадр golden має
  заспокоїтися. Кадри анімації: `LaunchedEffect(key) { while (true) withFrameNanos { … } }`.

## Стандарт сторісів

Файл `U<Name>Stories.kt`, значення `u<Name>Stories`, група
`group("U<Name>", category = "Element" | "Feedback" | "Form" | "Navigation" | "Overlays")`.

| Story | Що показує |
|---|---|
| `Playground` | компонент з args (`string`, `boolean`, `enum`); `render` групи малює саме його |
| `Variants` | матриця `color × variant` (`view = MatrixView.Table`), якщо вони є |
| `Sizes` | матриця за `size`, якщо він є |
| `States` | усі стани поруч; `states = listOf(InteractionState.Hover, InteractionState.Focus)` на віджеті з `testTag("target")` (або `stateTarget`) |
| `Keyboard` | `play { }`: кожен рядок таблиці клавіш і перевірка стану після нього |
| `Accessibility` | `play { }`: `role`, `name`, стани кожної частини |
| `Examples` | типові сценарії: дані ззовні, `ui`-слоти, стандарти з теми |

- `docs("…")` одним абзацом: призначення, роль, клавіші.
- Сторіс зі станом тримає його в `remember { mutableStateOf(…) }`. Результат дії показано текстом
  (`Label` з tag), щоб `play` міг його перевірити.
- Кадр не залежить від часу: таймери в сторісах зупинені.
- Overlay-сторіс бере місце заздалегідь: `OverlayStage { … }`. Вміст popover-а знімає
  `snapshot("open", "<tag>")`.
- `testTag` унікальні в межах story. `type(…)` друкує лише латиницю, цифри, пробіл і `- . , /`.
- Дії й перевірки `play`: `focus`, `click`, `hover`, `press(Key.…, shift = true)`, `type`,
  `setText`, `snapshot`, `expectAbsent`, `expect(tag).role() .name() .description() .text()
  .focused() .enabled() .selected() .expanded() .checked("mixed") .state("valuenow", "5.0")`.

Нову групу додають у `src/linuxX64Atlas/kotlin/atlas/catalog.kt` за категоріями.

## Перевірка

```bash
./gradlew :demo:shell-ui-kit:linuxX64Test          # логіка й контраст
./gradlew :demo:shell-ui-kit:runAtlas              # каталог у вікні (-Pstory=UButton/Keyboard)
./gradlew :demo:shell-ui-kit:atlasGoldenCheck      # вигляд і play, Dark і Light
./gradlew :demo:shell-ui-kit:atlasGoldenRecord     # оновити еталони
```

Після запису еталони треба переглянути очима: запис приймає будь-який вигляд.
