# Super FreeOllee Brand Design Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the Super FreeOllee design system (Everforest-dark Material 3 theme, Nunito type, DSEG7 LCD readout, bottom-nav shell, launcher icon, SUPER FREE wordmark) to the Compose Multiplatform Android app.

**Architecture:** Populate the existing no-op `FreeOlleeFacesTheme` with a real `darkColorScheme` + Nunito `Typography` so all screens (which already read `MaterialTheme.*`) re-skin automatically; then layer brand accents — an `LcdReadout` composable, a bottom `NavigationBar` + shared `AppBar`, the launcher icon, and a wordmark image — on top.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform 1.8.2, Material 3, Compose Resources (`compose.components.resources`), fontTools (Pi-side woff2→ttf), Pillow (Pi-side wordmark PNG).

**Source bundle (read-only reference):** `/tmp/design_bundle/extracted/super-freeollee-design-system/` — tokens at `project/tokens/`, component specs at `project/components/`, assets at `project/assets/`.

**Prerequisite — bundle must be present.** Tasks 1, 7, and 8 read source fonts/art from the extracted bundle under `/tmp/design_bundle/`. `/tmp` does not survive a reboot. Before starting, verify it exists:
```bash
ls /tmp/design_bundle/extracted/super-freeollee-design-system/project/assets/fonts/Nunito-400.woff2
```
If missing, re-fetch and re-extract: `WebFetch` the design URL `https://api.anthropic.com/v1/design/h/stF7nQ9cR2jiZQgqAVTn_A` (returns a gzip), then `gunzip` → `tar -xf` into `/tmp/design_bundle/extracted/` (the bundle is a gzipped tar of `super-freeollee-design-system/`).

---

## File Structure

