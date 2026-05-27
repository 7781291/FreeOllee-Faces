# Active-Face UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the UI around a single active face that is simultaneously what the watch shows, what auto-updates, and the default view — with a fast-switch Faces list, per-face config, fetch timestamps, and location inputs that appear only on fallback.

**Architecture:** Two hand-rolled screens (`Home`, `FacesList`) switched by a `Screen` value in `mutableStateOf` (no nav library). `AutoSource{OFF,TEMPERATURE,SUN}` becomes `ActiveFace{TEMPERATURE,SUN,CUSTOM}`; the active face drives the existing WorkManager auto-update chain (CUSTOM cancels it). A pure staleness function lets activation push a cached temperature without a redundant fetch.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), AndroidX WorkManager, JUnit (JVM unit tests only — no instrumented/UI tests).

**Build/test commands:**
- Unit tests: `./gradlew :app:testDebugUnitTest`
- Compile/build: `./gradlew :app:assembleDebug`

(Gradle builds are slow on the Pi with a cold cache; allow time.)

**Ordering note:** Each task ends in a state that compiles. The enum swap is staged: Task 3 *adds* `activeFace` to `Prefs` while keeping `autoSource`; Task 4 switches the scheduler/worker to `activeFace`; Task 7 deletes the old UI, removes `Prefs.autoSource`, and deletes `AutoSource.kt`. Between Task 4 and Task 7 the old auto-update card is a dead control — expected and resolved in Task 7.

---

## File structure

**Create:**
- `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/ActiveFace.kt` — the active-face enum + legacy mapping
- `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/Staleness.kt` — pure temperature-cache freshness check
- `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/Screen.kt` — sealed screen type
- `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/FacesListScreen.kt` — the faces list
- `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt` — home scaffold + per-face bodies + location fallback (replaces `MainScreen.kt`)
- `app/src/test/java/com/blizzardcaron/freeolleefaces/auto/ActiveFaceTest.kt`
- `app/src/test/java/com/blizzardcaron/freeolleefaces/auto/StalenessTest.kt`
- `docs/superpowers/verification-active-face-redesign.md` — manual verification checklist

**Modify:**
- `app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt` — add `activeFace`, temp cache, custom fields; later remove `autoSource`
- `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduler.kt` — key off `activeFace`
- `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt` — key off `activeFace`, record temp fetch
- `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt` — screen state, activation/staleness/push logic, location fallback

**Delete (Task 7):**
- `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoSource.kt`
- `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/MainScreen.kt`

---

