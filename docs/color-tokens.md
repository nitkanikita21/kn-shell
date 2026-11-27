# Пам'ятка: кольорові токени

Коротка шпаргалка «який токен брати під яку задачу». Канонічний опис —
[`docs/theme.md`](theme.md), код — `demo/shell-ui-kit/src/commonMain/kotlin/dev/knshell/uikit/theme/` (токени й виведення
кольорів; сервіс і джерела шпалер — у `demo/shell-app`).

## Хто що володіє

| Шар | Файл | Відповідає за |
| --- | --- | --- |
| Rust `palette-core` | `palette-core/src/lib.rs` | читає шпалери → кластери (L/C/h/вага) + `suggested_hue`, `suggested_chroma`, `suggested_mode` |
| Kotlin `WallpaperPalette` | `demo/shell-ui-kit/.../theme/WallpaperPalette.kt` | дизайн-вхід: `hue`, `familyChroma` (макс. у родині), `secondHue`, `dominantLightness` |
| Kotlin `ThemeScale` | `demo/shell-ui-kit/.../theme/ThemeScale.kt` | палітра + режим + `ThemeTuning` → 30 семантичних токенів |
| Kotlin `ThemeTuning` | `demo/shell-ui-kit/.../theme/ThemeTuning.kt` | наскільки явний тон: `tint`/`accent`/`lift`/caps/`secondHue` + пресети |
| `ThemeService` | `demo/shell-app/.../theme/ThemeService.kt` | `tokens`, `palette`, `mode`, `tuning` як `StateFlow`; `setMode()`, `setTuning()`, `reloadTheme()` |
| UI | будь-де | **тільки** читає токени, ніколи не рахує колір сам |

Правило: якщо в UI з'являється `Color.oklch(...)`, `Color.hex(0x…)` або `Catppuccin.*` — це баг
шару. `Catppuccin` у `framework/.../Color.kt` існує лише як референсна палітра для прикладів
бібліотеки (`docs/bar-api.md`), не для shell-UI.

## Як дістати токени

```kotlin
val theme by collectTheme()          // State<DesignTokens>, ui/helpers/collectTheme.kt
val c = theme.colors                 // ThemeTokens
Text("hi", color = c.foreground)

val palette by collectPalette()      // State<WallpaperPalette> — h/h₂/family для діагностики
val tuning by collectThemeTuning()   // State<ThemeTuning> — поточний рівень тонування
```

- `DesignTokens`: `colors`, `radius`, `spacing`, `typography`, `motion`, `icon`.
  Зараз UI реально споживає тільки `colors` (radius/spacing/motion/icon ніде не читаються —
  захардкоджені числа в компонентах поки що норма).
- `DesignTokens.default()` — до першого підвантаження палітри (`WallpaperPalette.Achromatic`,
  тобто нейтрально, без вигаданого кольору).
- `DesignTokens.fromPalette(palette, isDark, tuning)` — те, що робить `ThemeService`.
- `ThemeMode.Auto` слідує за шпалерами, `Dark`/`Light` перебивають; `setMode()` і `setTuning()`
  перераховують токени з останньої палітри без повторного читання шпалер.
- Без hue (NAN від palette-core) → `NEUTRAL_HUE = 250f` у `WallpaperPalette.kt`.
- Тонування: `ThemeTuning.presets` = `off`/`subtle`/`balanced`/`vivid`;
  `ThemeTuning.preset("vivid")` для конфіга/CLI. `tint = 0` дає чисто нейтральний UI.

## 30 токенів

Пара `X` / `XForeground` — завжди разом: `foreground` — це колір, читабельний **на** `X`.

| Токен | Для чого | Приклад у коді |
| --- | --- | --- |
| `background` | саме вікно, базова поверхня | `USurface` |
| `foreground` | основний текст/іконки на `background` | текст `USurface` |
| `card` | піднята поверхня: картки, панелі | `UCard`, `UInput` |
| `cardForeground` | текст на `card` | `UKbd` |
| `popover` | плаваючі поверхні: меню, тултіпи (зараз == `card`) | `UPopupSurface`, `UModal`, `UToastCard` |
| `popoverForeground` | текст на `popover` | там само |
| `primary` | **бренд** зі шпалер: активний таб, заповнена кнопка, акцентна іконка | `UButton` (Solid), активна вкладка |
| `primaryForeground` | текст/іконка на `primary` (== `background` за значенням) | текст на ньому |
| `secondary` | другий ступінь поверхні: неактивний таб, well, трек прогресу | список `UTabs` (Pill), трек `UProgress` |
| `secondaryForeground` | текст на `secondary` | — |
| `muted` | приглушена зона (зараз == `secondary`) | — |
| `mutedForeground` | другорядний текст: підказки, підписи, таймстемпи | описи, підписи, неактивні вкладки |
| `accent` | **hover/pressed wash**, НЕ бренд | наведення в меню й списках, `UAvatar`, `USkeleton` |
| `accentForeground` | текст на `accent` | `UAvatar` |
| `destructive` | помилки, видалення; червоний, підфарбований під теплі шпалери (±12°) | `UColor.Error`, рамка поля з помилкою |
| `destructiveForeground` | текст на `destructive` | текст на ньому |
| `border` | хайрлайн між поверхнями | рамки `UCard`, `UModal`, розділювачі |
| `input` | обведення полів вводу, трохи сильніше за `border` | обведення полів, прапорця, радіо |
| `ring` | focus ring | `uFocusRing()` |
| `success` / `successForeground` | вдалося: заливка і текст на ній | — |
| `warning` / `warningForeground` | потребує уваги | — |
| `info` / `infoForeground` | нейтральна інформація | — |
| `primaryText`, `errorText`, `successText`, `warningText`, `infoText` | той самий колір як **текст** на фоні чи на легкому тоні (варіанти outline, soft, ghost, link) | — |