**New files:**
- `app/src/commonMain/composeResources/font/*.ttf` — 8 bundled font faces
- `app/src/commonMain/composeResources/drawable/wordmark_super_free.png` — wordmark image
- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/theme/Color.kt` — Everforest palette + `BrandColors`
- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/theme/Type.kt` — font families + `Typography`
- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/LcdReadout.kt` — LCD readout composable
- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AppBar.kt` — shared top bar
- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/BottomNavTab.kt` — tab↔Screen mapping (testable)
- `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ui/BottomNavTabTest.kt`
- `app/src/androidMain/res/mipmap-*/ic_launcher.png`, `ic_launcher_round.png`
- `app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`
- `app/src/androidMain/res/drawable/ic_launcher_foreground.png` (or density variants)
- `app/src/androidMain/res/values/ic_launcher_background.xml`
- `tools/generate_wordmark.py` — Pi-side wordmark generator

**Modified files:**
- `app/build.gradle.kts` — `compose.resources {}` config block
- `ui/theme/Theme.kt` — real `darkColorScheme` + `Typography`
- `MainActivity.kt` — Scaffold restructure with conditional `bottomBar`; icon wiring is in manifest
- `ui/HomeScreen.kt` — AppBar, LcdReadout in `FaceValue`, `SectionLabel` uppercase
- `ui/AlarmsScreen.kt`, `ui/TimerSetsScreen.kt`, `ui/SettingsScreen.kt` — AppBar headers
- `ui/TimerSetEditScreen.kt` — AppBar with back
- `androidMain/res/values/themes.xml` — dark parent + window background
- `androidMain/AndroidManifest.xml` — `android:icon` / `android:roundIcon`

---

### Task 1: Bundle fonts as Compose resources

**Files:**
- Create: `app/src/commonMain/composeResources/font/nunito_regular.ttf`, `nunito_semibold.ttf`, `nunito_bold.ttf`, `nunito_black.ttf`, `dseg7_regular.ttf`, `dseg7_bold.ttf`, `jetbrainsmono_regular.ttf`, `jetbrainsmono_bold.ttf`
- Modify: `app/build.gradle.kts` (add `compose.resources {}` block)

- [ ] **Step 1: Install fontTools + brotli into the repo venv**

Run:
```bash
cd /home/kbcaron/github/FreeOllee-Faces
.venv/bin/pip install fonttools brotli
```
Expected: `Successfully installed fonttools-… brotli-…` (or "already satisfied").

- [ ] **Step 2: Convert the bundle woff2 → ttf into the resources dir**

Run:
```bash
cd /home/kbcaron/github/FreeOllee-Faces
mkdir -p app/src/commonMain/composeResources/font
SRC=/tmp/design_bundle/extracted/super-freeollee-design-system/project/assets/fonts
DST=app/src/commonMain/composeResources/font
decode() { .venv/bin/python -c "from fontTools.ttLib.woff2 import decompress; decompress('$1','$2')"; }
decode "$SRC/Nunito-400.woff2"        "$DST/nunito_regular.ttf"
decode "$SRC/Nunito-600.woff2"        "$DST/nunito_semibold.ttf"
decode "$SRC/Nunito-700.woff2"        "$DST/nunito_bold.ttf"
decode "$SRC/Nunito-800.woff2"        "$DST/nunito_black.ttf"
decode "$SRC/DSEG7Classic-400.woff2"  "$DST/dseg7_regular.ttf"
decode "$SRC/DSEG7Classic-700.woff2"  "$DST/dseg7_bold.ttf"
decode "$SRC/JetBrainsMono-400.woff2" "$DST/jetbrainsmono_regular.ttf"
decode "$SRC/JetBrainsMono-700.woff2" "$DST/jetbrainsmono_bold.ttf"
ls -la "$DST"
```
Expected: 8 `.ttf` files listed, each non-zero size.

- [ ] **Step 3: Configure the Compose resources class package**

In `app/build.gradle.kts`, add this block at the top level (after the `android { … }` block, before `dependencies { … }`):

```kotlin
compose.resources {
    publicResClass = true
    packageOfResClass = "com.blizzardcaron.freeolleefaces.resources"
    generateResClass = always
}
```

- [ ] **Step 4: Build to generate the `Res` accessor**

Run: `./gradlew :app:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL; generated `Res` class now resolvable at `com.blizzardcaron.freeolleefaces.resources.Res` with `Res.font.nunito_regular` etc. (If Gradle daemon memory is tight on the Pi, expect a slow first run — that's normal.)

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/composeResources/font app/build.gradle.kts
git commit -m "feat(theme): bundle Nunito, DSEG7, JetBrains Mono as Compose font resources"
```

---

### Task 2: Everforest color theme

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/theme/Color.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/theme/Theme.kt`

- [ ] **Step 1: Write `Color.kt` (raw palette + brand extras)**

Create `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/theme/Color.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ui.theme

import androidx.compose.ui.graphics.Color

// Everforest (dark, medium) — mirrors design tokens/colors.css.
internal object Everforest {
    val BgDim = Color(0xFF232A2E)   // app canvas
    val Bg0 = Color(0xFF2D353B)     // card surface
    val Bg1 = Color(0xFF343F44)     // raised surface
    val Bg2 = Color(0xFF3D484D)     // inset / hover / outline-variant
    val Bg3 = Color(0xFF475258)     // border
    val Bg4 = Color(0xFF4F585E)     // strong border

    val BgGreen = Color(0xFF3C4841) // active/selected wash (primaryContainer)
    val BgRed = Color(0xFF543A48)   // error container

    val Fg = Color(0xFFD3C6AA)      // primary text (warm tan)
    val Grey2 = Color(0xFF9DA9A0)   // secondary text
    val TextBright = Color(0xFFE8E0CC)

    val Red = Color(0xFFE67E80)
    val Yellow = Color(0xFFDBBC7F)
    val Green = Color(0xFFA7C080)   // signature accent
    val Aqua = Color(0xFF83C092)
    val Blue = Color(0xFF7FBBB3)

    val OnPrimaryContainer = Color(0xFFCBE0A6)
    val MarkerRed = Color(0xFFF85552) // wordmark only
}

/** LCD readout colors not represented in the Material color scheme. */
object BrandColors {
    val LcdScreen = Color(0xFF1A1F1C)
    val LcdOn = Everforest.Green
    val LcdOnAqua = Everforest.Aqua
    val LcdOff = Everforest.Green.copy(alpha = 0.10f)
    val MarkerRed = Everforest.MarkerRed
}
```

- [ ] **Step 2: Rewrite `Theme.kt` with the dark color scheme**

Replace the entire contents of `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/theme/Theme.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val FreeOlleeColorScheme = darkColorScheme(
    primary = Everforest.Green,
    onPrimary = Everforest.BgDim,
    primaryContainer = Everforest.BgGreen,
    onPrimaryContainer = Everforest.OnPrimaryContainer,
    secondary = Everforest.Aqua,
    onSecondary = Everforest.BgDim,
    tertiary = Everforest.Blue,
    onTertiary = Everforest.BgDim,
    background = Everforest.BgDim,
    onBackground = Everforest.Fg,
    surface = Everforest.Bg0,
    onSurface = Everforest.Fg,
    surfaceVariant = Everforest.Bg1,
    onSurfaceVariant = Everforest.Grey2,
    surfaceContainerLowest = Everforest.BgDim,
    surfaceContainerLow = Everforest.Bg0,
    surfaceContainer = Everforest.Bg0,
    surfaceContainerHigh = Everforest.Bg1,
    surfaceContainerHighest = Everforest.Bg2,
    error = Everforest.Red,
    onError = Everforest.BgDim,
    errorContainer = Everforest.BgRed,
    onErrorContainer = Everforest.TextBright,
    outline = Everforest.Bg3,
    outlineVariant = Everforest.Bg2,
)

@Composable
fun FreeOlleeFacesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FreeOlleeColorScheme,
        typography = freeOlleeTypography(),
        content = content,
    )
}
```

(Note: `freeOlleeTypography()` is added in Task 3. Build will fail to resolve it until then — that's expected; do Task 3 before building.)

- [ ] **Step 3: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/theme/Color.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/theme/Theme.kt
git commit -m "feat(theme): Everforest dark color scheme + brand LCD colors"
```

---

### Task 3: Nunito typography + tracked eyebrow

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/theme/Type.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt` (`SectionLabel`)

- [ ] **Step 1: Write `Type.kt` (font families + Typography)**

Create `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/theme/Type.kt`. The font families and `Typography` are built in `@Composable` context because Compose-resources `Font(...)` is a composable function:

```kotlin
package com.blizzardcaron.freeolleefaces.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.blizzardcaron.freeolleefaces.resources.Res
import com.blizzardcaron.freeolleefaces.resources.dseg7_bold
import com.blizzardcaron.freeolleefaces.resources.dseg7_regular
import com.blizzardcaron.freeolleefaces.resources.jetbrainsmono_bold
import com.blizzardcaron.freeolleefaces.resources.jetbrainsmono_regular
import com.blizzardcaron.freeolleefaces.resources.nunito_black
import com.blizzardcaron.freeolleefaces.resources.nunito_bold
import com.blizzardcaron.freeolleefaces.resources.nunito_regular
import com.blizzardcaron.freeolleefaces.resources.nunito_semibold
import org.jetbrains.compose.resources.Font

@Composable
fun nunitoFamily() = FontFamily(
    Font(Res.font.nunito_regular, FontWeight.Normal),
    Font(Res.font.nunito_semibold, FontWeight.SemiBold),
    Font(Res.font.nunito_bold, FontWeight.Bold),
    Font(Res.font.nunito_black, FontWeight.ExtraBold),
)

@Composable
fun dseg7Family() = FontFamily(
    Font(Res.font.dseg7_regular, FontWeight.Normal),
    Font(Res.font.dseg7_bold, FontWeight.Bold),
)

@Composable
fun jetBrainsMonoFamily() = FontFamily(
    Font(Res.font.jetbrainsmono_regular, FontWeight.Normal),
    Font(Res.font.jetbrainsmono_bold, FontWeight.Bold),
)

@Composable
fun freeOlleeTypography(): Typography {
    val sans = nunitoFamily()
    fun s(size: Int, lh: Int, weight: FontWeight) =
        TextStyle(fontFamily = sans, fontSize = size.sp, lineHeight = lh.sp, fontWeight = weight)
    return Typography(
        displaySmall = s(40, 44, FontWeight.ExtraBold),
        headlineLarge = s(32, 38, FontWeight.ExtraBold),
        headlineMedium = s(28, 34, FontWeight.Bold),
        headlineSmall = s(24, 30, FontWeight.ExtraBold),
        titleLarge = s(22, 28, FontWeight.ExtraBold),
        titleMedium = s(18, 24, FontWeight.Bold),
        titleSmall = s(16, 22, FontWeight.Bold),
        bodyLarge = s(16, 22, FontWeight.SemiBold),
        bodyMedium = s(15, 22, FontWeight.Normal),
        bodySmall = s(13, 18, FontWeight.Normal),
        labelLarge = s(14, 20, FontWeight.Bold),
        labelMedium = s(12, 16, FontWeight.Bold),
        labelSmall = s(11, 15, FontWeight.Bold),
    )
}
```

- [ ] **Step 2: Make `SectionLabel` an uppercase tracked eyebrow**

In `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt`, replace the `SectionLabel` composable (currently around lines 247-255):

```kotlin
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}
```

with:

```kotlin
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.08.em),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}
```

Add the import `import androidx.compose.ui.unit.em` to the same file (alongside the existing `androidx.compose.ui.unit.dp` import).

- [ ] **Step 3: Build to confirm theme + typography resolve**

Run: `./gradlew :app:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL (Task 2's `freeOlleeTypography()` reference now resolves).

- [ ] **Step 4: Run the existing test suite (no regressions)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/theme/Type.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt
git commit -m "feat(theme): Nunito typography scale + uppercase tracked section eyebrow"
```

---

### Task 4: LCD readout composable + apply to watch payload

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/LcdReadout.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt` (`FaceValue`)

- [ ] **Step 1: Write `LcdReadout.kt`**

Create `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/LcdReadout.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blizzardcaron.freeolleefaces.ui.theme.BrandColors
import com.blizzardcaron.freeolleefaces.ui.theme.dseg7Family

enum class LcdSize(val fontSizeSp: Int) { Md(22), Lg(34), Xl(56) }
enum class LcdTone { Green, Aqua }

/** Segmented LCD readout (DSEG7) glowing green in a near-black screen well. */
@Composable
fun LcdReadout(
    value: String,
    modifier: Modifier = Modifier,
    size: LcdSize = LcdSize.Lg,
    tone: LcdTone = LcdTone.Green,
) {
    val lit = if (tone == LcdTone.Aqua) BrandColors.LcdOnAqua else BrandColors.LcdOn
    Text(
        text = value,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BrandColors.LcdScreen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        style = TextStyle(
            fontFamily = dseg7Family(),
            fontSize = size.fontSizeSp.sp,
            color = lit,
            shadow = Shadow(color = lit, offset = Offset.Zero, blurRadius = 10f),
        ),
    )
}
```

- [ ] **Step 2: Use `LcdReadout` for the payload in `FaceValue`**

In `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt`, in `FaceValue`, replace the `PreviewState.Ready` branch (currently around lines 384-390):

```kotlin
        is PreviewState.Ready -> {
            Text(preview.human, style = MaterialTheme.typography.headlineMedium)
            Text(
                "Watch: '${preview.payload}'",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
```

with:

```kotlin
        is PreviewState.Ready -> {
            Text(preview.human, style = MaterialTheme.typography.headlineMedium)
            LcdReadout(value = preview.payload, size = LcdSize.Md)
        }
```

Then remove the now-unused import `import androidx.compose.ui.text.font.FontFamily` from the top of `HomeScreen.kt`.

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/LcdReadout.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt
git commit -m "feat(ui): DSEG7 LCD readout for watch payload previews"
```

---

### Task 5: Bottom-nav tab mapping (TDD) + shared AppBar

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/BottomNavTab.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ui/BottomNavTabTest.kt`
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AppBar.kt`

- [ ] **Step 1: Write the failing test for the tab mapping**

Create `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ui/BottomNavTabTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BottomNavTabTest {
    @Test
    fun forScreen_maps_top_level_screens() {
        assertEquals(BottomNavTab.Complications, BottomNavTab.forScreen(Screen.Home))
        assertEquals(BottomNavTab.Alarm, BottomNavTab.forScreen(Screen.Alarms))
        assertEquals(BottomNavTab.Timer, BottomNavTab.forScreen(Screen.TimerSets))
        assertEquals(BottomNavTab.Settings, BottomNavTab.forScreen(Screen.Settings))
    }

    @Test
    fun forScreen_returns_null_for_pushed_subscreen() {
        assertNull(BottomNavTab.forScreen(Screen.TimerSetEdit))
    }

    @Test
    fun bottom_bar_hidden_only_on_edit_subscreen() {
        assertTrue(BottomNavTab.showsBottomBar(Screen.Home))
        assertTrue(BottomNavTab.showsBottomBar(Screen.Settings))
        assertFalse(BottomNavTab.showsBottomBar(Screen.TimerSetEdit))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.ui.BottomNavTabTest"`
Expected: FAIL — `BottomNavTab` is unresolved (does not compile yet).

- [ ] **Step 3: Write `BottomNavTab.kt`**

Create `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/BottomNavTab.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ui

/** The four top-level tabs in the bottom navigation bar, in display order. */
enum class BottomNavTab(val screen: Screen, val label: String, val glyph: String) {
    Complications(Screen.Home, "Complications", "▦"),
    Alarm(Screen.Alarms, "Alarm", "⏰"),
    Timer(Screen.TimerSets, "Timer", "⏱"),
    Settings(Screen.Settings, "Settings", "⚙");

    companion object {
        /** The tab that should read as selected for a screen, or null if it is not a
         *  top-level tab (e.g. TimerSetEdit, a pushed child of Timer). */
        fun forScreen(screen: Screen): BottomNavTab? = entries.firstOrNull { it.screen == screen }

        /** Whether the bottom navigation bar is shown for this screen. */
        fun showsBottomBar(screen: Screen): Boolean = screen != Screen.TimerSetEdit
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.ui.BottomNavTabTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Write `AppBar.kt`**

Create `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AppBar.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Top app bar: optional back affordance, Nunito-800 title, optional trailing actions.
 *  Recreates the design system navigation/AppBar.jsx. */
@Composable
fun AppBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Text("‹", style = MaterialTheme.typography.headlineSmall)
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack == null) 0.dp else 4.dp),
        )
        actions()
    }
}
```

- [ ] **Step 6: Build**

Run: `./gradlew :app:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/BottomNavTab.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AppBar.kt app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ui/BottomNavTabTest.kt
git commit -m "feat(ui): bottom-nav tab mapping (tested) + shared AppBar"
```

---

### Task 6: Wire the bottom navigation bar + refactor screen headers

**Files:**
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AlarmsScreen.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/SettingsScreen.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetEditScreen.kt`

