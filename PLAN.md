# План: кольорові токени, похідні від шпалер

**Статус:** готово до ревʼю
**Модулі:** `shell-app` (тема), `framework` (Color), docs

## Context

**Проблема.** Зі шпалер зараз береться лише один бренд-акцент. `ThemeScale` будує всі поверхні з
фіксованих lightness і майже нульової chroma (`NEUTRAL_CHROMA = 0.008`), тож shell — це чорний/білий
UI з однією кольоровою кнопкою. `destructive` взагалі фіксований (hue 25/27).

**Мета.** Кожен токен, який несе колір, має походити від шпалер: hue (два hue — основний і другий з
палітри), chroma поверхонь, трохи lightness, і навіть семантичний `destructive`.

## Рішення (узгоджено з користувачем)

- Рівень тонування — **B medium** (`neutral_gain 0.90`, cap `0.055`), а не A/C.
- Тонується **все**, включно з `background` вікна (не лише card/secondary).
- Light mode теж тонується: `background` не чисто білий, а світло-кольоровий (`#eaf8d8` для зелених
  шпалер, `#f2efff` для фіолетових).
- `lightness` — злегка адаптивний (L домінантного кластера зсуває поверхні в межах ±0.018).
- Другий hue з палітри — для `secondary`/`muted`/`accent`/`ring`.
- `destructive` — червоний, підфарбований до ±12° в бік hue шпалер.
- Явність акцента **тюниться параметрами** (п. 6): дефолт — B medium, але strength можна змінити
  без переписування `ThemeScale`.

## Що є зараз (факти)

| Що | Де | Значення |
| --- | --- | --- |
| нейтралі | `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/theme/ThemeScale.kt:22-26` | `NEUTRAL_CHROMA = 0.008f`, `TINT_CHROMA = 0.016f` |
| бренд | `ThemeScale.kt:29-32` | `chroma * PRIMARY_GAIN(2.5)`, стеля `0.18`; L `0.705` (dark) / `0.55` (light) |
| поверхні dark | `ThemeScale.kt:32-59` | background L `0.145`, card `0.205`, secondary/muted `0.269`, accent `0.371` |
| поверхні light | `ThemeScale.kt:63-90` | background `0.985`, card `1.0`, secondary/muted `0.955`, accent `0.93` |
| `destructive` | `ThemeScale.kt:50,80` | `oklch(0.704, 0.191, 25)` dark / `oklch(0.577, 0.245, 27)` light |
| border/input dark | `ThemeScale.kt:53-54` | білий, alpha `0.10` / `0.15` |
| палітра | `palette-core/src/lib.rs:158-171` | `colors[]` (k=6, `min_weight=0.03`, sorted by weight), `suggested_hue` (circular weighted mean), `suggested_chroma`, `suggested_mode` |
| DTO | `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/theme/palette/PaletteDto.kt:7-23` | `colors` уже приходять у Kotlin (`ColorJson.l/c/h/weight`), але не використовуються |
| сервіс | `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/theme/ThemeService.kt:41-69` | тримає лише `paletteHue`, `paletteChroma`, `paletteSuggestsDark` |
| API | `ThemeModels.kt:24-33` | `DesignTokens.fromPalette(hue, chroma, isDark)` |

**Ключовий факт:** `suggested_chroma` — це зважене середнє по хроматичних кластерах, тому воно дрібне
навіть для насичених шпалер (наші 6 шпалер: 0.008…0.092). Якщо множити на нього — поверхні лишаються
майже сірими. Тому за основу тону береться **family chroma** — максимальна chroma кластера в родині
домінантного hue (|Δh| ≤ 30°): 0.010…0.187 на тих самих шпалерах.

## Приклади (згенеровано з реальних шпалер)

Скрипт повторює `palette-core` (k=8/6, `max_dim=64`, `min_weight=0.03`, OKLab k-means) і формули
нижче; рендер у `/tmp/kn-palette-mock/` (не в репо, `git` не чіпає):

- `1__variants.png`, `wallhaven-6l2rk7__variants.png`, `wallhaven-jevdow__variants.png`,
  `wallhaven-k8kgz1__variants.png`, `wallhaven-yxy8zk__variants.png`, `wallpaper__variants.png` —
  кожна шпалера × 3 варіанти (A subtle / B medium / C strong).