Кожна пара з текстом і кожен `*Text` тримають контраст щонайменше 4.5:1 за будь-яких шпалер;
це перевіряє `ThemeContrastTest` у `demo/shell-ui-kit`. Через це в темній темі
`destructiveForeground` темний (дорівнює `background`).

«—» = токен є, але компоненти кіта його не беруть. Це не привід плодити новий токен під свою
задачу: спершу перевір, чи підходить наявний.

`ThemeTokens.entries()` віддає всі 30, спершу набір shadcn — їм ітеруються свотчі у демо, тож новий
токен неможливо додати й забути в демо.

## Пастки

1. **`accent` ≠ `primary`.** `accent` — це hover-заливка (крок над `secondary`); брендовий колір —
   `primary`. Активний таб/кнопку фарбують `primary`, рядок під курсором — `accent`.
2. **`muted` == `secondary`** за значенням в обох режимах. Не будуй на різниці між ними; різниця
   лише семантична.
3. **`primaryForeground` — це і є `background`** за значенням. Для тексту на `primary` пиши
   `primaryForeground`: пара очевидна.
4. **`border`/`input` у dark — напівпрозорі й тоновані** (L `0.62`/`0.68`, `alpha 0.22`/`0.28`).
   Вони лягають *поверх* поверхні, тому їх не можна підставляти як `background`, і не можна
   очікувати однакового вигляду на різних поверхнях. У light — суцільні.
5. **`destructive` — червоний, але не фіксований**: hue 25°, тягнеться за шпалерами на 0.35,
   clamp `±12°`, і тільки для теплих hue (`|Δh| ≤ 90°`). Не роби його повністю зі шпалер —
   помилка, пофарбована в зелений акцент, перестає читатись як помилка.
6. **Нейтралі будуються з `familyChroma`, а не з `suggested_chroma`.** `suggested_chroma` —
   зважене середнє по насичених кластерах, тому на контрастних шпалерах воно падає до ~0.01 і
   UI стає сірим. `familyChroma` — максимум chroma кластерів у родині домінантного hue
   (`±30°`); саме він 0.010…0.187 на реальних шпалерах. Не замінюй на чистий сірий
   «щоб було нейтральніше» — для цього є `ThemeTuning.Off`.
7. **`primary` не світиться**: `chroma × PRIMARY_GAIN(2.5)`, стеля `0.18`; у light L = `0.55`
   (у dark `0.705`). Не піднімай ці числа локально — тільки в `ThemeScale`.
8. **Dark-поверхні впираються в gamut, а не в `neutralCap`.** При L ≈ 0.16 sRGB дозволяє лише
   ~0.042 chroma для зеленого (hue 126°) і ~0.051 для синього (253°). Тому всі кольори йдуть
   через `Color.oklchFitted` (зберігає L і h, підрізає chroma), а «зробити явніше» — це
   `tuning.lift`, не підняття cap-у: cap вище ~0.05 сам по собі нічого не змінює.
9. Для тексту на довільному кольорі порівнюй `contrastRatio(текст, колір.over(фон))`, як свотчі в
   демо. Для пар теми бери `XForeground`.
10. **Другий hue — не завжди.** `secondHue` є тільки якщо в шпалері є кластер ≥ 40° від
    домінантного з `c ≥ 0.02`. Не вигадуй другий hue «для краси»: монохромні шпалери мають
    давати монохромний UI.

## Рецепти

```kotlin
// Картка
Box(Modifier.fillWidth().radius(12).background(c.card).padding(16)) {
    Text("Title", color = c.cardForeground, modifier = Modifier.bold())
    Text("helper", size = 12, color = c.mutedForeground)
}

// Таб / pill: активний — бренд, неактивний — другий ступінь
Row(Modifier.radius(999, 999).background(if (active) c.primary else c.secondary)) {
    Text(name, color = if (active) c.primaryForeground else c.foreground)
}

// Рядок списку з hover
Row(Modifier.hoverBackground(c.accent)) { /* вміст */ }

// Прогрес: заповнення — primary, трек — secondary
Row(Modifier.height(6).radius(999, 999).clipOverflow()) {
    Box(Modifier.width(190).fillHeight().background(c.primary))
    Box(Modifier.weight(1.0).fillHeight().background(c.secondary))
}

// Хайрлайн
Modifier.background(c.card, borderWidth = 1, borderColor = c.border)
```

Анімація зміни кольору: `background(..., transitionMs = …)`, `textColor(color, transitionMs = …)`
або `Icon(..., transitionMs = 180)` — твін іде на frame clock, без рекомпозиції на кожен кадр.

## Чек-ліст ревʼю

- [ ] Жодного літерального `Color` / `Catppuccin` у shell-UI.
- [ ] Кожен `X`-фон має пару `XForeground` (або свідомо `foreground`).
- [ ] Бренд-акцент — `primary`; hover — `accent`.
- [ ] Новий токен додано в `ThemeTokens` **і** в `entries()`, з поясненням ролі в KDoc.
- [ ] Зміна чисел derive-у — тільки в `ThemeScale.kt` (+ `ThemeTuning` для явності тону),
      разом з оновленням `docs/theme.md`.
- [ ] Жоден токен не використовує `Color.oklch` напряму — тільки `Color.oklchFitted`
      (інакше hue дрейфує, коли chroma не влазить у sRGB).