> Unused imports left behind by these edits (e.g. a now-unused `TextButton`) are warnings, not errors, and won't break the build. Removing them is optional cleanup.

- [ ] **Step 1: Add the imports MainActivity needs**

In `MainActivity.kt`, add these imports alongside the existing `androidx.compose.material3.*` imports:

```kotlin
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import com.blizzardcaron.freeolleefaces.ui.BottomNavTab
```

- [ ] **Step 2: Simplify `setContent` (move the Scaffold into AppRoot)**

Replace this block in `MainActivity.onCreate`:

```kotlin
        setContent {
            FreeOlleeFacesTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { inner ->
                    AppRoot(snackbarHostState, Modifier.padding(inner))
                }
            }
        }
```

with:

```kotlin
        setContent {
            FreeOlleeFacesTheme {
                AppRoot()
            }
        }
```

- [ ] **Step 3: Change the `AppRoot` signature and own the snackbar state**

Replace:

```kotlin
@Composable
private fun AppRoot(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel = remember {
```

with:

```kotlin
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val viewModel = remember {
```

(All the existing `LaunchedEffect`/`DisposableEffect`/launcher/callback code in `AppRoot` is unchanged.)

- [ ] **Step 4: Wrap the screen router in a Scaffold with the bottom bar**

Replace the opening of the routing block. Change:

```kotlin
    when (screen) {
        Screen.Home -> HomeScreen(state = state, callbacks = homeCallbacks, modifier = modifier)
```

to:

```kotlin
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (BottomNavTab.showsBottomBar(screen)) {
                NavigationBar {
                    BottomNavTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = BottomNavTab.forScreen(screen) == tab,
                            onClick = {
                                when (tab) {
                                    BottomNavTab.Alarm -> viewModel.refreshAlarms()
                                    BottomNavTab.Timer -> viewModel.refreshTimers()
                                    else -> {}
                                }
                                viewModel.navigateTo(tab.screen)
                            },
                            icon = { Text(tab.glyph, style = MaterialTheme.typography.titleLarge) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        val modifier = Modifier.padding(inner)
        when (screen) {
            Screen.Home -> HomeScreen(state = state, callbacks = homeCallbacks, modifier = modifier)
```

Keep every other branch of the existing `when (screen)` exactly as written (only its indentation shifts one level deeper since it now lives inside the Scaffold content lambda).

- [ ] **Step 5: Close the Scaffold content lambda after the `when`**

The `when (screen)` block is immediately followed by the `if (showPicker)` block. Add one closing brace to end the Scaffold content lambda. Change:

```kotlin
        }
    }

    if (showPicker) {
        val devices = bondedDevices(context)
```

