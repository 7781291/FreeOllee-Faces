# Faces Dashboard + Stale/Error Marking — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn Home into a live all-faces dashboard, mark cache-fallback name-tag pushes with a leading `E`, abbreviate 6-digit steps to fit that prefix, and make timer "Save & send" close the Edit-set screen.

**Architecture:** `DisplayFormatter` gains a pure `stale` flag per face (the formatting heart, fully unit-tested). `MainActivity` wires that flag into the foreground refresh paths (on fetch-fail-with-cache → show/push the `E` value) and adds a foreground-only `refreshAllPreviews()` polled while Home is visible. `HomeScreen` is rewritten from a single-active-face body into a stacked list of face cards. `AutoUpdateWorker` pushes the `E`-marked cached value on background fetch failure. The active-face selection and notification toggle stay where they are.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), JUnit4, WorkManager. Build: `./gradlew`. Tests: `./gradlew :app:testDebugUnitTest`.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `app/src/main/java/.../format/DisplayFormatter.kt` | Pure 6-char payload formatting | Add `stale` flag to `temperature`/`steps`/`sunTime`; private `markStale` + `abbreviateThousands` helpers |
| `app/src/test/java/.../format/DisplayFormatterTest.kt` | Formatter unit tests | Add `E`/abbreviation/sun-drop cases |
| `app/src/main/java/.../MainActivity.kt` | App state + screen routing + refresh logic | E-on-fail wiring in `refreshTemp`/`refreshSteps`; new `refreshAllPreviews()`; foreground ticker; new `onToggleNotifications` callback; timer `onSend` nav fix |
| `app/src/main/java/.../ui/HomeScreen.kt` | The dashboard UI | Full rewrite: five face cards, badges, tap-to-expand controls, "Update active now" |
| `app/src/main/java/.../auto/AutoUpdateWorker.kt` | Background due-send | Push `E`-marked cached value on temp/steps fetch failure |

**Naming locked across tasks** (use these exact names):
- `DisplayFormatter.temperature(value: Double, unit: TempUnit = TempUnit.FAHRENHEIT, stale: Boolean = false)`
- `DisplayFormatter.steps(count: Long, stale: Boolean = false)`
- `DisplayFormatter.sunTime(kind: SunEventKind, time: LocalTime, stale: Boolean = false)`
- `MainActivity` local fun `refreshAllPreviews()` (no args; always preview-only)
- `HomeCallbacks.onToggleNotifications: (Boolean) -> Unit`

---

## Task 1: DisplayFormatter — `stale` flag for temperature

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatter.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatterTest.kt`

The rule: format normally, then if `stale`, replace the **leading pad space** with `E`. Temps are ≤3 digits under `%4d`, so there is always ≥1 leading space — `E` always fits without losing a glyph.

- [ ] **Step 1: Write the failing tests**

Add to `DisplayFormatterTest.kt` (inside the class):

```kotlin
@Test
fun `temperature stale replaces leading pad with E - two digit`() {
    assertEquals("E 67#F", DisplayFormatter.temperature(67.0, stale = true))
    assertEquals("E 72#F", DisplayFormatter.temperature(72.0, TempUnit.FAHRENHEIT, stale = true))
}

@Test
fun `temperature stale three digit consumes the single pad`() {
    assertEquals("E100#F", DisplayFormatter.temperature(100.0, stale = true))
    assertEquals("E102#F", DisplayFormatter.temperature(102.0, TempUnit.CELSIUS, stale = true))
}

@Test
fun `temperature stale is still six chars`() {
    listOf(0.0, 67.0, 100.0, -12.0).forEach {
        assertEquals("len for $it", 6, DisplayFormatter.temperature(it, stale = true).length)
    }
}

