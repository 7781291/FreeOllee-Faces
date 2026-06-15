# Super FreeOllee — Brand Design Implementation

**Date:** 2026-06-15
**Status:** Approved design, ready for planning
**Source:** Claude Design bundle `super-freeollee-design-system` (handle `stF7nQ9cR2jiZQgqAVTn_A`)

## Summary

Apply the "Super FreeOllee" design system — an **Everforest-dark** brand layer with a
friendly **Nunito** UI, a **7-segment LCD** readout motif, a bottom tab bar, and a
SUPER FREE wordmark — to the existing Compose Multiplatform Android app.

The app today renders **default Material 3 (light)**: `themes.xml` uses
`Theme.Material.Light.NoActionBar` and `FreeOlleeFacesTheme` is a no-op wrapper around
`MaterialTheme()`. Crucially, **every screen already consumes
`MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*`**, so populating the theme
re-skins the whole app (Home/Complications, Alarms, Timers, Settings) without touching
screen logic. Brand accents (LCD readout, bottom nav, launcher icon, wordmark) layer on top.

## Decisions (locked with the user)

- **Scope:** Everything — foundation theme + LCD readout + launcher icon + wordmark +
  bespoke component polish.
- **Fonts:** Bundle **Nunito + DSEG7 Classic + JetBrains Mono** only. Render the
  **SUPER FREE wordmark as an image** (Anton + Permanent Marker are used only by a
  Pi-side generator script, not bundled into the app).
- **Navigation:** Adopt the design's **persistent bottom navigation bar**
  (Complications / Alarm / Timer / Settings) with a per-screen AppBar title.
  `TimerSetEdit` stays a pushed sub-screen with a back arrow.

## Non-goals

- No new feature behavior — purely a brand/visual + navigation-shell change.
- No iOS/desktop targets (Android is the only built target today).
- Not reproducing the design system's React component library 1:1 — recreate the
  *visual output* in idiomatic Compose, per the bundle README.

---

## Unit 1 — Fonts (Compose resources)

**What it does:** Provides the three bundled font families as Compose Multiplatform resources.

- Source files live in the bundle at
  `super-freeollee-design-system/project/assets/fonts/*.woff2`.
- Android/Compose's font loader does **not** accept woff2, so convert woff2 → ttf using
  `fonttools` (Python; install into the repo `.venv` if not present).
- Place the ttf files in **`app/src/commonMain/composeResources/font/`**:
  - `nunito_regular.ttf` (400), `nunito_semibold.ttf` (600), `nunito_bold.ttf` (700),
    `nunito_black.ttf` (800)
  - `dseg7_regular.ttf` (400), `dseg7_bold.ttf` (700)
  - `jetbrainsmono_regular.ttf` (400), `jetbrainsmono_bold.ttf` (700)
- The Compose resources Gradle plugin (already on the classpath via
  `compose.components.resources`) generates the `Res` accessor.
- Define `FontFamily` values in `ui/theme/Type.kt`: `Nunito`, `Dseg7`, `JetBrainsMono`,
  each built from `Font(Res.font.…, weight = …)` entries. These must be created inside a
  `@Composable` (or via `FontFamily(Font(...))` with resource APIs) per the Compose
  resources font API.

**License note:** Nunito, DSEG7 Classic, JetBrains Mono are all SIL OFL — bundling and
redistribution under GPL-3.0 is fine.

**Done when:** `Res.font.*` resolves and a smoke composable can render text in each family.

## Unit 2 — Color theme (`ui/theme/Color.kt`, `ui/theme/Theme.kt`)

**What it does:** Replaces the no-op theme with a real Everforest `darkColorScheme`.

`Color.kt` declares the raw Everforest palette (named to match `tokens/colors.css`).
`Theme.kt` builds `darkColorScheme(...)` with this mapping:

| M3 role | Token | Hex |
|---|---|---|
| `primary` / `onPrimary` | ef-green / canvas | `#a7c080` / `#232a2e` |
| `primaryContainer` / `onPrimaryContainer` | ef-bg-green / | `#3c4841` / `#cbe0a6` |
| `secondary` / `onSecondary` | ef-aqua / canvas | `#83c092` / `#232a2e` |
| `tertiary` / `onTertiary` | ef-blue / canvas | `#7fbbb3` / `#232a2e` |
| `background` / `onBackground` | canvas / fg | `#232a2e` / `#d3c6aa` |
| `surface` / `onSurface` | bg0 / fg | `#2d353b` / `#d3c6aa` |
| `surfaceVariant` / `onSurfaceVariant` | bg1 / grey2 | `#343f44` / `#9da9a0` |
| `surfaceContainer*` (low→high) | bg-dim→bg2 | `#232a2e` → `#3d484d` |
| `error` / `onError` / `errorContainer` / `onErrorContainer` | ef-red / canvas / bg-red / | `#e67e80` / `#232a2e` / `#543a48` / `#e8e0cc` |
| `outline` / `outlineVariant` | border / border-subtle | `#475258` / `#3d484d` |
| `scrim` | scrim | `rgba(20,24,26,.66)` |

`FreeOlleeFacesTheme` wires the scheme + `Typography` (Unit 3) into `MaterialTheme`.
Keep the function signature `FreeOlleeFacesTheme(content: @Composable () -> Unit)` so
`MainActivity` is unaffected.

**Effect:** The active `ComplicationCard` / `TimerSetCard` (already `primaryContainer` +
2dp `primary` border) render the green wash automatically. The `ConnectionRow` colors
(`primary` / `onSurfaceVariant` / `error`) become Everforest-correct with no edit.

**Brand extras:** A small `object BrandColors` (in `Color.kt`) exposes LCD values not in
the M3 scheme: `lcdScreen = #1a1f1c`, `lcdOn = #a7c080`, `lcdOnAqua = #83c092`,
`lcdOff = a7c080 @ 10%`, plus the marker red `#f85552` (wordmark only).

**Done when:** App builds, launches dark, and all four screens read as Everforest.

## Unit 3 — Typography (`ui/theme/Type.kt`)

**What it does:** Nunito across the M3 type scale, mapping `tokens/typography.css` onto the
named styles the screens already reference.

| M3 style | size/line-height | weight | usage in app |
|---|---|---|---|
| `displaySmall` | 40/44 | 800 | (available) |
| `headlineLarge` | 32/38 | 800 | (available) |
| `headlineMedium` | 28/34 | 700 | big preview values (`FaceValue`, notifications count) |
| `headlineSmall` | 24/30 | 800 | residual screen titles |
| `titleLarge` | 22/28 | 800 | AppBar titles, section headers |
| `titleMedium` | 18/24 | 700 | card titles |
| `titleSmall` | 16/22 | 700 | sub-headers |
| `bodyLarge` | 16/22 | 600 | row labels |
| `bodyMedium` | 15/22 | 400 | body/helper text |
| `bodySmall` | 13/18 | 400 | captions, "Updated …", "Next: …" |
| `labelLarge` | 14/20 | 700 | button labels |
| `labelMedium` | 12/16 | 700 | eyebrow (`SectionLabel`) |
| `labelSmall` | 11/15 | 700 | tiny labels |

All families default to `Nunito`. The `SectionLabel` composable in `HomeScreen.kt` (and
any duplicate in other screens) is updated to render **UPPERCASE + 0.08em letterSpacing +
primary color** to match the design's tracked eyebrows.

**Done when:** Text renders in Nunito at the design weights; eyebrows are uppercase/tracked.

## Unit 4 — LCD readout (`ui/LcdReadout.kt`) + application

**What it does:** A reusable brand composable recreating `components/core/LcdReadout.jsx`.

- Signature: `LcdReadout(value: String, modifier: Modifier = Modifier, size: LcdSize = LcdSize.Lg, tone: LcdTone = LcdTone.Green)`.
- Renders `value` in the **DSEG7** family, color `BrandColors.lcdOn` (or `lcdOnAqua`),
  inside a `#1a1f1c` well (`RoundedCornerShape(14.dp)`, padding 10×16dp, a subtle inset
  shadow approximated with a dark inner border / low elevation).