to:

```kotlin
            }
        }
    }

    if (showPicker) {
        val devices = bondedDevices(context)
```

(The first `}` now closes the `TimerSetEdit` branch, the second closes the `when`, the third closes the Scaffold content lambda. The `if (showPicker)` dialog stays outside the Scaffold as an overlay.)

- [ ] **Step 6: Replace the HomeScreen header with an AppBar**

In `HomeScreen.kt`, replace the header row + divider (currently lines ~65-80):

```kotlin
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Complications", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = callbacks.onOpenTimerSets) { Text("Timers") }
                TextButton(onClick = callbacks.onOpenAlarms) { Text("Alarms") }
                IconButton(onClick = callbacks.onOpenSettings) {
                    Text("⚙", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        HorizontalDivider()

        ConnectionRow(status = state.connectionStatus, onReconnect = callbacks.onReconnect)
```

with:

```kotlin
        AppBar(title = "Complications")

        ConnectionRow(status = state.connectionStatus, onReconnect = callbacks.onReconnect)
```

(`callbacks.onOpenTimerSets`, `onOpenAlarms`, and `onOpenSettings` are now reached via the bottom nav; they become unused in `HomeScreen` but stay in `HomeCallbacks` — harmless. Leave the `HorizontalDivider` import if still used elsewhere in the file; otherwise ignore the unused-import warning.)