- `*__light.png` (yxy8zk, jevdow, 1.jpg) — примусово light mode.
- `_all_tokens.png` — усі 19 токенів з hex для 6 шпалер × 3 варіантів.
- `tokens.json` — числа (l, c, h, hex) по кожному токену.

Спостереження з рендерів:

- Усі 6 шпалер дають `suggested_mode = dark`, тому dark — основний кейс.
- `wallhaven-6l2rk7` → bg `#000e22`, card `#051b33`; `jevdow` (зелена) → `#091000` / `#131e00`;
  `yxy8zk` (фіолетова) → `#0f0722` / `#1c1330`; `wallpaper.png` → `#080a24` / `#131733`.
- `1.jpg` майже монохромна (family chroma 0.021) → лишається майже чорною; це чесно, а не баг.
- Другий hue (≥ 40° від домінантного) є лише в `k8kgz1` (зелений 104° на фоні синього 229°) і `1.jpg`
  (301° на фоні 252°).
- Light mode: `background` лишається майже білим, а `secondary`/`accent`/`ring` помітно тонуються.

## Approach

### 1. `WallpaperPalette` — вхід теми замість двох чисел

Новий файл `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/theme/WallpaperPalette.kt`:

```kotlin
data class PaletteSwatch(val l: Float, val c: Float, val h: Float?, val weight: Float)

data class WallpaperPalette(
    val hue: Float,          // suggested_hue або NEUTRAL_HUE (250f)
    val chroma: Float,       // suggested_chroma
    val swatches: List<PaletteSwatch>,
) {
    /** Максимальна chroma кластера в родині домінантного hue (|Δh| ≤ 30°). */
    val familyChroma: Float
    /** Hue найвагомішого кластера, що відрізняється від основного на ≥ 40° (або null). */
    val secondHue: Float?
}
```

`familyChroma` — це і є «наскільки насиченим буває цей hue на цих шпалерах». Воно не залежить від
площі, тому не тоне в сірому, але й не вигадує колір, якого на шпалерах немає.

`ThemeService` мапить `PaletteJson` → `WallpaperPalette` один раз і тримає його для `setMode()`
(замість `paletteHue`/`paletteChroma`). `DesignTokens.fromPalette(palette, isDark)`.

### 2. Тонування поверхонь (модель B medium — затверджена)

```
family   = palette.familyChroma                       // 0…0.19
neutralC = clamp(family * 0.90, floor 0.015, cap 0.055)
secondC  = clamp(family2 * 0.90 * 1.25, floor, 0.0715)   // family2 — родина другого hue
tintC    = clamp(family * 1.10, neutralC * 1.5, cap 0.070)  // accent, ring, mutedForeground
brand    = clamp(max(chroma, family * 0.8) * 2.5, 0, 0.18)  // без змін у логіці
```

- `background`, `card` — `neutralC` в основному hue.
- `secondary`, `muted` — `secondC` в другому hue (якщо він є, інакше основний).
- `accent` (hover wash) — `tintC`; має читатися як колір, а не як сірий.
- `ring` — `tintC * 1.8`, стеля `0.13`: focus ring — найнасиченіший елемент після `primary`.
- `mutedForeground` — `tintC`, але не більше `0.05`, щоб текст не «фарбувався».
- `border`/`input` (dark) — лишаються напівпрозорими, але тонованими: `neutralC * 1.4/1.6`,
  alpha `0.22 / 0.28` (зараз білі 0.10/0.15). У light — суцільні `neutralC`.
- Achromatic шпалера (`family == 0`) → `neutralC = tintC = 0`, тобто справжній нейтральний UI,
  без вигаданого кольору.

Light mode (той самий family, інші коефіцієнти — фон теж тонується):

```
background.L = 0.975 - 0.013 + pull * 0.12,  C = neutralC * 0.80
card.L       = 0.998 - 0.006,                C = neutralC * 0.45
secondary.L  = 0.945 - 0.020 + pull * 0.15,  C = secondC * 1.05
accent.L     = 0.915 - 0.011,                C = tintC   * 0.95
```

Перевірено рендером: `jevdow` → bg `#eaf8d8`, card `#fafff3`, secondary `#e7f6d3`, accent `#d4e8b8`;
`yxy8zk` → bg `#f2efff`, card `#fdfcff`, accent `#e1d9ff`. Картка лишається майже білою, тому текст
на ній читається так само, як зараз.

