# Hardening: Location Caching, In-Context Status, Background Error Notifications — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make location cached-first with visible freshness, fold the bottom status line into context, and raise self-clearing notifications when background updates fail.

**Architecture:** Three slices over the existing active-face app. §1 adds a pure freshness helper + a Prefs timestamp + a Home location line, and gates the launch-time GPS fix on staleness. §2 removes the global status string and routes every signal to context (location line, value area, inline, or a Material Snackbar). §3 adds a pure notify-decision core plus a thin `ErrorNotifier`, wired into the WorkManager worker, with transition-only firing and auto-clear. Pure logic is unit-tested (codebase pattern); Compose/Android glue is verified by `assembleDebug` + a manual checklist.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), WorkManager, AndroidX Core (`NotificationManagerCompat`), JUnit4. Builds on the ARM64 Pi via `./gradlew` (all pure-AAR deps; no native code).

**Spec:** `docs/superpowers/specs/2026-05-29-hardening-location-status-notifications-design.md`

---

## File Structure

**New files**
- `app/src/main/java/com/blizzardcaron/freeolleefaces/location/LocationFreshness.kt` — pure staleness check + human freshness label.
- `app/src/test/java/com/blizzardcaron/freeolleefaces/location/LocationFreshnessTest.kt` — tests for the above.
- `app/src/main/java/com/blizzardcaron/freeolleefaces/notify/NotifyDecision.kt` — `FailureKind`, `NotifyAction`, pure `decide(...)`.
- `app/src/test/java/com/blizzardcaron/freeolleefaces/notify/NotifyDecisionTest.kt` — tests for `decide`.
- `app/src/main/java/com/blizzardcaron/freeolleefaces/notify/ErrorNotifier.kt` — channel + single-id post/clear + tap intent + per-kind copy.

**Modified files**
- `prefs/Prefs.kt` — add `lastLocationFetchedMs` and `lastNotifiedKind` (+ keys).
- `ui/HomeScreen.kt` — add the location line; remove the bottom status `Text`; `HomeState` field changes.
- `MainActivity.kt` — Snackbar host + `showSnackbar`; remove `status`; freshness-gated launch logic; stamp the fetch time; lazy `POST_NOTIFICATIONS` request.
- `auto/AutoUpdateWorker.kt` — compute `FailureKind?` at each outcome; apply the notify decision.
- `AndroidManifest.xml` — `POST_NOTIFICATIONS` permission.

**Task order:** 1 → 9, in sequence. Tasks 1–2 are independent; Task 3 is the big MainActivity/HomeScreen integration (state-shape change); Task 4 changes the launch policy; Tasks 5–9 are the additive notification slice.

---