- [ ] **Step 7: Replace the AlarmsScreen header**

In `AlarmsScreen.kt`, replace:

```kotlin
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Alarms", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Done") }
        }
        HorizontalDivider()
```

with:

```kotlin
        AppBar(title = "Alarms")
```

(Keep `BackHandler { onBack() }` and the `onBack` parameter — system back from the Alarms tab still returns to Complications.)

- [ ] **Step 8: Replace the TimerSetsScreen header**

In `TimerSetsScreen.kt`, replace:

```kotlin
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Timer Sets", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Done") }
        }
        HorizontalDivider()
```

with:

```kotlin
        AppBar(title = "Timer")
```

(Keep `BackHandler { onBack() }` and the `onBack` parameter.)

- [ ] **Step 9: Replace the SettingsScreen header**

In `SettingsScreen.kt`, replace:

```kotlin
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = callbacks.onBack) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider()
```

with:

```kotlin
        AppBar(title = "Settings")
```

(Keep `BackHandler { callbacks.onBack() }`.)

- [ ] **Step 10: Replace the TimerSetEditScreen header (keep back)**

In `TimerSetEditScreen.kt`, replace:

```kotlin
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Edit set", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Cancel") }
        }
        HorizontalDivider()
```

with:

```kotlin
        AppBar(title = "Edit set", onBack = onBack)
```

- [ ] **Step 11: Build and run tests**

Run: `./gradlew :app:compileDebugKotlinAndroid :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass.

- [ ] **Step 12: Commit**

```bash
git add app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AlarmsScreen.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/SettingsScreen.kt app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetEditScreen.kt
git commit -m "feat(ui): bottom navigation bar + shared AppBar headers across screens"
```

---

### Task 7: Launcher icon + adaptive icon + dark splash

**Files:**
- Create: `tools/generate_launcher_icons.py`
- Create: `app/src/androidMain/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`, `ic_launcher_round.png`, `ic_launcher_foreground.png`
- Create: `app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml`
- Create: `app/src/androidMain/res/values/colors.xml`
- Modify: `app/src/androidMain/res/values/themes.xml`
- Modify: `app/src/androidMain/AndroidManifest.xml`

- [ ] **Step 1: Install Pillow into the venv**

Run: `.venv/bin/pip install pillow`
Expected: `Successfully installed pillow-…` (or already satisfied).

- [ ] **Step 2: Write the icon generator script**

Create `tools/generate_launcher_icons.py`:

```python
"""Generate Android launcher icons from the design-system 1024px app icon.

Legacy square/round icons use the full framed art; the adaptive foreground
scales the art into the ~62% safe zone on a transparent canvas (the adaptive
background color matches the art's canvas, so the seam is invisible)."""
import os
from PIL import Image

SRC = "/tmp/design_bundle/extracted/super-freeollee-design-system/project/assets/app-icon-1024.png"
RES = "app/src/androidMain/res"

art = Image.open(SRC).convert("RGBA")

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FOREGROUND = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def save(img, density, name):
    d = os.path.join(RES, f"mipmap-{density}")
    os.makedirs(d, exist_ok=True)
    img.save(os.path.join(d, name))