### 3. Lightness: трохи адаптивний

```
pull = clamp((dominantL - 0.5) * 0.036, -0.018, 0.018)   // L домінантного кластера
lift = +0.022  (dark) / -0.020 (light)                   // щоб chroma було видно при низькому L
background.L = 0.145 + lift + pull * 0.6
card.L       = 0.205 + lift * 0.9 + pull * 0.7
secondary.L  = 0.269 + lift * 1.1 + pull * 0.8
accent.L     = 0.371 + lift * 1.3 + pull
```

Смуга навколо базових L зафіксована, тому «дуже темна» шпалера не провалює UI в чорне, а світла не
робить поверхні нечитабельними.

### 4. `destructive`: червоний, підфарбований

```
delta = shortestSignedAngle(wallHue, 25°)
pull  = 0 if |delta| > 90° else 0.35 * delta           // холодні шпалери не рухають error
hDest = 25° + clamp(pull, -12°, +12°)
cDest = clamp(chroma * 1.5, 0.15, 0.21)   (dark)  /  clamp(chroma * 1.6, 0.17, 0.245) (light)
```

Тобто `destructive` лишається в червоній зоні (≤ 12° зсуву), але не виглядає як чужий елемент.

### 5. Gamut fitting

При L ≈ 0.16 і високій chroma багато OKLCH-кольорів поза sRGB, а `Color.oklch` затискає канали
поштучно — це зсуває hue і вбиває насиченість (найгірше для зеленого). Додаємо в
`framework/src/nativeMain/kotlin/dev/knshell/compose/Color.kt`:

```kotlin
fun Color.Companion.oklchFitted(l: Float, c: Float, h: Float, alpha: Float = 1f): Color
// бінарний пошук максимальної chroma, що лишається в [0,1] по кожному linear-каналу
```

і в `ThemeScale` всі кольори створюються через нього. `Color.oklch` не чіпаємо (сумісність).

### 6. Тюнінг явності акцента (параметри, не константи)

Зашиті `neutral_gain 0.90 / tint_gain 1.10 / cap 0.055` — це лише **дефолт**. Щоб «зробити
кольоровіше» не означало правити `ThemeScale`, усі числа деривації збираються в один value-обʼєкт:

```kotlin
// shell-app/.../theme/ThemeTuning.kt
data class ThemeTuning(
    /** Множник хроматичності поверхонь (0 = звичайний чорний/білий UI). */
    val tint: Float = 1f,              // 0f..2f, дефолт 1f == B medium
    /** Множник хроматичності primary/ring: акцент «світиться», поверхні — ні. */
    val accent: Float = 1f,            // 0f..2f
    /** Підйом lightness поверхонь. У dark це головний важіль: gamut sRGB обмежує chroma. */
    val lift: Float = 0.022f,          // 0f..0.05f
    /** Стелі хроматичності, щоб насичена шпалера не зробила UI «пластиковим». */
    val neutralCap: Float = 0.055f,
    val accentCap: Float = 0.070f,
    /** Тягнути secondary/accent у другий hue палітри (0 = усе в основному hue). */
    val secondHue: Float = 1f,         // 0f..1f
) {
    companion object {
        val Balanced = ThemeTuning()
        val Off = ThemeTuning(tint = 0f)                                              // старий вигляд
        val Subtle = ThemeTuning(tint = 0.55f, accent = 0.85f, neutralCap = 0.030f)    // ≈ колишній A
        val Vivid = ThemeTuning(tint = 1.55f, accent = 1.35f, lift = 0.045f,
                                neutralCap = 0.090f, accentCap = 0.100f)
        fun preset(name: String): ThemeTuning?
    }
}
```

`ThemeScale.dark(palette, tuning)` / `light(palette, tuning)` множать `neutralC`/`tintC`/`brand` на
`tuning.tint`/`tuning.accent`, затискають у `neutralCap`/`accentCap` і додають `tuning.lift` до L
замість константи `SURFACE_LIFT`. `DesignTokens.fromPalette(palette, isDark, tuning)` має
`tuning = ThemeTuning.Balanced` за замовчуванням, тож наявні виклики не ламаються.