- **Glow** is approximated with `TextStyle(shadow = Shadow(color = lcdOn, blurRadius ≈ 10f))`.
- Sizes from `typography.css`: `Md` 22sp, `Lg` 34sp, `Xl` 56sp.
- The "ghost" all-segments-lit underlay from the JSX is **optional/omitted** in v1 unless
  it renders cleanly — flag if dropped. (DSEG7's own design conveys the segment look.)

**Application:** In `HomeScreen.FaceValue`, replace the plain monospace
`Watch: '${preview.payload}'` line with an `LcdReadout(value = preview.payload)` (the
segmented hero). The human-readable value above it stays Nunito (`headlineMedium`). Any
*other* literal-payload strings elsewhere keep **JetBrains Mono** via a
`FontFamily.JetBrainsMono` (not LCD).

**Done when:** The Complications cards show the watch payload as a green segmented readout
in a dark well.

## Unit 5 — Navigation (bottom nav + AppBar)

**What it does:** Replaces header-button navigation with a persistent bottom tab bar and a
shared AppBar, matching `ui_kits/mobile-app/App.jsx`.

- **`MainActivity` `Scaffold`:** add a `bottomBar = { … }` rendering a Material 3
  `NavigationBar` with 4 `NavigationBarItem`s:
  - Complications (`▦`) → `Screen.Home`
  - Alarm (`⏰`) → `Screen.Alarms`
  - Timer (`⏱`) → `Screen.TimerSets`
  - Settings (`⚙`) → `Screen.Settings`
  - Item label font = Nunito 800 11sp; selected uses `primary` on `primaryContainer`
    indicator (Material default with our scheme).
  - The bottom bar shows on the four top-level screens and is **hidden** on
    `Screen.TimerSetEdit` (a pushed child of Timer that keeps its own back arrow).
- **VM/`Screen`:** tab taps call the existing `viewModel.navigateTo(...)` with the matching
  `Screen`; entering Alarms/Timers also calls the existing `refreshAlarms()` /
  `refreshTimers()` (preserve current side effects). No new persisted state. The
  `Screen` enum is unchanged.
- **Shared `AppBar` composable** (`ui/AppBar.kt`): `AppBar(title, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {})` — recreates `navigation/AppBar.jsx`
  (min-height 56dp, title Nunito-800 22sp `onSurface`/`text-bright`, optional `‹` back,
  trailing actions). Use Material 3 `TopAppBar` underneath if it themes cleanly; otherwise
  a hand-rolled `Row`.
- **Screen header refactor:** each top-level screen drops its hand-rolled header row and
  uses `AppBar`:
  - `HomeScreen`: remove the "Complications" + Timers/Alarms text-buttons + ⚙ row →
    `AppBar(title = "Complications")` (no actions; nav is the bottom bar). The
    "Update active now" footer button stays.
  - `AlarmsScreen`, `TimerSetsScreen`, `SettingsScreen`: replace their header rows
    (currently `headlineSmall` title + back) with `AppBar(title = …)`; since they are now
    tabs, **drop the back arrow** on these three (back is the bottom bar).
  - `TimerSetEditScreen`: keep `AppBar(title = "Edit set", onBack = …)` — still a pushed
    sub-screen.
- `BondedDevicesDialog` is unchanged (modal dialog with its own dismiss).

**Done when:** All four tabs reachable via the bottom bar; titles render via AppBar;
TimerSetEdit still pushes/pops with back; existing refresh side effects preserved.

## Unit 6 — Brand assets (launcher icon + wordmark)

**Launcher icon** (`app/src/androidMain/res/`):

- No custom launcher icon exists today (manifest sets no `android:icon`).
- Generate density-bucket PNGs from `assets/app-icon-1024.png`:
  `mipmap-mdpi` 48, `-hdpi` 72, `-xhdpi` 96, `-xxhdpi` 144, `-xxxhdpi` 192
  (square `ic_launcher.png` + `ic_launcher_round.png`).
- Add an **adaptive icon** (`mipmap-anydpi-v26/ic_launcher.xml`): `background` = solid
  `#232a2e`, `foreground` = the icon art scaled into the adaptive safe zone (drawable from
  the 1024 art). Provide a matching `ic_launcher_round.xml`.
- Wire `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"`
  in `AndroidManifest.xml`.
- **Splash fix:** change `themes.xml` from `Theme.Material.Light.NoActionBar` to a dark
  NoActionBar parent (e.g. `Theme.Material.NoActionBar`) with
  `<item name="android:windowBackground">#232a2e</item>` so launch isn't a white flash.

**Wordmark** (Compose resource + Settings About card):

- Pi-side generator script (`tools/generate_wordmark.py`, Python + Pillow) draws the
  **SUPER FREE** lockup to a transparent PNG using the Anton (SUPER, `#a7c080`) and
  Permanent Marker (FREE, `#f85552` + double underline) ttf converted from the bundle
  woff2. Mirrors `guidelines/brand/brand-wordmark.html`.
- Output bundled at `app/src/commonMain/composeResources/drawable/wordmark_super_free.png`
  (provide @2x/@3x density variants or a single high-res PNG scaled by Compose).
- Shown in an **About card** at the bottom of `SettingsScreen`: the wordmark image, the
  existing version label, and the GPL-3.0 / "Not affiliated with Ollee" line.

**Done when:** Launcher shows the SUPER FREE icon, launch background is dark, and Settings
shows the wordmark in an About card.

---

## Testing & verification

- **Unit tests:** Logic is thin (visual change). Add a focused test only if Unit 5
  introduces any tab↔Screen mapping function worth isolating; otherwise rely on the
  existing suite staying green (`./gradlew :app:testDebugUnitTest`).
- **Build gate:** `./gradlew :app:assembleDebug` must compile clean (fonts, resources,
  theme, nav all wired).
- **Visual confirmation:** This is a visual change and **cannot be verified blind**. After
  a clean build, ask the user to switch to **VNC** (RealVNC on iPad, per environment) and
  do a screenshot pass across the four tabs + TimerSetEdit before claiming visual
  correctness. Do not assert pixel-fidelity from code alone.

## Risks / caveats

- **woff2 → ttf conversion** must round-trip the glyphs the app uses (DSEG7's special
  lowercase, JetBrains Mono symbols). Verify rendered output, not just that the file loads.
- **LCD glow** in Compose is an approximation (`Shadow`), not a true outer glow; acceptable
  per the design's "soft green glow" intent.
- **Bottom-nav refactor** is the most invasive change — touches `MainActivity`, all four
  top-level screen headers, and removes the Home gear/text-buttons. Mechanical but broad;
  keep each screen's existing callbacks intact.
- **Adaptive icon safe zone:** the icon art is a framed plate; confirm it isn't clipped by
  the circular/rounded mask on device (may need a background-color layer + inset foreground).
- **No workday pushes** (per user rule, 9–5 MT weekdays): commit locally freely; defer any
  push/release to outside that window.

## File-level change inventory

- **New:** `ui/theme/Color.kt`, `ui/LcdReadout.kt`, `ui/AppBar.kt`,
  `commonMain/composeResources/font/*.ttf`,
  `commonMain/composeResources/drawable/wordmark_super_free.png`,
  `tools/generate_wordmark.py`,
  `androidMain/res/mipmap-*/ic_launcher*.png`,
  `androidMain/res/mipmap-anydpi-v26/ic_launcher*.xml`.
- **Modified:** `ui/theme/Theme.kt`, `ui/theme/Type.kt` (new content),
  `MainActivity.kt` (bottomBar + icon wiring), `HomeScreen.kt`, `AlarmsScreen.kt`,
  `TimerSetsScreen.kt`, `SettingsScreen.kt`, `TimerSetEditScreen.kt` (AppBar),
  `androidMain/res/values/themes.xml`, `AndroidManifest.xml`.