for density, size in LEGACY.items():
    im = art.resize((size, size), Image.LANCZOS)
    save(im, density, "ic_launcher.png")
    save(im, density, "ic_launcher_round.png")

for density, size in FOREGROUND.items():
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    content = int(size * 0.62)
    scaled = art.resize((content, content), Image.LANCZOS)
    off = (size - content) // 2
    canvas.alpha_composite(scaled, (off, off))
    save(canvas, density, "ic_launcher_foreground.png")

print("Launcher icons generated.")
```

- [ ] **Step 3: Run the generator**

Run: `.venv/bin/python tools/generate_launcher_icons.py`
Expected: `Launcher icons generated.` and 5 `mipmap-*` dirs each containing `ic_launcher.png`, `ic_launcher_round.png`, `ic_launcher_foreground.png`.

- [ ] **Step 4: Write the color resources**

Create `app/src/androidMain/res/values/colors.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#232A2E</color>
    <color name="window_background">#232A2E</color>
</resources>
```

- [ ] **Step 5: Write the adaptive icon descriptors**

Create `app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
```

Create `app/src/androidMain/res/mipmap-anydpi-v26/ic_launcher_round.xml` with identical contents.

- [ ] **Step 6: Make the splash/window background dark**

Replace the entire contents of `app/src/androidMain/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.SuperFreeOllee" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@color/window_background</item>
    </style>
</resources>
```

- [ ] **Step 7: Wire the icon in the manifest**

In `AndroidManifest.xml`, replace the `<application` opening tag attributes:

```xml
    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.SuperFreeOllee">
```

with:

```xml
    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.SuperFreeOllee">
```

- [ ] **Step 8: Build the APK to validate resource merge**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (aapt2 merges the mipmaps, adaptive XML, and colors without error).

- [ ] **Step 9: Commit**

```bash
git add tools/generate_launcher_icons.py app/src/androidMain/res
git commit -m "feat(brand): SUPER FREE launcher icon (adaptive) + dark splash background"
```

---

### Task 8: SUPER FREE wordmark image + Settings About card

**Files:**
- Create: `tools/generate_wordmark.py`
- Create: `app/src/commonMain/composeResources/drawable/wordmark_super_free.png`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/SettingsScreen.kt`

- [ ] **Step 1: Write the wordmark generator**

Create `tools/generate_wordmark.py` (uses Anton + Permanent Marker, decompressed from the bundle woff2 on the fly — these fonts are NOT bundled into the app):

```python
"""Render the SUPER FREE wordmark lockup to a transparent PNG.

SUPER in Anton (Everforest green), FREE in Permanent Marker (marker red) with
the 100-style double underline. Mirrors guidelines/brand/brand-wordmark.html."""
import os
import tempfile
from fontTools.ttLib.woff2 import decompress
from PIL import Image, ImageDraw, ImageFont

SRC = "/tmp/design_bundle/extracted/super-freeollee-design-system/project/assets/fonts"
OUT = "app/src/commonMain/composeResources/drawable/wordmark_super_free.png"

tmp = tempfile.mkdtemp()
anton = os.path.join(tmp, "anton.ttf")
marker = os.path.join(tmp, "marker.ttf")
decompress(f"{SRC}/Anton-400.woff2", anton)
decompress(f"{SRC}/PermanentMarker-400.woff2", marker)

GREEN = (167, 192, 128, 255)
RED = (248, 85, 82, 255)
W, H = 900, 520
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
f_super = ImageFont.truetype(anton, 170)
f_free = ImageFont.truetype(marker, 210)

sw = d.textlength("SUPER", font=f_super)
d.text(((W - sw) / 2, 30), "SUPER", font=f_super, fill=GREEN)

fw = d.textlength("FREE", font=f_free)
fx = (W - fw) / 2
d.text((fx, 210), "FREE", font=f_free, fill=RED)

y = 470
d.line([(fx, y), (fx + fw, y)], fill=RED, width=14)
d.line([(fx + 20, y + 34), (fx + fw + 20, y + 34)], fill=RED, width=14)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
img.save(OUT)
print("Wordmark generated:", OUT)
```

- [ ] **Step 2: Run the generator**