**Чому в тюнінгу є `lift`, а не тільки `tint`.** У dark поверхні живуть на L ≈ 0.15–0.23, а gamut sRGB
там жорстко обмежує chroma (виміряно `fit_chroma` для наших hue):

| hue шпалери | maxC @ L 0.14 | @ L 0.167 | @ L 0.24 | @ L 0.30 |
| --- | --- | --- | --- | --- |
| 126° (`jevdow`, зелений) | 0.035 | 0.042 | 0.060 | 0.075 |
| 253° (`6l2rk7`, синій) | 0.043 | 0.051 | 0.073 | 0.091 |
| 295° (`yxy8zk`, фіолет) | 0.076 | 0.090 | 0.130 | 0.162 |

Тобто `neutralCap 0.055` для зеленого/синього в dark уже впирається в стіну: слайдер `tint` після
~1.0 перестає щось міняти. Тому `lift` піднімає L разом із chroma — інакше «зробити явніше» працює
лише на фіолетових/світлих шпалерах. Це записується в KDoc `ThemeTuning`, щоб наступний читач не
шукав баг у `tint`.

Канал керування:

- `ThemeService.setTuning(tuning: ThemeTuning)` — пере-деривує з кешованої палітри, як `setMode()`
  (`ThemeService.kt:49`), без повторного запуску `palette-core`.
- Персистентності **немає**: у репо немає жодного завантажувача конфігу (`ThemeMode` теж живе лише
  в памʼяті). Тому явність акцента цього разу — runtime-API + слайдер у демо; збереження в
  `~/.config/kn-shell/theme.toml` — окремий follow-up, щоб не тягнути конфіг-шар у цю зміну.
- Живий тюнінг у демо: `shell-app/.../ui/test.ui.kt` — слайдер `tint` 0…2 + пресети
  `Off/Subtle/Balanced/Vivid`, поряд із рядком `hue/family` (див. Files to modify). Це і стенд, і
  спосіб підібрати кращий дефолт на реальних шпалерах.

Орієнтири (dark, `jevdow`, surface L 0.167 / card 0.225): `Off` → `#0f0f0f`/`#1c1c1c` (як зараз,
сірі), `Subtle` → `#0b1104`/`#191e10`, `Balanced` → `#0a1200`/`#162004`, `Vivid` (lift 0.045) →
`#0e1700`/`#192501`. **Інваріант:** тюнінг міняє лише chroma і L поверхонь — hue залишається тим
самим (це й перевіряємо в рядку статусу демо).

## Files to modify

| Файл | Зміна |
| --- | --- |
| `framework/src/nativeMain/kotlin/dev/knshell/compose/Color.kt` | `Color.Companion.oklchFitted` (gamut-aware) |
| `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/theme/WallpaperPalette.kt` | новий: `PaletteSwatch`, `WallpaperPalette`, `familyChroma`, `secondHue` |
| `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/theme/ThemeTuning.kt` | новий: `ThemeTuning`, пресети `Off/Subtle/Balanced/Vivid` |
| `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/theme/ThemeScale.kt` | нова деривація (п. 2–4), параметри з `ThemeTuning` замість констант |
| `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/theme/ThemeModels.kt` | `fromPalette(palette, isDark)`, `default()`/`fallback` |
| `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/theme/ThemeService.kt` | мапити `PaletteJson` → `WallpaperPalette`, тримати його для `setMode`; `setTuning()` + `StateFlow<ThemeTuning>` |
| `shell-app/src/linuxX64Main/kotlin/dev/knshell/app/ui/test.ui.kt` | у рядку статусу `hue/secondHue/familyChroma`; слайдер `tint` 0…2 + пресети `Off/Subtle/Balanced/Vivid` |
| `docs/theme.md` | оновити «Derivation rules», таблиця `ThemeTuning` (що множить, межі, пресети) і правило family chroma |
| `docs/color-tokens.md` | пастки #5/#6/#7 переписати: `destructive` підфарбований, нейтралі тонуються, `secondHue` |

Rust (`palette-core`) **не змінюється**: усі кластери вже приходять у Kotlin.

## Reuse

- `PaletteJson.colors` / `ColorJson` (`palette/PaletteDto.kt`) — джерело кластерів, парсинг уже є.
- `dev.knshell.compose.Color.oklch` — та сама матриця OKLab→linear sRGB, на ній будується
  `oklchFitted` (без дублювання констант).