## Task 1: ActiveFace enum + legacy mapping

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/ActiveFace.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/auto/ActiveFaceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/blizzardcaron/freeolleefaces/auto/ActiveFaceTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.auto

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveFaceTest {

    @Test fun legacyOffMapsToTemperature() {
        assertEquals(ActiveFace.TEMPERATURE, ActiveFace.fromLegacyAutoSource("OFF"))
    }

    @Test fun legacyNullMapsToTemperature() {
        assertEquals(ActiveFace.TEMPERATURE, ActiveFace.fromLegacyAutoSource(null))
    }

    @Test fun legacyTemperatureMapsToTemperature() {
        assertEquals(ActiveFace.TEMPERATURE, ActiveFace.fromLegacyAutoSource("TEMPERATURE"))
    }

    @Test fun legacySunMapsToSun() {
        assertEquals(ActiveFace.SUN, ActiveFace.fromLegacyAutoSource("SUN"))
    }

    @Test fun unknownStringMapsToTemperature() {
        assertEquals(ActiveFace.TEMPERATURE, ActiveFace.fromLegacyAutoSource("WAT"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ActiveFaceTest"`
Expected: FAIL — `ActiveFace` unresolved (compilation error).

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/ActiveFace.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.auto

/** The single face the watch currently shows / auto-updates. Exactly one is active. */
enum class ActiveFace {
    TEMPERATURE,
    SUN,
    CUSTOM;

    companion object {
        /** Map the legacy `AutoSource` pref name to an [ActiveFace]. "OFF"/null/unknown -> TEMPERATURE. */
        fun fromLegacyAutoSource(name: String?): ActiveFace = when (name) {
            "SUN" -> SUN
            "TEMPERATURE" -> TEMPERATURE
            else -> TEMPERATURE
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*ActiveFaceTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/auto/ActiveFace.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/auto/ActiveFaceTest.kt
git commit -m "Add ActiveFace enum with legacy AutoSource mapping"
```

---

## Task 2: Pure temperature-cache staleness check

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/Staleness.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/auto/StalenessTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/blizzardcaron/freeolleefaces/auto/StalenessTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.auto

import com.blizzardcaron.freeolleefaces.format.TempUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StalenessTest {

    private val now = 1_000_000_000_000L

    @Test fun freshWhenWithinInterval() {
        assertTrue(
            isTempCacheFresh(
                fetchedMs = now - 10 * 60_000L,
                cacheUnit = TempUnit.FAHRENHEIT,
                currentUnit = TempUnit.FAHRENHEIT,
                intervalMin = 60,
                nowMs = now,
            )
        )
    }

    @Test fun staleWhenPastInterval() {
        assertFalse(
            isTempCacheFresh(now - 61 * 60_000L, TempUnit.FAHRENHEIT, TempUnit.FAHRENHEIT, 60, now)
        )
    }

    @Test fun staleWhenUnitMismatch() {
        assertFalse(
            isTempCacheFresh(now - 1 * 60_000L, TempUnit.CELSIUS, TempUnit.FAHRENHEIT, 60, now)
        )
    }

    @Test fun staleWhenNeverFetched() {
        assertFalse(isTempCacheFresh(null, null, TempUnit.FAHRENHEIT, 60, now))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*StalenessTest"`
Expected: FAIL — `isTempCacheFresh` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/Staleness.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.auto

import com.blizzardcaron.freeolleefaces.format.TempUnit

/**
 * True when a cached temperature can be pushed without re-fetching: it exists, was fetched in the
 * current unit, and is younger than the auto-update interval.
 */
fun isTempCacheFresh(
    fetchedMs: Long?,
    cacheUnit: TempUnit?,
    currentUnit: TempUnit,
    intervalMin: Int,
    nowMs: Long,
): Boolean {
    if (fetchedMs == null || cacheUnit == null) return false
    if (cacheUnit != currentUnit) return false
    return nowMs - fetchedMs < intervalMin * 60_000L
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*StalenessTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/auto/Staleness.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/auto/StalenessTest.kt
git commit -m "Add pure isTempCacheFresh staleness check"
```

---

## Task 3: Prefs — add activeFace, temp cache, custom fields

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt`

This task is additive (keeps `autoSource`) so the project still compiles.

- [ ] **Step 1: Add the new properties**

In `Prefs.kt`, add the following properties after the existing `autoSource` property (keep `autoSource` for now):

```kotlin
    var activeFace: ActiveFace
        get() {
            sp.getString(KEY_ACTIVE_FACE, null)?.let { stored ->
                runCatching { ActiveFace.valueOf(stored) }.getOrNull()?.let { return it }
            }
            val migrated = ActiveFace.fromLegacyAutoSource(sp.getString(KEY_AUTO_SOURCE, null))
            sp.edit { putString(KEY_ACTIVE_FACE, migrated.name) }
            return migrated
        }
        set(value) = sp.edit { putString(KEY_ACTIVE_FACE, value.name) }

    var tempValue: Double?
        get() = if (sp.contains(KEY_TEMP_VALUE)) sp.getFloat(KEY_TEMP_VALUE, 0f).toDouble() else null
        set(value) = sp.edit { if (value == null) remove(KEY_TEMP_VALUE) else putFloat(KEY_TEMP_VALUE, value.toFloat()) }

    var tempCacheUnit: TempUnit?
        get() = sp.getString(KEY_TEMP_CACHE_UNIT, null)
            ?.let { runCatching { TempUnit.valueOf(it) }.getOrNull() }
        set(value) = sp.edit { if (value == null) remove(KEY_TEMP_CACHE_UNIT) else putString(KEY_TEMP_CACHE_UNIT, value.name) }

    var tempFetchedMs: Long?
        get() = if (sp.contains(KEY_TEMP_FETCHED_MS)) sp.getLong(KEY_TEMP_FETCHED_MS, 0L) else null
        set(value) = sp.edit { if (value == null) remove(KEY_TEMP_FETCHED_MS) else putLong(KEY_TEMP_FETCHED_MS, value) }

    var customText: String
        get() = sp.getString(KEY_CUSTOM_TEXT, "") ?: ""
        set(value) = sp.edit { putString(KEY_CUSTOM_TEXT, value) }

    var customSentMs: Long?
        get() = if (sp.contains(KEY_CUSTOM_SENT_MS)) sp.getLong(KEY_CUSTOM_SENT_MS, 0L) else null
        set(value) = sp.edit { if (value == null) remove(KEY_CUSTOM_SENT_MS) else putLong(KEY_CUSTOM_SENT_MS, value) }

    /** Stamp the cached temperature value, the unit it was fetched in, and the fetch time. */
    fun recordTempFetch(value: Double, unit: TempUnit) {
        tempValue = value
        tempCacheUnit = unit
        tempFetchedMs = System.currentTimeMillis()
    }
```

- [ ] **Step 2: Add the import and the new key constants**

Add the import near the top (alongside the existing `ActiveFace` import line is not present yet — add it):

```kotlin
import com.blizzardcaron.freeolleefaces.auto.ActiveFace
```

(The existing `import com.blizzardcaron.freeolleefaces.auto.AutoSource` stays.)

Inside the `companion object`, add these constants (keep all existing ones, including `KEY_AUTO_SOURCE`):

```kotlin
        private const val KEY_ACTIVE_FACE = "active_face"
        private const val KEY_TEMP_VALUE = "temp_value"
        private const val KEY_TEMP_CACHE_UNIT = "temp_cache_unit"
        private const val KEY_TEMP_FETCHED_MS = "temp_fetched_ms"
        private const val KEY_CUSTOM_TEXT = "custom_text"
        private const val KEY_CUSTOM_SENT_MS = "custom_sent_ms"
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (additive change; `autoSource` still present).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt
git commit -m "Add activeFace, temp cache, and custom-string prefs"
```

---

## Task 4: Scheduler & worker key off activeFace

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduler.kt:30-54`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt:26-86`

- [ ] **Step 1: Switch the scheduler's `when` to activeFace**

In `AutoUpdateScheduler.reschedule`, replace the `when (prefs.autoSource) { ... }` block (lines ~30-54) with:

```kotlin
        when (prefs.activeFace) {
            ActiveFace.CUSTOM -> wm.cancelUniqueWork(WORK_NAME)

            ActiveFace.TEMPERATURE -> {
                val now = ZonedDateTime.now(ZoneId.systemDefault())
                val sleep = if (prefs.sleepEnabled) {
                    SleepWindow(prefs.sleepStartMin, prefs.sleepEndMin)
                } else null
                val interval = prefs.tempIntervalMinutes
                val nowMinOfDay = now.hour * 60 + now.minute
                val inSleep = sleep != null &&
                    AutoUpdateSchedule.isInSleepWindow(nowMinOfDay, sleep.startMin, sleep.endMin)
                val overdue = prefs.lastAutoSendMs
                    ?.let { it + interval * 60_000L <= System.currentTimeMillis() } ?: true
                val delayMs = if (overdue && !inSleep) {
                    0L
                } else {
                    val fire = AutoUpdateSchedule.nextTemperatureFire(now, interval, sleep)
                    Duration.between(now, fire).toMillis().coerceAtLeast(0)
                }
                enqueueNext(ctx, delayMs, sunAttempt = 0)
            }

            ActiveFace.SUN -> enqueueNext(ctx, 0L, sunAttempt = 0)
        }
```

- [ ] **Step 2: Switch the worker to activeFace and record the temp fetch**

In `AutoUpdateWorker.doWork`, replace lines ~30-47 with:

```kotlin
        val face = prefs.activeFace
        val lat = prefs.lastLat
        val lng = prefs.lastLng
        val address = prefs.watchAddress

        // CUSTOM has no schedule; stop the chain. Enough config required to send.
        if (face == ActiveFace.CUSTOM) return Result.success()
        if (lat == null || lng == null || address == null) {
            prefs.recordAutoSend("Skipped: set location/watch in app")
            return Result.success()
        }

        val now = ZonedDateTime.now(ZoneId.systemDefault())
        return when (face) {
            ActiveFace.TEMPERATURE -> runTemperature(ctx, prefs, lat, lng, address, now)
            ActiveFace.SUN -> runSun(ctx, prefs, lat, lng, address, now)
            ActiveFace.CUSTOM -> Result.success()
        }
```

In `runTemperature`, inside the `.onSuccess { temp -> ... }` block (line ~68), add the cache stamp as the first line so the Home "Updated" reflects the background fetch:

```kotlin
                .onSuccess { temp ->
                    prefs.recordTempFetch(temp, prefs.tempUnit)
                    val payload = DisplayFormatter.temperature(temp, prefs.tempUnit)
                    OlleeBleClient(ctx).send(address, payload)
                        .onSuccess { prefs.recordAutoSend("Sent '$payload'") }
                        .onFailure { prefs.recordAutoSend("Skipped: watch unreachable") }
                }
```

- [ ] **Step 3: Fix imports**

In both files, remove `import com.blizzardcaron.freeolleefaces.auto.AutoSource` if present (these are same-package files, so `AutoSource`/`ActiveFace` need no import — confirm no dangling `AutoSource` references remain). `ActiveFace` is in the same `auto` package, so no import is needed.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (`Prefs.autoSource` is now unused by these files but still referenced by the old UI — that's fine until Task 7.)

- [ ] **Step 5: Run the full unit suite (no regressions)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — including the unchanged `AutoUpdateScheduleTest`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduler.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt
git commit -m "Drive auto-update chain off activeFace; cache temp on background fetch"
```

---

## Task 5: Screen type + Faces list screen

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/Screen.kt`
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/FacesListScreen.kt`

These are new, standalone composables not yet wired into `MainActivity`; the project still builds with the old `MainScreen` in place.

- [ ] **Step 1: Create the Screen type**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/Screen.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ui

sealed interface Screen {
    data object Home : Screen
    data object FacesList : Screen
}
```

- [ ] **Step 2: Create the Faces list screen**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/FacesListScreen.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.auto.ActiveFace
import androidx.compose.foundation.layout.Column

@Composable
fun FacesListScreen(
    active: ActiveFace,
    onSelect: (ActiveFace) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Faces", style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider()
        FaceRow("Temperature", ActiveFace.TEMPERATURE, active, onSelect)
        FaceRow("Sun event", ActiveFace.SUN, active, onSelect)
        FaceRow("Custom", ActiveFace.CUSTOM, active, onSelect)
    }
}

@Composable
private fun FaceRow(
    label: String,
    face: ActiveFace,
    active: ActiveFace,
    onSelect: (ActiveFace) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(face) }
            .padding(vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = face == active, onClick = { onSelect(face) })
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Text("›", style = MaterialTheme.typography.bodyLarge)
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ui/Screen.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/ui/FacesListScreen.kt
git commit -m "Add Screen type and Faces list switcher screen"
```

---

## Task 6: Home screen (chrome + per-face bodies + location fallback)

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt`

New standalone file; not wired into `MainActivity` until Task 7. The Home value text reuses the existing `PreviewState.Ready.human` string rather than introducing new value formatting.

- [ ] **Step 1: Create HomeScreen with state, callbacks, scaffold, and bodies**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.auto.ActiveFace
import com.blizzardcaron.freeolleefaces.format.TempUnit

data class HomeState(
    val activeFace: ActiveFace = ActiveFace.TEMPERATURE,
    val watchLabel: String = "Watch: none selected",
    val watchSelected: Boolean = false,
    val status: String = "Ready.",
    val sending: Boolean = false,

    val tempUnit: TempUnit = TempUnit.FAHRENHEIT,
    val tempPreview: PreviewState = PreviewState.WaitingForCoords,
    val tempUpdated: String? = null,
    val tempNext: String? = null,
    val tempIntervalText: String = "60",
    val sleepEnabled: Boolean = true,
    val sleepStartMin: Int = 22 * 60,
    val sleepEndMin: Int = 6 * 60,

    val sunPreview: PreviewState = PreviewState.WaitingForCoords,
    val sunUpdated: String? = null,
    val sunNext: String? = null,

    val custom: String = "",
    val customSent: String? = null,

    val showLocationFallback: Boolean = false,
    val lat: String = "",
    val lng: String = "",
)

data class HomeCallbacks(
    val onOpenFaces: () -> Unit,
    val onSelectWatch: () -> Unit,
    val onUpdateNow: () -> Unit,
    val onTempUnitChange: (TempUnit) -> Unit,
    val onTempIntervalChange: (String) -> Unit,
    val onSleepEnabledChange: (Boolean) -> Unit,
    val onSleepStartChange: (Int) -> Unit,
    val onSleepEndChange: (Int) -> Unit,
    val onCustomChange: (String) -> Unit,
    val onSendCustom: () -> Unit,
    val onLatChange: (String) -> Unit,
    val onLngChange: (String) -> Unit,
    val onUseMyLocation: () -> Unit,
)

@Composable
fun HomeScreen(
    state: HomeState,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(faceTitle(state.activeFace), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = callbacks.onOpenFaces) { Text("Faces") }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(state.watchLabel, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = callbacks.onSelectWatch) {
                Text(if (state.watchSelected) "change" else "select")
            }
        }

        HorizontalDivider()

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.activeFace) {
                ActiveFace.TEMPERATURE -> TemperatureBody(state, callbacks)
                ActiveFace.SUN -> SunBody(state, callbacks)
                ActiveFace.CUSTOM -> CustomBody(state, callbacks)
            }
        }

        Text(state.status, style = MaterialTheme.typography.bodySmall)
    }
}

private fun faceTitle(face: ActiveFace): String = when (face) {
    ActiveFace.TEMPERATURE -> "Temperature"
    ActiveFace.SUN -> "Sun event"
    ActiveFace.CUSTOM -> "Custom"
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

@Composable
private fun LocationFallback(state: HomeState, callbacks: HomeCallbacks) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Location unavailable", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = state.lat,
                onValueChange = callbacks.onLatChange,
                label = { Text("Latitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.lng,
                onValueChange = callbacks.onLngChange,
                label = { Text("Longitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = callbacks.onUseMyLocation, modifier = Modifier.fillMaxWidth()) {
                Text("Grant location")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemperatureBody(state: HomeState, callbacks: HomeCallbacks) {
    if (state.showLocationFallback) LocationFallback(state, callbacks)
    FaceValue(state.tempPreview, state.tempUpdated, state.tempNext)
    Button(onClick = callbacks.onUpdateNow, modifier = Modifier.fillMaxWidth()) { Text("Update now") }
    HorizontalDivider()
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
    OutlinedTextField(
        value = state.tempIntervalText,
        onValueChange = callbacks.onTempIntervalChange,
        label = { Text("Every (minutes, min 15)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Power-saving sleep")
        Switch(checked = state.sleepEnabled, onCheckedChange = callbacks.onSleepEnabledChange)
    }
    if (state.sleepEnabled) {
        val context = LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { showTimePicker(context, state.sleepStartMin, callbacks.onSleepStartChange) },
                modifier = Modifier.weight(1f),
            ) { Text("From ${minutesToLabel(state.sleepStartMin)}") }
            OutlinedButton(
                onClick = { showTimePicker(context, state.sleepEndMin, callbacks.onSleepEndChange) },
                modifier = Modifier.weight(1f),
            ) { Text("To ${minutesToLabel(state.sleepEndMin)}") }
        }
    }
}

@Composable
private fun SunBody(state: HomeState, callbacks: HomeCallbacks) {
    if (state.showLocationFallback) LocationFallback(state, callbacks)
    FaceValue(state.sunPreview, state.sunUpdated, state.sunNext)
    Button(onClick = callbacks.onUpdateNow, modifier = Modifier.fillMaxWidth()) { Text("Update now") }
}

@Composable
private fun CustomBody(state: HomeState, callbacks: HomeCallbacks) {
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

private fun minutesToLabel(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

private fun showTimePicker(
    context: android.content.Context,
    currentMin: Int,
    onPicked: (Int) -> Unit,
) {
    android.app.TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(hour * 60 + minute) },
        currentMin / 60,
        currentMin % 60,
        true,
    ).show()
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (`TemperatureBody` carries `@OptIn(ExperimentalMaterial3Api::class)` for the segmented-button APIs, matching the existing `MainScreen`. The experimental usage is contained in `TemperatureBody`, so `HomeScreen` itself needs no opt-in.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt
git commit -m "Add Home screen with per-face bodies and location fallback"
```

---

## Task 7: Rewrite MainActivity; delete old UI and AutoSource

**Files:**
- Modify (full rewrite): `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt` (remove `autoSource`)
- Delete: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/MainScreen.kt`
- Delete: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoSource.kt`

- [ ] **Step 1: Replace MainActivity.kt with the new screen-driven root**

Overwrite `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt` with:

```kotlin
package com.blizzardcaron.freeolleefaces

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.blizzardcaron.freeolleefaces.auto.ActiveFace
import com.blizzardcaron.freeolleefaces.auto.AutoUpdateSchedule
import com.blizzardcaron.freeolleefaces.auto.AutoUpdateScheduler
import com.blizzardcaron.freeolleefaces.auto.SleepWindow
import com.blizzardcaron.freeolleefaces.auto.isTempCacheFresh
import com.blizzardcaron.freeolleefaces.ble.OlleeBleClient
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.format.WeatherErrorCopy
import com.blizzardcaron.freeolleefaces.location.LocationSource
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.sun.SunCalc
import com.blizzardcaron.freeolleefaces.ui.BondedDevicesDialog
import com.blizzardcaron.freeolleefaces.ui.HomeCallbacks
import com.blizzardcaron.freeolleefaces.ui.HomeScreen
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.blizzardcaron.freeolleefaces.ui.FacesListScreen
import com.blizzardcaron.freeolleefaces.ui.PreviewState
import com.blizzardcaron.freeolleefaces.ui.Screen
import com.blizzardcaron.freeolleefaces.ui.theme.FreeOlleeFacesTheme
import com.blizzardcaron.freeolleefaces.weather.OpenMeteoClient
import com.blizzardcaron.freeolleefaces.weather.RetryPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreeOlleeFacesTheme {
                Scaffold { inner ->
                    AppRoot(Modifier.padding(inner))
                }
            }
        }
    }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

private fun clockTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(CLOCK)

@Composable
private fun AppRoot(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val ble = remember { OlleeBleClient(context) }
    val locationSource = remember { LocationSource(context) }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var showPicker by remember { mutableStateOf(false) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    var state by remember {
        mutableStateOf(
            HomeState(
                activeFace = prefs.activeFace,
                lat = prefs.lastLat?.toString() ?: "",
                lng = prefs.lastLng?.toString() ?: "",
                watchLabel = labelForAddress(context, prefs.watchAddress),
                watchSelected = prefs.watchAddress != null,
                tempUnit = prefs.tempUnit,
                tempIntervalText = prefs.tempIntervalMinutes.toString(),
                sleepEnabled = prefs.sleepEnabled,
                sleepStartMin = prefs.sleepStartMin,
                sleepEndMin = prefs.sleepEndMin,
                custom = prefs.customText,
                customSent = prefs.customSentMs?.let { "Sent '${prefs.customText}' at ${clockTime(it)}" },
            )
        )
    }

    fun update(transform: (HomeState) -> HomeState) { state = transform(state) }

    fun validCoords(): Pair<Double, Double>? {
        val lat = state.lat.toDoubleOrNull(); val lng = state.lng.toDoubleOrNull()
        return if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
            lat to lng
        } else null
    }

    fun pushIfWatch(payload: String) {
        val addr = prefs.watchAddress ?: return
        scope.launch { sendAndReport(ble, addr, payload, ::update) }
    }

    fun interval(): Int = state.tempIntervalText.toIntOrNull()?.coerceAtLeast(15) ?: 60

    fun tempNextText(): String {
        val sleep = if (state.sleepEnabled) SleepWindow(state.sleepStartMin, state.sleepEndMin) else null
        val fire = AutoUpdateSchedule.nextTemperatureFire(
            ZonedDateTime.now(ZoneId.systemDefault()), interval(), sleep,
        )
        return "Next update ${fire.format(CLOCK)}"
    }

    fun refreshTemp(force: Boolean, push: Boolean) {
        val c = validCoords()
        if (c == null) {
            update { it.copy(tempPreview = PreviewState.Error("Enter coordinates to see temperature")) }
            return
        }
        val (lat, lng) = c
        if (!force && isTempCacheFresh(
                prefs.tempFetchedMs, prefs.tempCacheUnit, state.tempUnit, interval(), System.currentTimeMillis(),
            )
        ) {
            val cached = prefs.tempValue!!
            val payload = DisplayFormatter.temperature(cached, state.tempUnit)
            val human = "Currently: %.1f°%s".format(Locale.US, cached, state.tempUnit.symbol)
            update { it.copy(
                tempPreview = PreviewState.Ready(payload, human),
                tempUpdated = "Updated ${clockTime(prefs.tempFetchedMs!!)}",
                tempNext = tempNextText(),
            ) }
            if (push) pushIfWatch(payload)
            return
        }
        refreshJob?.cancel()
        refreshJob = scope.launch {
            update { it.copy(tempPreview = PreviewState.Loading) }
            OpenMeteoClient.currentTemp(lat, lng, state.tempUnit, RetryPolicy.Preview)
                .onSuccess { temp ->
                    prefs.recordTempFetch(temp, state.tempUnit)
                    val payload = DisplayFormatter.temperature(temp, state.tempUnit)
                    val human = "Currently: %.1f°%s".format(Locale.US, temp, state.tempUnit.symbol)
                    update { it.copy(
                        tempPreview = PreviewState.Ready(payload, human),
                        tempUpdated = "Updated ${clockTime(prefs.tempFetchedMs!!)}",
                        tempNext = tempNextText(),
                    ) }
                    if (push) pushIfWatch(payload)
                }
                .onFailure { err ->
                    update { it.copy(tempPreview = PreviewState.Error(WeatherErrorCopy.describe(err))) }
                }
        }
    }

    fun refreshSun(push: Boolean) {
        val c = validCoords()
        if (c == null) {
            update { it.copy(sunPreview = PreviewState.Error("Enter coordinates to see sun event")) }
            return
        }
        val (lat, lng) = c
        val event = SunCalc.nextEvent(Instant.now(), lat, lng, ZoneId.systemDefault())
        if (event == null) {
            update { it.copy(sunPreview = PreviewState.NoEvent, sunNext = null) }
            return
        }
        val payload = DisplayFormatter.sunTime(event.kind, event.time.toLocalTime())
        val pretty = event.time.format(CLOCK)
        val kindLabel = event.kind.name.lowercase().replaceFirstChar { it.uppercase() }
        update { it.copy(
            sunPreview = PreviewState.Ready(payload, "$kindLabel at $pretty"),
            sunUpdated = "Updated ${clockTime(System.currentTimeMillis())}",
            sunNext = "Next: $kindLabel at $pretty",
        ) }
        if (push) pushIfWatch(payload)
    }

    fun refreshActive(force: Boolean, push: Boolean) {
        when (state.activeFace) {
            ActiveFace.TEMPERATURE -> refreshTemp(force, push)
            ActiveFace.SUN -> refreshSun(push)
            ActiveFace.CUSTOM -> {}
        }
    }

    fun activate(face: ActiveFace) {
        prefs.activeFace = face
        update { it.copy(activeFace = face) }
        screen = Screen.Home
        AutoUpdateScheduler.reschedule(context)
        when (face) {
            ActiveFace.TEMPERATURE -> refreshTemp(force = false, push = true)
            ActiveFace.SUN -> refreshSun(push = true)
            ActiveFace.CUSTOM -> {
                val text = prefs.customText
                if (text.isNotEmpty()) {
                    prefs.customSentMs = System.currentTimeMillis()
                    update { it.copy(customSent = "Sent '$text' at ${clockTime(prefs.customSentMs!!)}") }
                    pushIfWatch(DisplayFormatter.custom(text))
                }
            }
        }
    }

    fun onCoordEdit(lat: String, lng: String) {
        update { it.copy(lat = lat, lng = lng) }
        val latD = lat.toDoubleOrNull(); val lngD = lng.toDoubleOrNull()
        if (latD != null && lngD != null && latD in -90.0..90.0 && lngD in -180.0..180.0) {
            prefs.lastLat = latD; prefs.lastLng = lngD
        }
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(500)
            refreshActive(force = false, push = false)
        }
    }

    LaunchedEffect(Unit) {
        val hasAnyLocation =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (hasAnyLocation) {
            update { it.copy(status = "Getting location fix…") }
            locationSource.fetch()
                .onSuccess { coords ->
                    prefs.lastLat = coords.lat; prefs.lastLng = coords.lng
                    update { it.copy(
                        lat = coords.lat.toString(),
                        lng = coords.lng.toString(),
                        showLocationFallback = false,
                        status = "Got fix: %.4f, %.4f".format(coords.lat, coords.lng),
                    ) }
                }
                .onFailure {
                    update { it.copy(
                        showLocationFallback = state.activeFace != ActiveFace.CUSTOM,
                        status = "Location failed. Using saved coordinates.",
                    ) }
                }
        } else {
            update { it.copy(
                showLocationFallback = state.activeFace != ActiveFace.CUSTOM,
                status = "Location unavailable.",
            ) }
        }
        refreshActive(force = false, push = false)
        AutoUpdateScheduler.reschedule(context)
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showPicker = true
        else update { it.copy(status = "Bluetooth permission denied — can't list paired watches.") }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            scope.launch {
                update { it.copy(status = "Getting location fix…") }
                locationSource.fetch()
                    .onSuccess { coords ->
                        prefs.lastLat = coords.lat; prefs.lastLng = coords.lng
                        update { it.copy(
                            lat = coords.lat.toString(),
                            lng = coords.lng.toString(),
                            showLocationFallback = false,
                            status = "Got fix: %.4f, %.4f".format(coords.lat, coords.lng),
                        ) }
                        refreshActive(force = true, push = false)
                    }
                    .onFailure { err -> update { it.copy(status = "Location failed: ${err.message}") } }
            }
        } else {
            update { it.copy(status = "Location permission denied — enter coordinates manually.") }
        }
    }

    val callbacks = HomeCallbacks(
        onOpenFaces = { screen = Screen.FacesList },
        onSelectWatch = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
            ) showPicker = true
            else btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        },
        onUpdateNow = { refreshActive(force = true, push = true) },
        onTempUnitChange = { newUnit ->
            prefs.tempUnit = newUnit
            update { it.copy(tempUnit = newUnit) }
            refreshTemp(force = false, push = state.activeFace == ActiveFace.TEMPERATURE)
        },
        onTempIntervalChange = { text ->
            update { it.copy(tempIntervalText = text) }
            val mins = text.toIntOrNull()
            if (mins != null) {
                prefs.tempIntervalMinutes = mins.coerceAtLeast(15)
                AutoUpdateScheduler.reschedule(context)
                update { it.copy(tempNext = tempNextText()) }
            }
        },
        onSleepEnabledChange = { enabled ->
            prefs.sleepEnabled = enabled
            update { it.copy(sleepEnabled = enabled, tempNext = tempNextText()) }
            AutoUpdateScheduler.reschedule(context)
        },
        onSleepStartChange = { min ->
            prefs.sleepStartMin = min
            update { it.copy(sleepStartMin = min, tempNext = tempNextText()) }
            AutoUpdateScheduler.reschedule(context)
        },
        onSleepEndChange = { min ->
            prefs.sleepEndMin = min
            update { it.copy(sleepEndMin = min, tempNext = tempNextText()) }
            AutoUpdateScheduler.reschedule(context)
        },
        onCustomChange = { text ->
            update { it.copy(custom = text) }
            prefs.customText = text
        },
        onSendCustom = {
            val addr = prefs.watchAddress ?: return@HomeCallbacks
            val text = state.custom
            prefs.customText = text
            scope.launch {
                sendAndReport(ble, addr, DisplayFormatter.custom(text), ::update)
                prefs.customSentMs = System.currentTimeMillis()
                update { it.copy(customSent = "Sent '$text' at ${clockTime(prefs.customSentMs!!)}") }
            }
        },
        onLatChange = { onCoordEdit(it, state.lng) },
        onLngChange = { onCoordEdit(state.lat, it) },
        onUseMyLocation = {
            val hasAny =
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            if (hasAny) {
                scope.launch {
                    update { it.copy(status = "Getting location fix…") }
                    locationSource.fetch()
                        .onSuccess { coords ->
                            prefs.lastLat = coords.lat; prefs.lastLng = coords.lng
                            update { it.copy(
                                lat = coords.lat.toString(),
                                lng = coords.lng.toString(),
                                showLocationFallback = false,
                                status = "Got fix: %.4f, %.4f".format(coords.lat, coords.lng),
                            ) }
                            refreshActive(force = true, push = false)
                        }
                        .onFailure { err -> update { it.copy(status = "Location failed: ${err.message}") } }
                }
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
            }
        },
    )

    when (screen) {
        Screen.Home -> HomeScreen(state = state, callbacks = callbacks, modifier = modifier)
        Screen.FacesList -> FacesListScreen(
            active = state.activeFace,
            onSelect = { activate(it) },
            onBack = { screen = Screen.Home },
            modifier = modifier,
        )
    }

    if (showPicker) {
        val devices = bondedDevices(context)
        BondedDevicesDialog(
            devices = devices,
            onPick = { device ->
                prefs.watchAddress = device.address
                update { it.copy(
                    watchLabel = "Watch: ${device.name ?: device.address}",
                    watchSelected = true,
                    status = "Selected ${device.name ?: device.address}.",
                ) }
                showPicker = false
                refreshActive(force = false, push = true)
            },
            onDismiss = { showPicker = false },
        )
    }
}

private suspend fun sendAndReport(
    ble: OlleeBleClient,
    address: String,
    value: String,
    update: ((HomeState) -> HomeState) -> Unit,
) {
    update { it.copy(status = "Sending '$value'…", sending = true) }
    ble.send(address, value)
        .onSuccess { update { it.copy(sending = false, status = "Sent '$value'.") } }
        .onFailure { err -> update { it.copy(sending = false, status = "Send failed: ${err.message}") } }
}

@SuppressLint("MissingPermission")
private fun bondedDevices(context: Context): List<BluetoothDevice> {
    val mgr = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
    val adapter = mgr.adapter ?: return emptyList()
    if (!adapter.isEnabled) return emptyList()
    return adapter.bondedDevices?.toList().orEmpty()
}

@SuppressLint("MissingPermission")
private fun labelForAddress(context: Context, address: String?): String {
    if (address == null) return "Watch: none selected"
    val mgr = context.getSystemService(BluetoothManager::class.java) ?: return "Watch: $address"
    val device = mgr.adapter?.getRemoteDevice(address)
    return "Watch: ${device?.name ?: address}"
}
```

- [ ] **Step 2: Remove the obsolete `autoSource` property from Prefs**

In `Prefs.kt`, delete the `autoSource` property (the `var autoSource: AutoSource ...` block, lines ~32-36) and remove `import com.blizzardcaron.freeolleefaces.auto.AutoSource`. Keep `KEY_AUTO_SOURCE` in the companion object (the `activeFace` getter reads it for migration). Keep the `import com.blizzardcaron.freeolleefaces.auto.ActiveFace`.

- [ ] **Step 3: Delete the old UI and AutoSource files**

```bash
git rm app/src/main/java/com/blizzardcaron/freeolleefaces/ui/MainScreen.kt
git rm app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoSource.kt
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL with no references to `AutoSource`, `MainScreen`, `MainScreenState`, or `MainScreenCallbacks`. If the compiler reports any lingering reference, grep for it: `grep -rn "AutoSource\|MainScreen" app/src/main` and resolve.

- [ ] **Step 5: Run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (ActiveFaceTest, StalenessTest, and all pre-existing tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt
git commit -m "Wire active-face Home/FacesList navigation; remove AutoSource and old MainScreen"
```

---

## Task 8: Manual verification checklist

The Compose UI has no automated tests (consistent with the codebase, and the dev runs headless over SSH). Capture a checklist to run on a device.

**Files:**
- Create: `docs/superpowers/verification-active-face-redesign.md`

- [ ] **Step 1: Write the checklist**

Create `docs/superpowers/verification-active-face-redesign.md`:

```markdown
# Manual Verification — Active-Face UI Redesign

Build & install: `./gradlew :app:assembleDebug` then install the APK from
`app/build/outputs/apk/debug/`.

## Launch & default view
- [ ] App opens on the Home view showing the previously-active face (fresh install: Temperature).
- [ ] Upgrading from a build that had auto-update OFF lands on Temperature (migration).
- [ ] Upgrading from auto-update Temperature/Sun lands on that same face.

## Faces list & switching
- [ ] Tapping "Faces" opens the list; the active face shows a filled radio.
- [ ] Tapping a different face returns Home immediately on that face.
- [ ] Device back button from the list returns Home without changing the active face.

## Active = auto = watch
- [ ] Selecting Temperature/Sun starts auto-updates (verify a scheduled push lands on the watch).
- [ ] Selecting Custom stops auto-updates (no further scheduled pushes).

## Staleness on activation
- [ ] Activating Temperature with a recent (< interval) cached value pushes without a visible reload.
- [ ] Activating Temperature with a stale (> interval) value shows "Loading…" then a fresh value.
- [ ] "Update now" always reloads and pushes, even when the value is fresh.

## Timestamps
- [ ] Temperature shows "Updated <time>" and "Next update <time>".
- [ ] Changing the interval updates the "Next update" time.
- [ ] Sun shows "Updated <time>" and "Next: <sunrise/sunset> at <time>".

## Custom face
- [ ] Typing + Send pushes the string and shows "Sent '<text>' at <time>".
- [ ] The custom string survives an app relaunch (persisted).
- [ ] Activating Custom with a saved string re-pushes it; with none, it waits for input.

## Location fallback
- [ ] With location permission granted and a successful fix, no lat/lng fields appear.
- [ ] With permission denied (or a failed fix), the "Location unavailable" block with lat/lng appears on Temperature/Sun.
- [ ] Entering coordinates manually refreshes the value; "Grant location" re-requests permission.
- [ ] The fallback never appears on the Custom face.

## Watch selection
- [ ] "select/change" opens the bonded-devices dialog; picking a watch updates the label.
- [ ] After selecting a watch, the current active face is pushed to it.
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/verification-active-face-redesign.md
git commit -m "Add manual verification checklist for active-face redesign"
```

---

## Self-review notes (addressed in this plan)

- **Spec coverage:** active-face model (Tasks 1,3,4,7); fast-switch list (Task 5,7); per-face config folded in (Task 6); timestamps + next-update (Tasks 3,6,7); location fallback on failure only (Tasks 6,7); no off-state — CUSTOM cancels (Tasks 1,4,7); Approach-A navigation (Tasks 5,7); migration default TEMPERATURE (Tasks 1,3); temp `Float` storage (Task 3); pure-function migration test (Task 1).
- **Value text simplification:** the Home hero value reuses `PreviewState.Ready.human` ("Currently: 72.3°F" / "Sunset at 8:12 PM") rather than the mockups' exact "72 °F" — layout matches; no new untested formatting introduced.
- **Launch does not push:** opening the app refreshes the preview only; the auto-update chain keeps the watch current. Pushes happen on activation, "Update now", Custom Send, and watch selection.
```