Run: `.venv/bin/python tools/generate_wordmark.py`
Expected: `Wordmark generated: app/src/commonMain/composeResources/drawable/wordmark_super_free.png` and the file exists, non-zero size. (Coordinates are approximate — if SUPER/FREE overlap or the underline is misplaced, nudge the `y`/offset constants and rerun. A VNC visual check happens in Task 9.)

- [ ] **Step 3: Add the About card to SettingsScreen**

In `SettingsScreen.kt`, add these imports:

```kotlin
import androidx.compose.foundation.Image
import androidx.compose.material3.Card
import androidx.compose.ui.layout.ContentScale
import com.blizzardcaron.freeolleefaces.resources.Res
import com.blizzardcaron.freeolleefaces.resources.wordmark_super_free
import org.jetbrains.compose.resources.painterResource
```

Add the `AboutSection` composable at the end of the file:

```kotlin
@Composable
private fun AboutSection(state: HomeState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painter = painterResource(Res.drawable.wordmark_super_free),
                contentDescription = "Super FreeOllee",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(0.6f),
            )
            Text(
                state.versionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Not affiliated with Ollee · GPL-3.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

Then render it as the last item in the scrolling `Column` of `SettingsScreen`, right after `SleepSection(state, callbacks)`:

```kotlin
            WatchSection(state, callbacks)
            HorizontalDivider()
            LocationSection(state, callbacks)
            HorizontalDivider()
            IntervalSection(state, callbacks)
            HorizontalDivider()
            SleepSection(state, callbacks)
            HorizontalDivider()
            AboutSection(state)
```

- [ ] **Step 4: Build the APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (the new drawable resource resolves via `Res.drawable.wordmark_super_free`).

- [ ] **Step 5: Commit**

```bash
git add tools/generate_wordmark.py app/src/commonMain/composeResources/drawable/wordmark_super_free.png app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/SettingsScreen.kt
git commit -m "feat(brand): SUPER FREE wordmark in Settings About card"
```

---

### Task 9: Full verification + visual pass

**Files:** none (verification only)

- [ ] **Step 1: Run the complete test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass (including `BottomNavTabTest`).

- [ ] **Step 2: Clean assemble**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; APK at `app/build/outputs/apk/debug/`.

- [ ] **Step 3: Visual confirmation (VNC)**

This is a visual change and cannot be verified from code alone. Per the dev environment, ask the user to switch to **VNC** (RealVNC on iPad) and install/launch the debug APK on the device, then walk the four tabs + TimerSetEdit. Confirm:
- App launches **dark** (Everforest canvas, no white flash).
- Nunito type across all screens; section eyebrows UPPERCASE + tracked.
- Complications cards show the watch payload as a **green DSEG7 LCD** in a dark well; active card has the green wash + border.
- Bottom nav shows **Complications · Alarm · Timer · Settings**; tapping switches screens; selected tab is green.
- Settings shows the **SUPER FREE wordmark** in the About card.
- Launcher shows the **SUPER FREE app icon** (adaptive, not clipped).

Do not claim visual correctness until this pass is done. Fix any issues (e.g. wordmark coordinates, LCD glow strength, adaptive-icon clipping) and rebuild.

- [ ] **Step 4: Final state confirmation**

Run: `git status` and `git log --oneline -9`
Expected: clean tree; nine feature commits (Tasks 1-8) on `feature/brand-design-system`. Do not push (respects the no-workday-pushes rule; the user pushes when ready).

---

## Self-review notes

- **Spec coverage:** Unit 1→Task 1; Unit 2→Task 2; Unit 3→Task 3; Unit 4→Task 4; Unit 5→Tasks 5-6; Unit 6→Tasks 7-8; Testing/verification→Task 9. All spec sections covered.
- **Type consistency:** `freeOlleeTypography()` (Task 3) is referenced in `Theme.kt` (Task 2) — Task 3 must land before building Task 2. `dseg7Family()` (Task 3) used by `LcdReadout` (Task 4). `BottomNavTab.forScreen`/`showsBottomBar` defined in Task 5, used in Task 6. `BrandColors`/`Everforest` from Task 2 used in Tasks 3-4. `Res.font.*` / `Res.drawable.*` package `com.blizzardcaron.freeolleefaces.resources` (Task 1 config) is consistent everywhere.
- **Ordering caveat:** Tasks 2 and 3 are interdependent (color scheme references typography); commit them together if executing strictly task-by-task, or accept a transient non-building state between the two commits.