## Task 1: Pure location-freshness helper

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/location/LocationFreshness.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/location/LocationFreshnessTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/blizzardcaron/freeolleefaces/location/LocationFreshnessTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationFreshnessTest {

    private val now = 1_000_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Test fun staleWhenNeverFetched() {
        assertTrue(isLocationStale(fetchedMs = null, nowMs = now))
    }

    @Test fun freshWithinWeek() {
        assertFalse(isLocationStale(fetchedMs = now - 3 * day, nowMs = now))
    }

    @Test fun staleAtOrPastWeek() {
        assertTrue(isLocationStale(fetchedMs = now - 7 * day, nowMs = now))
        assertTrue(isLocationStale(fetchedMs = now - 8 * day, nowMs = now))
    }

    @Test fun labelNullWhenNeverFetched() {
        assertNull(freshnessLabel(fetchedMs = null, nowMs = now))
    }

    @Test fun labelBuckets() {
        assertEquals("just now", freshnessLabel(now - 30_000L, now))
        assertEquals("5m ago", freshnessLabel(now - 5 * 60_000L, now))
        assertEquals("3h ago", freshnessLabel(now - 3 * 60 * 60_000L, now))
        assertEquals("5d ago", freshnessLabel(now - 5 * day, now))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*LocationFreshnessTest*"`
Expected: FAIL — compilation error, `isLocationStale` / `freshnessLabel` unresolved.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/location/LocationFreshness.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.location

/** Saved coordinates older than this are silently re-fetched on launch. */
const val LOCATION_STALE_MS: Long = 7L * 24 * 60 * 60 * 1000

/** True when there is no saved fetch time, or it is at least [staleMs] old. */
fun isLocationStale(fetchedMs: Long?, nowMs: Long, staleMs: Long = LOCATION_STALE_MS): Boolean {
    if (fetchedMs == null) return true
    return nowMs - fetchedMs >= staleMs
}

/** Human "age" of a saved fix, or null if never fetched. e.g. "just now", "5m ago", "3h ago", "5d ago". */
fun freshnessLabel(fetchedMs: Long?, nowMs: Long): String? {
    if (fetchedMs == null) return null
    val min = (nowMs - fetchedMs).coerceAtLeast(0) / 60_000L
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 60 * 24 -> "${min / 60}h ago"
        else -> "${min / (60 * 24)}d ago"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*LocationFreshnessTest*"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/location/LocationFreshness.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/location/LocationFreshnessTest.kt
git commit -m "Add pure location-freshness helper (staleness + age label)"
```

---

## Task 2: Persist the location fetch time

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt`

No unit test — `Prefs` is an untested Android glue class in this codebase (consistent with `tempFetchedMs` et al.).

- [ ] **Step 1: Add the property**

In `Prefs.kt`, after the `tempFetchedMs` property block (around line 54), add:

```kotlin
    var lastLocationFetchedMs: Long?
        get() = if (sp.contains(KEY_LOCATION_FETCHED_MS)) sp.getLong(KEY_LOCATION_FETCHED_MS, 0L) else null
        set(value) = sp.edit { if (value == null) remove(KEY_LOCATION_FETCHED_MS) else putLong(KEY_LOCATION_FETCHED_MS, value) }
```

- [ ] **Step 2: Add the key constant**

In the `companion object` (after `KEY_TEMP_FETCHED_MS`, around line 111), add:

```kotlin
        private const val KEY_LOCATION_FETCHED_MS = "location_fetched_ms"
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt
git commit -m "Persist last location fetch time in Prefs"
```

---

## Task 3: Location line + Snackbar; remove the global status string

This task changes the shape of `HomeState` (removes `status`, adds three location fields), so `HomeScreen.kt` and `MainActivity.kt` must change together to keep the build green. **Behavior is otherwise unchanged** — the launch logic still fetches on every launch; Task 4 changes that policy.

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt`

- [ ] **Step 1: Update `HomeState` fields**

In `HomeScreen.kt`, edit the `HomeState` data class: remove the `status` property (line 39) and add three location fields. The top of the class becomes:

```kotlin
data class HomeState(
    val activeFace: ActiveFace = ActiveFace.TEMPERATURE,
    val watchLabel: String = "Watch: none selected",
    val watchSelected: Boolean = false,
    val sending: Boolean = false,

    val locationLabel: String = "Location: not set",
    val locationFreshness: String? = null,
    val locating: Boolean = false,
```

(Leave the rest of `HomeState` — `tempUnit` through `lng` — unchanged.)

- [ ] **Step 2: Add the location line and remove the bottom status `Text`**

In `HomeScreen.kt`, inside `HomeScreen`, add a location row between the watch `Row` (ends ~line 107) and the `HorizontalDivider()` (line 109). Insert:

```kotlin
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.locating) "Locating…"
                else state.locationLabel + (state.locationFreshness?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = callbacks.onUseMyLocation) {
                Text(if (state.locationFreshness == null) "set" else "refresh")
            }
        }
```

Then delete the final status line of `HomeScreen` (currently line 122):

```kotlin
        Text(state.status, style = MaterialTheme.typography.bodySmall)
```

- [ ] **Step 3: Hoist a `SnackbarHost` into the Scaffold**

In `MainActivity.kt`, add imports near the other Material 3 imports (line 15 area):

```kotlin
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
```

Change the `setContent` Scaffold (lines 55–60) to host a Snackbar:

```kotlin
            FreeOlleeFacesTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { inner ->
                    AppRoot(snackbarHostState, Modifier.padding(inner))
                }
            }
```

- [ ] **Step 4: Thread the host into `AppRoot` and add `showSnackbar`**

In `MainActivity.kt`, change the `AppRoot` signature (line 70) and add a helper right after `scope` is created (after line 75):

```kotlin
@Composable
private fun AppRoot(
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
```

After `val scope = rememberCoroutineScope()`:

```kotlin
    fun showSnackbar(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }
```

- [ ] **Step 5: Initialise the new state fields and drop `status` from the initial state**

In `MainActivity.kt`, the `HomeState(...)` initialiser (lines 83–97) must no longer pass `status` (it was using the default anyway) and should seed the location label. Add these two lines inside the `HomeState(...)` call (e.g. after `watchSelected = ...`):

```kotlin
                locationLabel = locLabel(prefs.lastLat, prefs.lastLng),
                locationFreshness = freshnessLabel(prefs.lastLocationFetchedMs, System.currentTimeMillis()),
```

Add the `locLabel` top-level helper near `clockTime` (after line 67) and the needed import:

```kotlin
import com.blizzardcaron.freeolleefaces.location.freshnessLabel
```

```kotlin
private fun locLabel(lat: Double?, lng: Double?): String =
    if (lat != null && lng != null) "Location: %.4f, %.4f".format(lat, lng) else "Location: not set"
```

- [ ] **Step 6: Reroute every `status =` write**

In `MainActivity.kt`, remove all `status = ...` assignments inside `update { it.copy(...) }` and reroute them:

1. `sendAndReport` (lines 422–432) — drop the `status`/in-progress string; use the Snackbar. Replace the body with:

```kotlin
private suspend fun sendAndReport(
    ble: OlleeBleClient,
    address: String,
    value: String,
    update: ((HomeState) -> HomeState) -> Unit,
    showSnackbar: (String) -> Unit,
): Result<Unit> {
    update { it.copy(sending = true) }
    return ble.send(address, value)
        .onSuccess { update { it.copy(sending = false) }; showSnackbar("Sent '$value'") }
        .onFailure { err -> update { it.copy(sending = false) }; showSnackbar("Send failed: ${err.message}") }
}
```

Update its two call sites (`pushIfWatch` line 112 and `sendCustom` line 119) to pass `::showSnackbar`:

```kotlin
        scope.launch { sendAndReport(ble, addr, payload, ::update, ::showSnackbar) }
```
```kotlin
            val result = sendAndReport(ble, addr, DisplayFormatter.custom(text), ::update, ::showSnackbar)
```

2. BT-permission-denied (line 284): replace
   `else update { it.copy(status = "Bluetooth permission denied — can't list paired watches.") }`
   with `else showSnackbar("Bluetooth permission denied — can't list paired watches.")`.

3. The watch-picker "Selected …" (line 409): drop the `status =` from the `copy` (the `watchLabel` update already shows the name).

4. The `LaunchedEffect` location-fix writes (lines 252–275) and `locationPermissionLauncher` / `onUseMyLocation` fix writes (lines 290–308, 363–378): replace `status = "Getting location fix…"` with `locating = true`; on success set `locating = false`, `locationLabel = locLabel(coords.lat, coords.lng)`, `locationFreshness = "just now"`; on failure set `locating = false` and (for explicit refresh) `showSnackbar("Location failed: ${err.message}")`. Concretely, the success block used in all three places becomes:

```kotlin
                    .onSuccess { coords ->
                        prefs.lastLat = coords.lat; prefs.lastLng = coords.lng
                        prefs.lastLocationFetchedMs = System.currentTimeMillis()
                        update { it.copy(
                            lat = coords.lat.toString(),
                            lng = coords.lng.toString(),
                            showLocationFallback = false,
                            locating = false,
                            locationLabel = locLabel(coords.lat, coords.lng),
                            locationFreshness = "just now",
                        ) }
                        refreshActive(force = true, push = false)
                    }
```

(The `LaunchedEffect` copy of this success block calls `refreshActive` once at its end as today — keep a single trailing `refreshActive(force = false, push = false)`; do not double-fetch. See Task 4, which rewrites this block.)

- [ ] **Step 7: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Grep to confirm the field is gone: `grep -rn "\.status" app/src/main` returns nothing referencing `HomeState.status`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "Add Home location line; route status into Snackbar/location line"
```

---

## Task 4: Cached-first launch policy

Replace the always-fetch launch behavior with freshness gating. Saved coords are used immediately; a fix runs only when they are missing or ≥ 1 week old; a failed silent refresh marks the location line and shows a Snackbar.

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt`

- [ ] **Step 1: Add the import**

```kotlin
import com.blizzardcaron.freeolleefaces.location.isLocationStale
```

- [ ] **Step 2: Rewrite the `LaunchedEffect(Unit)` block**

Replace the entire `LaunchedEffect(Unit) { ... }` (lines 246–278) with:

```kotlin
    LaunchedEffect(Unit) {
        val hasAnyLocation =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val haveCoords = prefs.lastLat != null && prefs.lastLng != null
        val stale = isLocationStale(prefs.lastLocationFetchedMs, System.currentTimeMillis())

        when {
            // Fresh saved coords: use them silently, no fix.
            haveCoords && !stale -> { /* render with saved coords */ }

            // Stale saved coords + permission: render saved, silently refresh.
            haveCoords && hasAnyLocation -> {
                update { it.copy(locating = true) }
                locationSource.fetch()
                    .onSuccess { coords ->
                        prefs.lastLat = coords.lat; prefs.lastLng = coords.lng
                        prefs.lastLocationFetchedMs = System.currentTimeMillis()
                        update { it.copy(
                            lat = coords.lat.toString(),
                            lng = coords.lng.toString(),
                            showLocationFallback = false,
                            locating = false,
                            locationLabel = locLabel(coords.lat, coords.lng),
                            locationFreshness = "just now",
                        ) }
                    }
                    .onFailure {
                        update { it.copy(
                            locating = false,
                            locationFreshness = (freshnessLabel(
                                prefs.lastLocationFetchedMs, System.currentTimeMillis(),
                            ) ?: "stale") + " · refresh failed",
                        ) }
                        showSnackbar("Couldn't refresh location — using saved coordinates")
                    }
            }

            // No saved coords, permission held: first-run fix.
            !haveCoords && hasAnyLocation -> {
                update { it.copy(locating = true) }
                locationSource.fetch()
                    .onSuccess { coords ->
                        prefs.lastLat = coords.lat; prefs.lastLng = coords.lng
                        prefs.lastLocationFetchedMs = System.currentTimeMillis()
                        update { it.copy(
                            lat = coords.lat.toString(),
                            lng = coords.lng.toString(),
                            showLocationFallback = false,
                            locating = false,
                            locationLabel = locLabel(coords.lat, coords.lng),
                            locationFreshness = "just now",
                        ) }
                    }
                    .onFailure {
                        update { it.copy(
                            locating = false,
                            showLocationFallback = state.activeFace != ActiveFace.CUSTOM,
                        ) }
                    }
            }

            // No permission: manual entry / grant fallback (Custom needs no coords).
            else -> {
                update { it.copy(showLocationFallback = state.activeFace != ActiveFace.CUSTOM) }
            }
        }

        refreshActive(force = false, push = false)
        AutoUpdateScheduler.reschedule(context)
    }
```

- [ ] **Step 3: Stamp the fetch time on manual coord commit**

In `onCoordEdit` (lines 233–244), where valid manual coords persist (line 237), also stamp the time and refresh the label. Change:

```kotlin
        if (latD != null && lngD != null && latD in -90.0..90.0 && lngD in -180.0..180.0) {
            prefs.lastLat = latD; prefs.lastLng = lngD
        }
```

to:

```kotlin
        if (latD != null && lngD != null && latD in -90.0..90.0 && lngD in -180.0..180.0) {
            prefs.lastLat = latD; prefs.lastLng = lngD
            prefs.lastLocationFetchedMs = System.currentTimeMillis()
            update { it.copy(
                locationLabel = locLabel(latD, lngD),
                locationFreshness = "just now",
            ) }
        }
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "Gate launch GPS fix on staleness; stamp fetch time"
```

---

## Task 5: Pure notify-decision core

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/notify/NotifyDecision.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/notify/NotifyDecisionTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/blizzardcaron/freeolleefaces/notify/NotifyDecisionTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifyDecisionTest {

    private val watch = FailureKind.WATCH_UNREACHABLE
    private val weather = FailureKind.WEATHER_FETCH_FAILED

    @Test fun healthyToFailureNotifies() {
        assertEquals(NotifyAction.Notify(watch), NotifyDecision.decide(watch, null, inSleep = false))
    }

    @Test fun sameFailurePersistsDoesNothing() {
        assertEquals(NotifyAction.Nothing, NotifyDecision.decide(watch, watch, inSleep = false))
    }

    @Test fun changedFailureKindNotifies() {
        assertEquals(NotifyAction.Notify(weather), NotifyDecision.decide(weather, watch, inSleep = false))
    }

    @Test fun failureToSuccessClears() {
        assertEquals(NotifyAction.Clear, NotifyDecision.decide(null, watch, inSleep = false))
    }

    @Test fun successToSuccessDoesNothing() {
        assertEquals(NotifyAction.Nothing, NotifyDecision.decide(null, null, inSleep = false))
    }

    @Test fun failureWhileAsleepDoesNothing() {
        assertEquals(NotifyAction.Nothing, NotifyDecision.decide(watch, null, inSleep = true))
    }

    @Test fun recoveryWhileAsleepStillClears() {
        assertEquals(NotifyAction.Clear, NotifyDecision.decide(null, watch, inSleep = true))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*NotifyDecisionTest*"`
Expected: FAIL — `FailureKind` / `NotifyAction` / `NotifyDecision` unresolved.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/notify/NotifyDecision.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.notify

/** A background failure worth notifying about. Null elsewhere means "healthy". */
enum class FailureKind { WATCH_UNREACHABLE, WEATHER_FETCH_FAILED, SETUP_INCOMPLETE, SUN_UNREACHABLE }

/** What to do with the single error notification after one worker outcome. */
sealed interface NotifyAction {
    data class Notify(val kind: FailureKind) : NotifyAction
    data object Clear : NotifyAction
    data object Nothing : NotifyAction
}

object NotifyDecision {
    /**
     * Transition-only firing with auto-clear:
     *  - success clears any showing notification, else nothing;
     *  - a failure during the sleep window is suppressed (state unchanged);
     *  - a new or changed failure notifies once; the same failure persisting does nothing.
     */
    fun decide(current: FailureKind?, lastNotified: FailureKind?, inSleep: Boolean): NotifyAction {
        if (current == null) {
            return if (lastNotified != null) NotifyAction.Clear else NotifyAction.Nothing
        }
        if (inSleep) return NotifyAction.Nothing
        return if (lastNotified != current) NotifyAction.Notify(current) else NotifyAction.Nothing
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*NotifyDecisionTest*"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/notify/NotifyDecision.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/notify/NotifyDecisionTest.kt
git commit -m "Add pure notify-decision core (transition + auto-clear)"
```

---

## Task 6: Persist the notified failure kind

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt`

No unit test (Prefs glue, per the codebase pattern).

- [ ] **Step 1: Add the import**

At the top of `Prefs.kt`, with the other imports (line 7 area):

```kotlin
import com.blizzardcaron.freeolleefaces.notify.FailureKind
```

- [ ] **Step 2: Add the property**

After the `lastAutoSendSummary` property block (around line 93), add a defensively-parsed enum pref (same pattern as `tempUnit` / `activeFace`):

```kotlin
    var lastNotifiedKind: FailureKind?
        get() = sp.getString(KEY_LAST_NOTIFIED_KIND, null)
            ?.let { runCatching { FailureKind.valueOf(it) }.getOrNull() }
        set(value) = sp.edit { if (value == null) remove(KEY_LAST_NOTIFIED_KIND) else putString(KEY_LAST_NOTIFIED_KIND, value.name) }
```

- [ ] **Step 3: Add the key constant**

In the `companion object` (after `KEY_LAST_SEND_SUMMARY`, around line 119):

```kotlin
        private const val KEY_LAST_NOTIFIED_KIND = "last_notified_kind"
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt
git commit -m "Persist last-notified failure kind in Prefs"
```

---

## Task 7: ErrorNotifier + manifest permission

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/notify/ErrorNotifier.kt`
- Modify: `app/src/main/AndroidManifest.xml`

Android glue, no unit test. Verified by build.

- [ ] **Step 1: Declare the permission**

In `AndroidManifest.xml`, add after the `RECEIVE_BOOT_COMPLETED` line:

```xml
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- [ ] **Step 2: Create the notifier**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/notify/ErrorNotifier.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.blizzardcaron.freeolleefaces.MainActivity

/** Owns the single "background update problem" notification. Post/clear driven by [NotifyDecision]. */
object ErrorNotifier {

    private const val CHANNEL_ID = "background_problems"
    private const val NOTIFICATION_ID = 1001

    fun notify(context: Context, kind: FailureKind) {
        val ctx = context.applicationContext
        ensureChannel(ctx)
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(titleFor(kind))
            .setContentText(textFor(kind))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification)
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(ctx: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background update problems",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Alerts when a background watch update fails." }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun titleFor(kind: FailureKind): String = when (kind) {
        FailureKind.WATCH_UNREACHABLE -> "Watch unreachable"
        FailureKind.WEATHER_FETCH_FAILED -> "Weather update failed"
        FailureKind.SETUP_INCOMPLETE -> "Setup incomplete"
        FailureKind.SUN_UNREACHABLE -> "Sun update missed"
    }

    private fun textFor(kind: FailureKind): String = when (kind) {
        FailureKind.WATCH_UNREACHABLE -> "The last update didn't reach your watch. Is it on and in range?"
        FailureKind.WEATHER_FETCH_FAILED -> "Couldn't fetch the temperature. The watch value may be stale."
        FailureKind.SETUP_INCOMPLETE -> "Open the app to set your location and watch."
        FailureKind.SUN_UNREACHABLE -> "Couldn't deliver the next sunrise/sunset to your watch."
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/notify/ErrorNotifier.kt \
        app/src/main/AndroidManifest.xml
git commit -m "Add ErrorNotifier + POST_NOTIFICATIONS permission"
```

---

## Task 8: Wire notifications into the worker

Map each `AutoUpdateWorker` outcome to a `FailureKind?` and apply the notify decision, honoring the sleep window. Intermediate sun retries and the polar no-event case skip the decision entirely (no notify, no clear).

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt`

- [ ] **Step 1: Add imports**

Near the existing imports in `AutoUpdateWorker.kt`:

```kotlin
import com.blizzardcaron.freeolleefaces.notify.ErrorNotifier
import com.blizzardcaron.freeolleefaces.notify.FailureKind
import com.blizzardcaron.freeolleefaces.notify.NotifyAction
import com.blizzardcaron.freeolleefaces.notify.NotifyDecision
```

- [ ] **Step 2: Add the apply-health + sleep helpers**

Add two private members to the `AutoUpdateWorker` class (e.g. above the `companion object`):

```kotlin
    private fun applyHealth(ctx: Context, prefs: Prefs, kind: FailureKind?, inSleep: Boolean) {
        when (val action = NotifyDecision.decide(kind, prefs.lastNotifiedKind, inSleep)) {
            is NotifyAction.Notify -> {
                ErrorNotifier.notify(ctx, action.kind)
                prefs.lastNotifiedKind = action.kind
            }
            NotifyAction.Clear -> {
                ErrorNotifier.clear(ctx)
                prefs.lastNotifiedKind = null
            }
            NotifyAction.Nothing -> {}
        }
    }

    private fun inSleepNow(prefs: Prefs): Boolean {
        if (!prefs.sleepEnabled) return false
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val m = now.hour * 60 + now.minute
        return AutoUpdateSchedule.isInSleepWindow(m, prefs.sleepStartMin, prefs.sleepEndMin)
    }
```

- [ ] **Step 3: Health-apply at the setup-incomplete and Custom returns**

In `doWork()`, update the two early returns (lines 36–40):

```kotlin
        // CUSTOM has no schedule; clear any stale error notification and stop the chain.
        if (face == ActiveFace.CUSTOM) {
            applyHealth(ctx, prefs, null, inSleep = false)
            return Result.success()
        }
        if (lat == null || lng == null || address == null) {
            prefs.recordAutoSend("Skipped: set location/watch in app")
            applyHealth(ctx, prefs, FailureKind.SETUP_INCOMPLETE, inSleepNow(prefs))
            return Result.success()
        }
```

- [ ] **Step 4: Health-apply in `runTemperature`**

In `runTemperature`, the `inSleep` val already exists (lines 64–65). At the three outcome sites inside the `if (!inSleep)` block, add an `applyHealth` call after each `recordAutoSend`. The asleep `else` branch is left untouched (no health evaluation overnight). The block becomes:

```kotlin
        if (!inSleep) {
            OpenMeteoClient.currentTemp(lat, lng, prefs.tempUnit, RetryPolicy.Background)
                .onSuccess { temp ->
                    prefs.recordTempFetch(temp, prefs.tempUnit)
                    val payload = DisplayFormatter.temperature(temp, prefs.tempUnit)
                    OlleeBleClient(ctx).send(address, payload)
                        .onSuccess {
                            prefs.recordAutoSend("Sent '$payload'")
                            applyHealth(ctx, prefs, null, inSleep)
                        }
                        .onFailure {
                            prefs.recordAutoSend("Skipped: watch unreachable")
                            applyHealth(ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep)
                        }
                }
                .onFailure { err ->
                    val suffix = (err as? WeatherFetchError)?.statusCode?.let { " (HTTP $it)" } ?: ""
                    prefs.recordAutoSend("Skipped: weather fetch failed$suffix")
                    applyHealth(ctx, prefs, FailureKind.WEATHER_FETCH_FAILED, inSleep)
                }
        } else {
            prefs.recordAutoSend("Asleep (power saving)")
        }
```

- [ ] **Step 5: Health-apply in `runSun`**

In `runSun`, compute sleep once and apply at the success and retries-exhausted sites only (the polar no-event return and the intermediate retry are left to skip the decision). The body becomes:

```kotlin
        val inSleep = inSleepNow(prefs)
        val event = SunCalc.nextEvent(now.toInstant(), lat, lng, ZoneId.systemDefault())
        if (event == null) {
            prefs.recordAutoSend("Skipped: no sun event (polar)")
            AutoUpdateScheduler.enqueueNext(ctx, Duration.ofHours(12).toMillis(), sunAttempt = 0)
            return Result.success()
        }

        val payload = DisplayFormatter.sunTime(event.kind, event.time.toLocalTime())
        val sendResult = OlleeBleClient(ctx).send(address, payload)

        if (sendResult.isSuccess) {
            prefs.recordAutoSend("Sent '$payload'")
            applyHealth(ctx, prefs, null, inSleep)
            scheduleAfterEvent(ctx, now, event.time)
        } else {
            val attempt = inputData.getInt(KEY_SUN_ATTEMPT, 0)
            if (attempt < MAX_SUN_RETRIES) {
                prefs.recordAutoSend("Retry ${attempt + 1}: watch unreachable")
                AutoUpdateScheduler.enqueueNext(
                    ctx, Duration.ofMinutes(15).toMillis(), sunAttempt = attempt + 1,
                )
            } else {
                prefs.recordAutoSend("Skipped: watch unreachable")
                applyHealth(ctx, prefs, FailureKind.SUN_UNREACHABLE, inSleep)
                scheduleAfterEvent(ctx, now, event.time)
            }
        }
        return Result.success()
```

- [ ] **Step 6: Build and run the full test suite**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests pass (existing + `LocationFreshnessTest` + `NotifyDecisionTest`).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt
git commit -m "Raise/clear error notifications from the background worker"
```

---

## Task 9: Lazy POST_NOTIFICATIONS request

Request the runtime notification permission (Android 13+) only when a background config is active, with graceful degradation if denied.

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt`

- [ ] **Step 1: Add imports**

```kotlin
import android.os.Build
```

- [ ] **Step 2: Add a notification-permission launcher**

In `AppRoot`, next to the other `rememberLauncherForActivityResult` launchers (after the `btPermissionLauncher`, around line 285), add:

```kotlin
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — errors still record in-app either way */ }
```

- [ ] **Step 3: Request lazily in a launch effect**

Add a second `LaunchedEffect(Unit)` after the existing one (after line ~325, the location one), so the request fires once on entry when background updates are active:

```kotlin
    LaunchedEffect(Unit) {
        val backgroundActive = prefs.activeFace != ActiveFace.CUSTOM && prefs.watchAddress != null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backgroundActive &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "Request POST_NOTIFICATIONS lazily when background updates are active"
```

---

## Manual verification checklist (on-device)

Background timing and notifications need a real device; the dev box is headless over SSH. After Task 9, append results to a verification report under `docs/superpowers/`:

- [ ] Launch with saved coords < 1 week old → **no** GPS fix; location line shows the saved coords + age.
- [ ] Launch with coords ≥ 1 week old → silent refresh; on success the line updates to "just now"; when the fix fails, the line shows "… · refresh failed" **and** a Snackbar appears.
- [ ] Tap `refresh` (or `set` when unset) → on-demand fix; permission requested if not held.
- [ ] Manual coord entry → line shows "just now"; staleness resets.
- [ ] Send to watch → confirmation appears as a Snackbar; nothing is left pinned at the bottom of Home.
- [ ] BLE/BT permission denied path → message appears as a Snackbar.
- [ ] Turn the watch off across one background tick → exactly **one** "Watch unreachable" notification appears (not per-tick); it **clears** on the next successful tick.
- [ ] Same failure persisting across several ticks → **no** re-alert.
- [ ] Failure that begins inside the sleep window → suppressed; surfaces at wake (06:00) if still broken.
- [ ] Deny POST_NOTIFICATIONS → app still works; no notifications; failures still recorded to `lastAutoSendSummary`.

## Self-review notes

- **Spec coverage:** §1 → Tasks 1–4; §2 → Task 3; §3 → Tasks 5–9. All four notify kinds (`WATCH_UNREACHABLE`, `WEATHER_FETCH_FAILED`, `SETUP_INCOMPLETE`, `SUN_UNREACHABLE`) are produced at concrete worker sites (Task 8). Sleep suppression honored via `inSleepNow` / the existing temp `inSleep`.
- **Type consistency:** `decide(current, lastNotified, inSleep): NotifyAction`; `NotifyAction.Notify(kind)` / `Clear` / `Nothing`; `ErrorNotifier.notify(ctx, kind)` / `clear(ctx)`; `Prefs.lastNotifiedKind: FailureKind?`; `Prefs.lastLocationFetchedMs: Long?`; `isLocationStale(fetchedMs, nowMs[, staleMs])`; `freshnessLabel(fetchedMs, nowMs): String?` — used identically across tasks.

