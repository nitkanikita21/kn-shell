# shell-ui-kit

UI-кіт прикладу шелу: дизайн-система й 23 компоненти на `framework` за патернами WAI-ARIA, з API в
дусі Nuxt UI. Це частина **прикладу** (`demo/`), а не API фреймворку.

## Підключення

```kotlin
commonMain.dependencies { implementation(project(":demo:shell-ui-kit")) }
```

```kotlin
UTheme(tokens) {                         // на корені кожного вікна
    OverlayHost(Modifier.expand()) {     // шар для діалогів і тостів
        USurface(Modifier.expand().uToastShortcut(toaster)) {
            UButton("Зберегти", onClick = { toaster.add(UToast("Збережено", color = UColor.Success)) })
        }
        UToasterHost(toaster)
    }
}
```

`tokens` — це `DesignTokens.default()`, `DesignTokens.fromPalette(палітра, isDark, tuning)` або
власні. Тему можна міняти будь-коли: компоненти, зокрема відкриті popover-и й діалоги, переходять на
нові кольори плавно (`motion.themeMs`). `motion.animate = false` зупиняє все, що рухається саме.

Стандарти компонентів задаються в темі, а слоти — на екземплярі:

```kotlin
UTheme(config = UThemeConfig(UButtonDefaults(variant = UVariant.Soft))) {
    UButton("Видалити", onClick = ::delete, color = UColor.Error, ui = UButtonUi(base = Modifier.radius(999)))
}
```

## Компоненти

| Категорія | Компоненти |
|---|---|
| Element | `UButton`, `UBadge`, `UCard`, `USeparator`, `UKbd`, `UAvatar` |
| Feedback | `UProgress`, `USkeleton`, `UAlert`, `UToastCard` + `UToaster` + `UToasterHost` |
| Form | `UInput`, `UFormField`, `UCheckbox`, `USwitch`, `URadioGroup`, `USlider`, `USelect` |
| Navigation | `UTabs`, `UAccordion` |
| Overlays | `UTooltip`, `UPopover`, `UDropdownMenu`, `UModal` |

Клавіші й ролі кожного — у KDoc і в `docs(…)` його story. Тригер меню, popover чи tooltip отримує
`UTrigger`: `{ t -> UButton("Меню", onClick = t.onClick, modifier = t.modifier) }`.

## Тости в панелі

Панель layer-shell замала для стосу тостів і типово не бере клавіатуру. Хост тримають в окремому
вікні: `barWindow(BarWindowSpec(layer = BarLayer.Overlay, keyboardMode = GTK_LAYER_SHELL_KEYBOARD_MODE_ON_DEMAND, …))` з
`UTheme` + `OverlayHost` + `UToasterHost` всередині. Автоматично такого вікна кіт не створює.

## Каталог і перевірки

```bash
./gradlew :demo:shell-ui-kit:runAtlas              # каталог; -Pstory=UButton/Keyboard
./gradlew :demo:shell-ui-kit:linuxX64Test          # логіка й контраст
./gradlew :demo:shell-ui-kit:atlasGoldenCheck      # вигляд і play-сценарії, Dark і Light
./gradlew :demo:shell-ui-kit:linkAtlasDebugExecutableLinuxX64 && scripts/a11y-check.py   # AT-SPI
```

Як писати компоненти й сторіси — [CONTRIBUTING.md](CONTRIBUTING.md).

## Перевірка зі скрінрідером (вручну)

`a11y-check.py` бачить, що ролі, назви й стани доходять до AT-SPI. Як це звучить, перевіряє людина:
запустіть каталог (`runAtlas`) у своїй сесії, увімкніть Orca (`orca`) і пройдіть:

- [ ] `UTabs/Playground`: Orca називає список вкладок, вкладку, «вибрано» й позицію.
- [ ] `UCheckbox/States`: «прапорець», назва, «відмічено / не відмічено / частково».
- [ ] `USwitch/Playground`: «перемикач», стан змінюється після Space.
- [ ] `URadioGroup/Playground`: назва групи, пункт, «вибрано»; стрілки озвучуються.
- [ ] `USlider/Playground`: значення озвучується під час зміни.
- [ ] `USelect/Playground`: «комбінований список», поточне значення; пункти у відкритому списку.
- [ ] `UFormField/States`: назва, підказка або помилка, «обов'язкове», «неправильне значення».
- [ ] `UDropdownMenu/Playground`: «меню», пункти, позначений пункт.
- [ ] `UModal/Playground`: після відкриття читаються заголовок і опис; фокус не виходить назовні.
- [ ] `UToast/Stack`: нове повідомлення озвучується саме, помилка — одразу; F8 переносить фокус.
- [ ] `UProgress/Playground`: «індикатор поступу» з відсотками.

## Відомі межі

- Меню без підменю.
- Індикатор `loading` у кнопці не обертається: у фреймворку немає обертання іконки.
- `UAvatar` і картинка тоста беруть лише локальні файли.
- Вбудовані тексти («Закрити», «Надіслати») українські; змінюються через `Defaults` компонента.
- Таблиць, дерева, combobox із набором тексту, command palette й валідації форм немає.