- `ThemeTokens.entries()` — свотчі в демо підхоплять нові кольори автоматично.
- `ThemeService.setMode()` — уже пере-деривує з кешу палітри; треба лише кешувати `WallpaperPalette`.
- `NEUTRAL_HUE = 250f` (`ThemeService.kt:34`) — лишається fallback для achromatic шпалер.

## Steps

- [x] `Color.oklchFitted` + короткий тест у `framework/src/nativeTest` (round-trip: fitted колір
      ніколи не виходить за gamut, hue не змінюється).
- [x] `WallpaperPalette` + `familyChroma`/`secondHue`, з KDoc про те, чому family, а не mean chroma.
- [x] `ThemeTuning` (п. 6) + пресети `Off/Subtle/Balanced/Vivid`;
      `ThemeScale.dark/light(palette, tuning)` з множниками `tint`/`accent`, стелями
      `neutralCap`/`accentCap` і `tuning.lift` замість `SURFACE_LIFT`; решта констант
      (`LIGHTNESS_PULL`, `SECOND_HUE_MIN_DELTA`, `DESTRUCTIVE_PULL`) — private const у `ThemeScale`.
- [x] `ThemeModels.fromPalette(palette, isDark)`; `default()` — `WallpaperPalette.achromatic`.
- [x] `ThemeService`: мапити DTO, кешувати `WallpaperPalette`, `setTuning()` + `tuning: StateFlow`.
- [x] `test.ui.kt`: рядок статусу з hue/h₂/family, слайдер `tint` + пресети — тюнінг без рестарту.
- [x] `docs/theme.md`, `docs/color-tokens.md`.
- [x] Прогнати рендер-скрипт на 6 шпалерах ще раз і порівняти з фінальними числами Kotlin.

## Verification

- [x] `./gradlew :shell-app:linkDebugExecutableLinuxX64 --console=plain` — компіляція (те саме, що
      робить `scripts/dev.sh:113`).
- [x] `./gradlew :framework:linuxX64Test` — новий тест gamut fitting.
- [x] `./scripts/run.sh` на кожній з 6 шпалер (`~/Pictures/wallpapers`), скрін демо-таби:
      - поверхні не чорні, але текст читається (контраст `foreground`/`mutedForeground`);
      - `accent` hover видно як колір, `primary` не «світиться»;
      - `k8kgz1` — secondary в іншому hue, ніж primary;
      - `1.jpg` — майже нейтральний, без вигаданого кольору.
- [x] Light mode (`ThemeMode.Light`): `background` майже білий, `secondary`/`accent` тоновані,
      `destructive` читається.
- [x] `awww img <other>` → shell перефарбовується без рестарту.
- [x] Слайдер `tint`: `0` дає рівно колишній нейтральний вигляд (`NEUTRAL_CHROMA`-рівень), `1` — B
      medium, `2` — насичені поверхні; при русі слайдера **hex змінюється лише за chroma** —
      hue/L у рядку статусу не стрибають.

## Очікувані значення (B medium, dark, з рендерів)

| Шпалера | h | h₂ | background | card | primary | secondary | accent |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `1.jpg` | 254° | — | `#080e15` | `#141a22` | `#8ca2be` | `#232b35` | `#3b4653` |
| `wallhaven-6l2rk7.jpg` | 253° | — | `#000e22` | `#051b33` | `#64a4ec` | `#0c2b4c` | `#28476a` |
| `wallhaven-jevdow.png` | 126° | — | `#091000` | `#131e00` | `#88b03c` | `#213001` | `#3b4c20` |
| `wallhaven-k8kgz1.jpg` | 229° | 104° | `#070f13` | `#131c20` | `#93a3aa` | `#2d2b15` | `#39484f` |
| `wallhaven-yxy8zk.jpg` | 295° | — | `#0f0722` | `#1c1330` | `#a987fe` | `#2c2149` | `#473c65` |
| `wallpaper.png` | 275° | — | `#080a24` | `#131733` | `#8998f0` | `#20264d` | `#3a416a` |

Ці числа — орієнтир для ручної перевірки після реалізації: якщо Kotlin дає інший hex, різниця
в деривації (а не в рендер-скрипті).