@Test
fun `temperature not stale is unchanged`() {
    assertEquals("  72#F", DisplayFormatter.temperature(72.0, stale = false))
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*DisplayFormatterTest*"`
Expected: FAIL — `temperature` has no `stale` parameter (compile error / unresolved reference).

- [ ] **Step 3: Implement the `stale` flag + `markStale` helper**

In `DisplayFormatter.kt`, replace the two `temperature` functions with:

```kotlin
fun temperature(value: Double, unit: TempUnit = TempUnit.FAHRENHEIT, stale: Boolean = false): String {
    // The watch's segment font maps '#' (0x23) to the degree ring; see the note below.
    val rounded = value.roundToInt()
    return markStale("%4d#${unit.symbol}".format(rounded), stale)
}
```

Delete the old `fun temperature(value: Double): String = temperature(value, TempUnit.FAHRENHEIT)`
overload — the default parameters above subsume it (callers passing one or two args still compile).

Add this private helper to the `object DisplayFormatter` body:

```kotlin
/**
 * Mark an already-formatted 6-char payload as stale by replacing its leading pad space with 'E'.
 * Caller guarantees there is a leading space to consume (temperatures and ≤5-digit steps always
 * have one). No-op when [stale] is false.
 */
private fun markStale(formatted: String, stale: Boolean): String =
    if (stale && formatted.startsWith(' ')) "E" + formatted.drop(1) else formatted
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*DisplayFormatterTest*"`
Expected: PASS (all existing + new temperature tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatter.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatterTest.kt
git commit -m "feat(format): stale 'E' prefix for temperature payloads"
```

---

## Task 2: DisplayFormatter — `stale` flag for steps (with thousands abbreviation)

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatter.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatterTest.kt`

Rules:
- `stale = false` → today's behavior (unchanged), incl. full 6-digit `"100234"`.
- `stale = true`, count fits with a pad (≤5 digits) → `markStale` replaces the leading space:
  `8432` → `"E 8432"`, `12345` → `"E12345"`.
- `stale = true`, count fills all 6 (6 digits, `100000..999999`) → abbreviate to thousands
  (`100234` → `"100k"`, `999999` → `"999k"`), then prefix `"E "`: `"E 100k"`. Abbreviation
  happens **only** in stale mode.

- [ ] **Step 1: Write the failing tests**

Add to `DisplayFormatterTest.kt`:

```kotlin
@Test
fun `steps stale small count replaces leading pad with E`() {
    assertEquals("E    0", DisplayFormatter.steps(0, stale = true))
    assertEquals("E 8432", DisplayFormatter.steps(8432, stale = true))
}

@Test
fun `steps stale five digit count consumes the single pad`() {
    assertEquals("E12345", DisplayFormatter.steps(12345, stale = true))
    assertEquals("E99999", DisplayFormatter.steps(99999, stale = true))
}

@Test
fun `steps stale six digit count abbreviates to thousands`() {
    assertEquals("E 100k", DisplayFormatter.steps(100_000, stale = true))
    assertEquals("E 100k", DisplayFormatter.steps(100_234, stale = true)) // floors to nearest k
    assertEquals("E 999k", DisplayFormatter.steps(999_999, stale = true))
}

@Test
fun `steps stale clamps impossible counts before abbreviating`() {
    assertEquals("E 999k", DisplayFormatter.steps(1_000_000, stale = true))
}

@Test
fun `steps stale is always exactly six chars`() {
    listOf(0L, 5L, 8432L, 12_345L, 99_999L, 100_000L, 999_999L, 1_000_000L, -5L).forEach {
        assertEquals("len for $it", 6, DisplayFormatter.steps(it, stale = true).length)
    }
}

@Test
fun `steps not stale six digit stays full`() {
    assertEquals("100234", DisplayFormatter.steps(100_234, stale = false))
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*DisplayFormatterTest*"`
Expected: FAIL — `steps` has no `stale` parameter.

- [ ] **Step 3: Implement the `stale` flag + `abbreviateThousands` helper**

In `DisplayFormatter.kt`, replace the `steps` function with:

```kotlin
fun steps(count: Long, stale: Boolean = false): String {
    val clamped = count.coerceIn(0L, 999_999L)
    val plain = "%${LENGTH}d".format(clamped)
    if (!stale) return plain
    // 6-digit counts fill the row, leaving no pad for 'E' — abbreviate to thousands first so
    // "E " + a 4-char "Nk" value fits in 6. Smaller counts keep their pad for markStale.
    return if (plain.startsWith(' ')) markStale(plain, stale = true)
    else "E " + abbreviateThousands(clamped)
}
```

Add this private helper to the `object DisplayFormatter` body:

```kotlin
/** Floor [count] to whole thousands and render as "Nk" (e.g. 100_234 -> "100k"). For the
 *  6-digit range 100_000..999_999 this is always 4 chars ("100k".."999k"). */
private fun abbreviateThousands(count: Long): String = "${count / 1000}k"
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*DisplayFormatterTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatter.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatterTest.kt
git commit -m "feat(format): stale 'E' prefix + thousands abbreviation for steps"
```

---

## Task 3: DisplayFormatter — `stale` flag for sunTime (drop event char)

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatter.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatterTest.kt`

Sun strings fill all 6 chars with no pad (`"6:41ps"`, `"10:30s"`), so `markStale` can't apply.
Instead, when `stale`, drop the trailing rise/set event char and prefix `E`:
`"6:41ps"` → `"E6:41p"`, `"10:30s"` → `"E10:30"`. The time survives; only the r/s letter is lost.

> **Note:** no runtime path currently sets `stale = true` for sun — sun is computed locally from
> coordinates and effectively never "fetch-fails". This task delivers the formatter capability and
> its tests per the approved spec; call sites pass `stale = false`. Do not invent a trigger.

- [ ] **Step 1: Write the failing tests**

Add to `DisplayFormatterTest.kt`:

```kotlin
@Test
fun `sunTime stale single-digit hour drops event char and prefixes E`() {
    // "6:41ps" (6 chars) -> drop 's' -> "6:41p" -> prefix E -> "E6:41p"
    assertEquals("E6:41p", DisplayFormatter.sunTime(SunEventKind.SUNSET, LocalTime.of(18, 41), stale = true))
    // sunrise: "6:29ar" -> "E6:29a"
    assertEquals("E6:29a", DisplayFormatter.sunTime(SunEventKind.SUNRISE, LocalTime.of(6, 29), stale = true))
}

@Test
fun `sunTime stale two-digit hour drops event char`() {
    // "10:30s" -> drop 's' -> "10:30" -> prefix E -> "E10:30"
    assertEquals("E10:30", DisplayFormatter.sunTime(SunEventKind.SUNSET, LocalTime.of(22, 30), stale = true))
}

@Test
fun `sunTime stale is always exactly six chars`() {
    listOf(LocalTime.of(6, 41), LocalTime.of(10, 5), LocalTime.of(0, 0), LocalTime.of(12, 0)).forEach {
        assertEquals("len for $it", 6, DisplayFormatter.sunTime(SunEventKind.SUNSET, it, stale = true).length)
    }
}

@Test
fun `sunTime not stale is unchanged`() {
    assertEquals("8:15ps", DisplayFormatter.sunTime(SunEventKind.SUNSET, LocalTime.of(20, 15), stale = false))
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*DisplayFormatterTest*"`
Expected: FAIL — `sunTime` has no `stale` parameter.

- [ ] **Step 3: Implement the `stale` flag**

In `DisplayFormatter.kt`, change the `sunTime` signature and add the stale branch at the end.
Replace the function with:

```kotlin
fun sunTime(kind: SunEventKind, time: LocalTime, stale: Boolean = false): String {
    val hour24 = time.hour
    val minute = time.minute
    val isAm = hour24 < 12
    val hour12 = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    val eventChar = if (kind == SunEventKind.SUNRISE) 'r' else 's'

    val fresh = if (hour12 < 10) {
        // single-digit hour: include am/pm marker -> "H:MMar" or "H:MMps"
        val ampm = if (isAm) 'a' else 'p'
        "%d:%02d%c%c".format(hour12, minute, ampm, eventChar)
    } else {
        // two-digit hour (10, 11, 12): drop am/pm -> "HH:MMr" or "HH:MMs"
        "%d:%02d%c".format(hour12, minute, eventChar)
    }
    // Sun fills all 6 chars (no pad) — to mark stale, drop the trailing r/s and prefix 'E'.
    return if (stale) "E" + fresh.dropLast(1) else fresh
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*DisplayFormatterTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatter.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/format/DisplayFormatterTest.kt
git commit -m "feat(format): stale 'E' marking for sun-event payloads"
```

---

## Task 4: Foreground E-on-failure wiring (refreshTemp + refreshSteps)

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt`

On a fetch failure where a usable cache exists, show (and, if `push`, send) the `E`-marked cached
value instead of an error. No cache → keep today's `Error` behavior. This is Compose/coroutine
wiring (no unit test); verified on-device in Task 9.

- [ ] **Step 1: Replace the `refreshTemp` failure branch**

In `MainActivity.kt`, the current `refreshTemp` ends with this `onFailure` (inside the
`OpenMeteoClient.currentTemp(...)` chain):

```kotlin
                .onFailure { err ->
                    update { it.copy(tempPreview = PreviewState.Error(WeatherErrorCopy.describe(err))) }
                }
```

Replace that `onFailure` block with:

```kotlin
                .onFailure { err ->
                    // Fetch failed: fall back to the last cached temp (only if it's in the unit we'd
                    // display) and mark it stale with a leading 'E'. No usable cache -> show the error.
                    val cached = prefs.tempValue
                    if (cached != null && prefs.tempCacheUnit == state.tempUnit) {
                        val payload = DisplayFormatter.temperature(cached, state.tempUnit, stale = true)
                        val human = "Currently: %.1f°%s (stale)".format(Locale.US, cached, state.tempUnit.symbol)
                        update { it.copy(
                            tempPreview = PreviewState.Ready(payload, human),
                            tempUpdated = prefs.tempFetchedMs?.let { ms -> "Updated ${clockTime(ms)}" },
                        ) }
                        if (push) pushIfWatch(payload)
                    } else {
                        update { it.copy(tempPreview = PreviewState.Error(WeatherErrorCopy.describe(err))) }
                    }
                }
```

- [ ] **Step 2: Replace the `refreshSteps` failure branch**

In `MainActivity.kt`, the current `refreshSteps` ends with this `onFailure` (inside
`stepsRepo.todaySteps()`):

```kotlin
                .onFailure {
                    update { it.copy(
                        stepsPreview = PreviewState.Error("Couldn't read steps from Health Connect"),
                    ) }
                }
```

Replace that `onFailure` block with:

```kotlin
                .onFailure {
                    // Read failed: fall back to the last cached step count, marked stale with 'E'.
                    val cached = prefs.lastStepCount
                    if (cached != null) {
                        val payload = DisplayFormatter.steps(cached, stale = true)
                        update { it.copy(
                            stepsPreview = PreviewState.Ready(payload, stepsHuman(cached) + " (stale)"),
                            stepsUpdated = prefs.stepsFetchedMs?.let { ms -> "Updated ${clockTime(ms)}" },
                        ) }
                        if (push) pushIfWatch(payload)
                    } else {
                        update { it.copy(
                            stepsPreview = PreviewState.Error("Couldn't read steps from Health Connect"),
                        ) }
                    }
                }
```

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`Locale`, `clockTime`, `stepsHuman`, `pushIfWatch`, `prefs.tempCacheUnit`,
`prefs.lastStepCount` are all already imported/defined in this file.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "feat(faces): show/push 'E'-marked cached value on foreground fetch failure"
```

---

## Task 5: Background worker E-on-failure push (temperature + steps)

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt`

When the background fetch fails but a usable cache exists, push the `E`-marked cached value to the
watch instead of silently skipping — so the watch shows the last-known number flagged stale. On a
successful stale push we clear the error notification (the watch itself now signals staleness);
genuine Health access gaps still notify. No unit test (the worker is BLE/network-coupled); verified
via a forced run in Task 9.

- [ ] **Step 1: Replace the `runTemperature` fetch-failure branch**

In `AutoUpdateWorker.kt`, `runTemperature` currently has:

```kotlin
                .onFailure { err ->
                    val suffix = (err as? WeatherFetchError)?.statusCode?.let { " (HTTP $it)" } ?: ""
                    prefs.recordAutoSend("Skipped: weather fetch failed$suffix")
                    applyHealth(ctx, prefs, FailureKind.WEATHER_FETCH_FAILED, inSleep)
                }
```

Replace that `onFailure` block with:

```kotlin
                .onFailure { err ->
                    val suffix = (err as? WeatherFetchError)?.statusCode?.let { " (HTTP $it)" } ?: ""
                    val cached = prefs.tempValue
                    if (cached != null && prefs.tempCacheUnit == prefs.tempUnit) {
                        // Fetch failed but we have a cached temp — push it marked stale ('E').
                        val payload = DisplayFormatter.temperature(cached, prefs.tempUnit, stale = true)
                        OlleeBleClient(ctx).send(address, payload)
                            .onSuccess {
                                prefs.recordAutoSend("Sent stale '$payload'$suffix")
                                applyHealth(ctx, prefs, null, inSleep)
                            }
                            .onFailure {
                                backstopped = handleSendFailure(
                                    ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep,
                                )
                            }
                    } else {
                        prefs.recordAutoSend("Skipped: weather fetch failed$suffix")
                        applyHealth(ctx, prefs, FailureKind.WEATHER_FETCH_FAILED, inSleep)
                    }
                }
```

- [ ] **Step 2: Replace the `runSteps` read-failure branch**

In `AutoUpdateWorker.kt`, `runSteps` currently has:

```kotlin
                .onFailure { error ->
                    when (val kind = StepsFailureClassifier.kindFor(error)) {
                        // Transient read glitch with access intact — don't alarm the user; the
                        // chain re-arms below and retries next cycle.
                        null -> prefs.recordAutoSend("Skipped: steps read failed (will retry)")
                        // Genuine access gap (HC unavailable, or steps/background read not
                        // granted) — actionable, so notify with "grant Health access".
                        else -> {
                            prefs.recordAutoSend("Skipped: grant Health access")
                            applyHealth(ctx, prefs, kind, inSleep)
                        }
                    }
                }
```

Replace that `onFailure` block with:

```kotlin
                .onFailure { error ->
                    val kind = StepsFailureClassifier.kindFor(error)
                    val cached = prefs.lastStepCount
                    if (cached != null) {
                        // Read failed but we have a cached count — push it marked stale ('E').
                        val payload = DisplayFormatter.steps(cached, stale = true)
                        OlleeBleClient(ctx).send(address, payload)
                            .onSuccess { prefs.recordAutoSend("Sent stale '$payload'") }
                            .onFailure {
                                backstopped = handleSendFailure(
                                    ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep,
                                )
                            }
                    } else {
                        prefs.recordAutoSend("Skipped: steps read failed (will retry)")
                    }
                    // A genuine access gap is still actionable — surface it regardless of the
                    // stale push above. Transient glitches (kind == null) stay quiet.
                    if (kind != null) applyHealth(ctx, prefs, kind, inSleep)
                }
```

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`DisplayFormatter`, `OlleeBleClient`, `handleSendFailure`, `applyHealth`,
`FailureKind`, `backstopped` are all already in scope in these functions.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt
git commit -m "feat(auto): push 'E'-marked cached value on background fetch failure"
```

---

## Task 6: Foreground dashboard polling (`refreshAllPreviews` + ticker)

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt`

Add a preview-only refresh of every face, run when Home becomes visible and every 60 s while it
stays visible. Background delivery is untouched.

- [ ] **Step 1: Add `refreshAllPreviews()`**

In `MainActivity.kt`, immediately after the `refreshActive` function (the one ending with the
`ActiveFace.CUSTOM -> {}` arm), add:

```kotlin
/**
 * Refresh every face's preview for the dashboard. Never pushes to the watch. Cheap: only
 * temperature can hit the network, and only when its cache has expired (refreshTemp honors
 * isTempCacheFresh); sun is local, steps is a local read, notifications is read from prefs.
 */
fun refreshAllPreviews() {
    refreshTemp(force = false, push = false)
    refreshSun(push = false)
    refreshSteps(push = false)
    update { it.copy(
        notificationCount = prefs.notificationCount,
        notificationAccessGranted = isNotificationAccessGranted(context),
        notificationsEnabled = prefs.notificationsEnabled,
    ) }
}
```

- [ ] **Step 2: Add the foreground ticker**

In `MainActivity.kt`, immediately after the existing `LaunchedEffect(Unit) { ... }` block that ends
with `refreshActive(force = false, push = false)` and `AutoUpdateScheduler.reschedule(context)`, add
a second effect keyed on `screen`:

```kotlin
// Dashboard polling: while Home is visible, refresh all face previews on entry and every 60 s.
// Keyed on `screen` so it cancels when you navigate away and restarts on return.
LaunchedEffect(screen) {
    if (screen == Screen.Home) {
        while (true) {
            refreshAllPreviews()
            delay(60_000)
        }
    }
}
```

(`delay` is already imported — it's used by the coord-edit debounce.)

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "feat(faces): poll all face previews while Home is visible"
```

---

## Task 7: Rewrite HomeScreen as the face dashboard

**Files:**
- Replace: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt` (add the new callback)

`HomeState` is unchanged. `HomeCallbacks` gains `onToggleNotifications`. The screen renders five
cards (Temperature, Sun event, Steps, Custom, Notifications); name-tag faces show an `● active`
badge when active, Notifications shows `● on` when enabled. Tapping a card with controls expands
them (one open at a time). A single bottom button pushes the active face. This is Compose UI —
verified on-device in Task 9.

- [ ] **Step 1: Replace the entire contents of `HomeScreen.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.auto.ActiveFace
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.format.TempUnit

data class HomeState(
    val activeFace: ActiveFace = ActiveFace.TEMPERATURE,
    val watchLabel: String = "Watch: none selected",
    val watchSelected: Boolean = false,
    val sending: Boolean = false,

    val locationLabel: String = "Location: not set",
    val locationFreshness: String? = null,
    val locating: Boolean = false,

    val tempUnit: TempUnit = TempUnit.FAHRENHEIT,
    val tempPreview: PreviewState = PreviewState.WaitingForCoords,
    val tempUpdated: String? = null,
    val tempNext: String? = null,
    val updateIntervalMinutes: Int = 15,
    val sleepEnabled: Boolean = true,
    val sleepStartMin: Int = 22 * 60,
    val sleepEndMin: Int = 6 * 60,

    val sunPreview: PreviewState = PreviewState.WaitingForCoords,
    val sunUpdated: String? = null,
    val sunNext: String? = null,

    val stepsPreview: PreviewState = PreviewState.Loading,
    val stepsUpdated: String? = null,
    val stepsHealthGranted: Boolean = false,

    val custom: String = "",
    val customSent: String? = null,

    val notificationCount: Int = 0,
    val notificationAccessGranted: Boolean = false,
    val notificationsEnabled: Boolean = false,

    val lat: String = "",
    val lng: String = "",
)

data class HomeCallbacks(
    val onOpenFaces: () -> Unit,
    val onOpenTimerSets: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onUpdateNow: () -> Unit,
    val onTempUnitChange: (TempUnit) -> Unit,
    val onCustomChange: (String) -> Unit,
    val onSendCustom: () -> Unit,
    val onGrantHealth: () -> Unit,
    val onGrantNotificationAccess: () -> Unit,
    val onToggleNotifications: (Boolean) -> Unit,
)

private enum class FaceCardId { TEMPERATURE, STEPS, CUSTOM, NOTIFICATIONS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeState,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf<FaceCardId?>(null) }
    fun toggle(id: FaceCardId) { expanded = if (expanded == id) null else id }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Faces", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = callbacks.onOpenTimerSets) { Text("Timers") }
                TextButton(onClick = callbacks.onOpenFaces) { Text("Faces") }
                IconButton(onClick = callbacks.onOpenSettings) {
                    Text("⚙", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        HorizontalDivider()

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.watchSelected) {
                SettingsHint("No watch selected — open Settings (⚙)")
            }

            FaceCard(
                title = "Temperature",
                badge = "active".takeIf { state.activeFace == ActiveFace.TEMPERATURE },
                preview = state.tempPreview,
                updated = state.tempUpdated,
                next = state.tempNext,
                expanded = expanded == FaceCardId.TEMPERATURE,
                onToggle = { toggle(FaceCardId.TEMPERATURE) },
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = state.tempUnit == TempUnit.FAHRENHEIT,
                        onClick = { callbacks.onTempUnitChange(TempUnit.FAHRENHEIT) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("°F") }
                    SegmentedButton(
                        selected = state.tempUnit == TempUnit.CELSIUS,
                        onClick = { callbacks.onTempUnitChange(TempUnit.CELSIUS) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("°C") }
                }
            }

            FaceCard(
                title = "Sun event",
                badge = "active".takeIf { state.activeFace == ActiveFace.SUN },
                preview = state.sunPreview,
                updated = state.sunUpdated,
                next = state.sunNext,
                expanded = false,
                onToggle = null,
            )

            FaceCard(
                title = "Steps",
                badge = "active".takeIf { state.activeFace == ActiveFace.STEPS },
                preview = state.stepsPreview,
                updated = state.stepsUpdated,
                next = null,
                expanded = expanded == FaceCardId.STEPS,
                onToggle = { toggle(FaceCardId.STEPS) },
            ) {
                if (!state.stepsHealthGranted) {
                    Text("Health access needed", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Steps come from Health Connect, where your step-tracking app writes them. " +
                            "Grant read access to show today's count on your watch.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = callbacks.onGrantHealth, modifier = Modifier.fillMaxWidth()) {
                        Text("Grant Health access")
                    }
                } else {
                    Text(
                        "Pushed every ${state.updateIntervalMinutes} min while awake.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            FaceCard(
                title = "Custom",
                badge = "active".takeIf { state.activeFace == ActiveFace.CUSTOM },
                preview = PreviewState.Ready(DisplayFormatter.custom(state.custom), "'${state.custom}'"),
                updated = null,
                next = null,
                expanded = expanded == FaceCardId.CUSTOM,
                onToggle = { toggle(FaceCardId.CUSTOM) },
            ) {
                OutlinedTextField(
                    value = state.custom,
                    onValueChange = callbacks.onCustomChange,
                    label = { Text("Custom (up to 6 chars)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = callbacks.onSendCustom,
                    enabled = state.watchSelected && !state.sending,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send to watch") }
                if (state.customSent != null) {
                    Text(state.customSent, style = MaterialTheme.typography.bodySmall)
                }
            }

            NotificationsCard(
                state = state,
                expanded = expanded == FaceCardId.NOTIFICATIONS,
                onToggle = { toggle(FaceCardId.NOTIFICATIONS) },
                onToggleEnabled = callbacks.onToggleNotifications,
                onGrantAccess = callbacks.onGrantNotificationAccess,
            )
        }

        Button(onClick = callbacks.onUpdateNow, modifier = Modifier.fillMaxWidth()) {
            Text("Update active now")
        }

        val context = LocalContext.current
        val versionText = remember {
            val name = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()
            versionLabel(name, context.packageName)
        }
        Text(
            versionText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingsHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun FaceCard(
    title: String,
    badge: String?,
    preview: PreviewState,
    updated: String?,
    next: String?,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    expandedContent: (@Composable () -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val headerModifier = Modifier
                .fillMaxWidth()
                .let { if (onToggle != null) it.clickable { onToggle() } else it }
                .padding(12.dp)
            Column(modifier = headerModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (badge != null) {
                            Text(
                                "● $badge",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (onToggle != null) {
                            Text(if (expanded) "  ▾" else "  ▸", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                FaceValue(preview, updated, next)
            }
            if (expanded && expandedContent != null) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { expandedContent() }
            }
        }
    }
}

@Composable
private fun NotificationsCard(
    state: HomeState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onGrantAccess: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Notifications", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.notificationsEnabled) {
                            Text(
                                "● on",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(if (expanded) "  ▾" else "  ▸", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val human = if (state.notificationsEnabled) "${state.notificationCount} unread" else "Off"
                Text(human, style = MaterialTheme.typography.headlineMedium)
                Text("Weekday slot overlay on the Clock face.", style = MaterialTheme.typography.bodySmall)
            }
            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Show count in weekday slot", style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = state.notificationsEnabled, onCheckedChange = onToggleEnabled)
                    }
                    if (state.notificationsEnabled && !state.notificationAccessGranted) {
                        Text("Notification access needed", style = MaterialTheme.typography.titleSmall)
                        Button(onClick = onGrantAccess, modifier = Modifier.fillMaxWidth()) {
                            Text("Grant notification access")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FaceValue(preview: PreviewState, updated: String?, next: String?) {
    when (preview) {
        is PreviewState.WaitingForCoords -> Text("Waiting for coordinates…", style = MaterialTheme.typography.bodyMedium)
        is PreviewState.Loading -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
        is PreviewState.Ready -> {
            Text(preview.human, style = MaterialTheme.typography.headlineMedium)
            Text(
                "Watch: '${preview.payload}'",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        is PreviewState.Error -> Text(preview.message, style = MaterialTheme.typography.bodyMedium)
        PreviewState.NoEvent -> Text("No sunrise/sunset in next 24 h.", style = MaterialTheme.typography.bodyMedium)
    }
    if (updated != null) Text(updated, style = MaterialTheme.typography.bodySmall)
    if (next != null) Text(next, style = MaterialTheme.typography.bodySmall)
}
```

- [ ] **Step 2: Add the `onToggleNotifications` callback in MainActivity**

In `MainActivity.kt`, the `homeCallbacks = HomeCallbacks(...)` construction currently ends with:

```kotlin
        onGrantNotificationAccess = {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
    )
```

Change it to add the new callback before the closing paren:

```kotlin
        onGrantNotificationAccess = {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
        onToggleNotifications = { setNotificationsEnabled(it) },
    )
```

(`setNotificationsEnabled` is already defined in this file.)

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "feat(faces): rewrite Home as a live all-faces dashboard"
```

---

## Task 8: Timer "Save & send" closes Edit set

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt`

`onSend` saves + sends but never navigates, unlike `onSave`. Add the same navigation back to the
timer-sets list.

- [ ] **Step 1: Add navigation to the timer `onSend`**

In `MainActivity.kt`, the `Screen.TimerSetEdit` block currently wires:

```kotlin
                    onSend = { s -> timerRepo.save(s); refreshTimers(); sendTimerSet(s) },
```

Change it to:

```kotlin
                    onSend = { s -> timerRepo.save(s); refreshTimers(); sendTimerSet(s); screen = Screen.TimerSets },
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "fix(timers): close Edit set after Save & send"
```

---

## Task 9: Full build, test, and on-device verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all `DisplayFormatterTest` cases (existing + new) pass.

- [ ] **Step 2: Build the debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (Installs as `com.blizzardcaron.freeolleefaces.debug` — the dev app —
so it won't collide with the GitHub release build.)

- [ ] **Step 3: Install and launch on the watch-paired phone**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
Then launch "FreeOllee Debug" from the drawer (or `adb shell monkey -p com.blizzardcaron.freeolleefaces.debug 1`).

- [ ] **Step 4: Verify the dashboard (manual)**

Confirm on the phone:
- Home shows five cards: Temperature, Sun event, Steps, Custom, Notifications — each with a live
  value and a `Watch: '…'` payload line.
- The active name-tag face shows `● active`; Notifications shows `● on` when enabled.
- Tapping Temperature/Steps/Custom/Notifications expands its controls; tapping again collapses.
- Values refresh on opening Home and roughly every minute while it stays open.
- `Update active now` pushes the active face to the watch.

- [ ] **Step 5: Verify the `E` stale marking (manual)**

- Temperature: with a watch in range and a cached temp, toggle airplane mode / kill network, then
  `Update active now` (Temperature active) → the watch name tag shows e.g. `E 72#F` and the card
  shows `… (stale)`.
- Steps: with Health access revoked but a cached count, force a worker run and confirm the watch
  shows `E <count>` (6-digit counts show `E <n>k`):
  `adb shell cmd jobscheduler run -f -n "androidx.work.systemjobscheduler" com.blizzardcaron.freeolleefaces.debug <jobid>`
  (find `<jobid>` via `adb shell dumpsys jobscheduler | grep -A2 freeolleefaces.debug`).

- [ ] **Step 6: Verify the timer fix (manual)**

Open a timer set → Edit → "Save & send" → confirm it returns to the Timer sets list (not stuck on
Edit set).

- [ ] **Step 7: Final confirmation**

All unit tests green, APK builds, dashboard + `E` marking + timer nav verified on-device. No commit
(verification task).

---

## Self-Review Notes

- **Spec coverage:** §1 dashboard → Tasks 6–7; §2 `E` marking (Temp/Steps/Sun, trigger, placement,
  abbreviation) → Tasks 1–5; §3 timer Save & send → Task 8; §4 files/testing → Tasks 1–9.
- **Sun runtime trigger:** intentionally none (documented in Task 3) — sun is locally computed and
  effectively never fetch-fails; the formatter capability + tests satisfy the approved spec.
- **Worker stale push** suppresses the weather-fetch error notification on a successful stale send
  (the watch's `E` is the signal); genuine Health access gaps still notify (Task 5).
