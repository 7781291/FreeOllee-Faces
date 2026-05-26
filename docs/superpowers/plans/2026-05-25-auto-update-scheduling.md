# Automatic Background Updates — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Push temperature (on a configurable interval with a power-saving sleep window) or sun events (re-sent when stale) to the watch automatically in the background, with the app closed.

**Architecture:** A single selected source (`OFF` / `TEMPERATURE` / `SUN`) drives a self-rescheduling WorkManager one-shot chain: one `CoroutineWorker` sends the due value, then enqueues the next `OneTimeWorkRequest` at the computed delay. The only nontrivial logic — the scheduling math — lives in a pure, unit-tested object; the worker, scheduler, boot receiver, and UI are thin Android wrappers. The chain is re-armed on app launch, on settings change, on `BOOT_COMPLETED`, and by a once-daily watchdog.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), AndroidX WorkManager, JUnit4. Build with `./gradlew` (Android, minSdk 31 / target 36).

**Spec:** `docs/superpowers/specs/2026-05-25-auto-update-scheduling-design.md`

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoSource.kt` *(new)* | The selected-face enum. |
| `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateSchedule.kt` *(new)* | Pure scheduling math + `SleepWindow`. |
| `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt` *(new)* | `CoroutineWorker`: build payload, send, self-reschedule. |
| `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/WatchdogWorker.kt` *(new)* | Daily periodic worker that re-arms the chain. |
| `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduler.kt` *(new)* | Re-arm entry point, enqueue helper, watchdog setup. |
| `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/BootReceiver.kt` *(new)* | Re-arm on `BOOT_COMPLETED`. |
| `app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt` *(modify)* | New persisted config + last-send record. |
| `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/MainScreen.kt` *(modify)* | Auto-update card + state fields + callbacks. |
| `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt` *(modify)* | Hydrate state, wire callbacks → Prefs + reschedule, re-arm on launch. |
| `app/src/main/AndroidManifest.xml` *(modify)* | `RECEIVE_BOOT_COMPLETED` + `BootReceiver`. |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` *(modify)* | Add WorkManager. |
| `app/src/test/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduleTest.kt` *(new)* | Pure unit tests for the scheduling math. |

**Note on the self-reschedule (`ExistingWorkPolicy.REPLACE` from inside the worker):** `AutoUpdateWorker` enqueues the next run under the same unique work name `auto_update_chain` with `REPLACE`. This is the standard one-shot-chaining idiom — by the time the new (delayed) request is enqueued the current run is completing, and the re-arm triggers (launch / boot / watchdog) heal any dropped chain. Keep this pattern.

---

## Task 1: Add the WorkManager dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add the version and library to the catalog**

In `gradle/libs.versions.toml`, under `[versions]` add (after the `orgJson` line):

```toml
workManager = "2.10.0"
```

Under `[libraries]` add (after the `org-json` line):

```toml
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }
```

- [ ] **Step 2: Reference the library in the app module**

In `app/build.gradle.kts`, inside the `dependencies { }` block, add after the `kotlinx.coroutines.android` line:

```kotlin
    implementation(libs.androidx.work.runtime.ktx)
```

- [ ] **Step 3: Verify it resolves**

Run: `./gradlew :app:dependencies --configuration debugRuntimeClasspath -q | grep work-runtime`
Expected: a line containing `androidx.work:work-runtime-ktx:2.10.0`.

(If `2.10.0` fails to resolve, use the latest stable shown by Android Studio's catalog; bump the `workManager` version and re-run.)

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "Add WorkManager dependency"
```

---

## Task 2: Add the AutoSource enum

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoSource.kt`

- [ ] **Step 1: Create the enum**

```kotlin
package com.blizzardcaron.freeolleefaces.auto

/** The single "selected face" that auto-update drives. */
enum class AutoSource { OFF, TEMPERATURE, SUN }
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoSource.kt
git commit -m "Add AutoSource enum"
```

---

## Task 3: Pure scheduling math (TDD)

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateSchedule.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduleTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduleTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class AutoUpdateScheduleTest {

    // ----- isInSleepWindow: non-wrapping window 01:00–05:00 (60..300) -----

    @Test
    fun `non-wrap window contains a middle minute`() {
        assertTrue(AutoUpdateSchedule.isInSleepWindow(120, 60, 300))
    }

    @Test
    fun `non-wrap window excludes a minute before start`() {
        assertFalse(AutoUpdateSchedule.isInSleepWindow(30, 60, 300))
    }

    @Test
    fun `non-wrap window includes the start minute`() {
        assertTrue(AutoUpdateSchedule.isInSleepWindow(60, 60, 300))
    }

    @Test
    fun `non-wrap window excludes the end minute`() {
        assertFalse(AutoUpdateSchedule.isInSleepWindow(300, 60, 300))
    }

    // ----- isInSleepWindow: wrapping window 22:00–06:00 (1320..360) -----

    @Test
    fun `wrap window contains a late-evening minute`() {
        assertTrue(AutoUpdateSchedule.isInSleepWindow(1350, 1320, 360)) // 22:30
    }

    @Test
    fun `wrap window contains an early-morning minute`() {
        assertTrue(AutoUpdateSchedule.isInSleepWindow(30, 1320, 360)) // 00:30
    }

    @Test
    fun `wrap window includes the start minute`() {
        assertTrue(AutoUpdateSchedule.isInSleepWindow(1320, 1320, 360)) // 22:00
    }

    @Test
    fun `wrap window excludes the end minute`() {
        assertFalse(AutoUpdateSchedule.isInSleepWindow(360, 1320, 360)) // 06:00
    }

    @Test
    fun `wrap window excludes a midday minute`() {
        assertFalse(AutoUpdateSchedule.isInSleepWindow(720, 1320, 360)) // 12:00
    }

    @Test
    fun `start equals end means never in window`() {
        assertFalse(AutoUpdateSchedule.isInSleepWindow(100, 300, 300))
    }

    // ----- nextTemperatureFire -----

    private fun at(hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(2026, 5, 25, hour, minute, 0, 0, ZoneOffset.UTC)

    @Test
    fun `no sleep window returns now plus interval`() {
        val result = AutoUpdateSchedule.nextTemperatureFire(at(12, 0), 60, null)
        assertEquals(at(13, 0), result)
    }

    @Test
    fun `fire landing inside window snaps to end same day`() {
        // now 02:00, +60 = 03:00 which is inside 22:00–06:00 -> snap to 06:00 same day.
        val result = AutoUpdateSchedule.nextTemperatureFire(at(2, 0), 60, SleepWindow(1320, 360))
        assertEquals(at(6, 0), result)
    }

    @Test
    fun `fire landing inside window in the evening snaps to next-day end`() {
        // now 21:30, +60 = 22:30 inside window -> snap to 06:00 the NEXT day.
        val result = AutoUpdateSchedule.nextTemperatureFire(at(21, 30), 60, SleepWindow(1320, 360))
        assertEquals(at(6, 0).plusDays(1), result)
    }

    @Test
    fun `fire outside window is unchanged`() {
        // now 08:00, +60 = 09:00, not in window.
        val result = AutoUpdateSchedule.nextTemperatureFire(at(8, 0), 60, SleepWindow(1320, 360))
        assertEquals(at(9, 0), result)
    }

    // ----- nextSunWake -----

    @Test
    fun `sun wake adds the buffer to the event time`() {
        val event = at(6, 29)
        assertEquals(event.plusSeconds(60), AutoUpdateSchedule.nextSunWake(event))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*.AutoUpdateScheduleTest" -q`
Expected: FAIL — compilation error / unresolved reference `AutoUpdateSchedule` (it doesn't exist yet).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateSchedule.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.auto

import java.time.ZonedDateTime

/** Sleep window bounds as minute-of-day; `endMin` may be < `startMin` (wraps midnight). */
data class SleepWindow(val startMin: Int, val endMin: Int)

/** Pure scheduling math — no Android dependencies, fully unit-testable. */
object AutoUpdateSchedule {

    /** Start inclusive, end exclusive. `startMin == endMin` means never in window. */
    fun isInSleepWindow(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean {
        if (startMin == endMin) return false
        return if (startMin < endMin) {
            minuteOfDay in startMin until endMin
        } else {
            minuteOfDay >= startMin || minuteOfDay < endMin
        }
    }

    /**
     * Next temperature fire = [now] + [intervalMinutes]; if that lands inside [sleep],
     * snap forward to the next occurrence of `sleep.endMin`.
     */
    fun nextTemperatureFire(
        now: ZonedDateTime,
        intervalMinutes: Int,
        sleep: SleepWindow?,
    ): ZonedDateTime {
        val base = now.plusMinutes(intervalMinutes.toLong())
        if (sleep == null) return base
        val baseMinOfDay = base.hour * 60 + base.minute
        if (!isInSleepWindow(baseMinOfDay, sleep.startMin, sleep.endMin)) return base
        return snapToEnd(base, sleep.endMin)
    }

    /** Wake right after the event goes stale. */
    fun nextSunWake(eventTime: ZonedDateTime, bufferSeconds: Long = 60): ZonedDateTime =
        eventTime.plusSeconds(bufferSeconds)

    private fun snapToEnd(from: ZonedDateTime, endMin: Int): ZonedDateTime {
        var candidate = from
            .withHour(endMin / 60)
            .withMinute(endMin % 60)
            .withSecond(0)
            .withNano(0)
        if (!candidate.isAfter(from)) candidate = candidate.plusDays(1)
        return candidate
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.AutoUpdateScheduleTest" -q`
Expected: BUILD SUCCESSFUL — all 16 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateSchedule.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduleTest.kt
git commit -m "Add pure auto-update scheduling math with tests"
```

---

## Task 4: Extend Prefs with auto-update config

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt`

- [ ] **Step 1: Add the import**

At the top of `Prefs.kt`, add after the existing `import com.blizzardcaron.freeolleefaces.format.TempUnit` line:

```kotlin
import com.blizzardcaron.freeolleefaces.auto.AutoSource
```

- [ ] **Step 2: Add the new properties and the record helper**

In `Prefs.kt`, add these inside the class, right after the existing `tempUnit` property (before the `companion object`):

```kotlin
    var autoSource: AutoSource
        get() = sp.getString(KEY_AUTO_SOURCE, null)
            ?.let { runCatching { AutoSource.valueOf(it) }.getOrNull() }
            ?: AutoSource.OFF
        set(value) = sp.edit { putString(KEY_AUTO_SOURCE, value.name) }

    var tempIntervalMinutes: Int
        get() = sp.getInt(KEY_TEMP_INTERVAL, 60)
        set(value) = sp.edit { putInt(KEY_TEMP_INTERVAL, value) }

    var sleepEnabled: Boolean
        get() = sp.getBoolean(KEY_SLEEP_ENABLED, true)
        set(value) = sp.edit { putBoolean(KEY_SLEEP_ENABLED, value) }

    var sleepStartMin: Int
        get() = sp.getInt(KEY_SLEEP_START, 22 * 60)
        set(value) = sp.edit { putInt(KEY_SLEEP_START, value) }

    var sleepEndMin: Int
        get() = sp.getInt(KEY_SLEEP_END, 6 * 60)
        set(value) = sp.edit { putInt(KEY_SLEEP_END, value) }

    var lastAutoSendMs: Long?
        get() = if (sp.contains(KEY_LAST_SEND_MS)) sp.getLong(KEY_LAST_SEND_MS, 0L) else null
        set(value) = sp.edit { if (value == null) remove(KEY_LAST_SEND_MS) else putLong(KEY_LAST_SEND_MS, value) }

    var lastAutoSendSummary: String?
        get() = sp.getString(KEY_LAST_SEND_SUMMARY, null)
        set(value) = sp.edit { if (value == null) remove(KEY_LAST_SEND_SUMMARY) else putString(KEY_LAST_SEND_SUMMARY, value) }

    /** Convenience: stamp the time and summary of the most recent background send attempt. */
    fun recordAutoSend(summary: String) {
        lastAutoSendMs = System.currentTimeMillis()
        lastAutoSendSummary = summary
    }
```

- [ ] **Step 3: Add the new keys to the companion object**

In `Prefs.kt`, inside `companion object`, add after the existing `KEY_TEMP_UNIT` line:

```kotlin
        private const val KEY_AUTO_SOURCE = "auto_source"
        private const val KEY_TEMP_INTERVAL = "temp_interval_min"
        private const val KEY_SLEEP_ENABLED = "sleep_enabled"
        private const val KEY_SLEEP_START = "sleep_start_min"
        private const val KEY_SLEEP_END = "sleep_end_min"
        private const val KEY_LAST_SEND_MS = "last_auto_send_ms"
        private const val KEY_LAST_SEND_SUMMARY = "last_auto_send_summary"
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt
git commit -m "Add auto-update config to Prefs"
```

---

## Task 5: Worker, watchdog, and scheduler

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt`
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/WatchdogWorker.kt`
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduler.kt`

These three reference each other (same package) and must compile together.

- [ ] **Step 1: Create the scheduler**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduler.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.auto

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Single re-arm entry point for the auto-update chain. */
object AutoUpdateScheduler {

    const val WORK_NAME = "auto_update_chain"
    const val WATCHDOG_NAME = "auto_update_watchdog"

    /** Re-arm the chain from current Prefs. Safe to call repeatedly (always REPLACEs). */
    fun reschedule(context: Context) {
        val ctx = context.applicationContext
        val prefs = Prefs(ctx)
        val wm = WorkManager.getInstance(ctx)

        ensureWatchdog(wm)

        when (prefs.autoSource) {
            AutoSource.OFF -> wm.cancelUniqueWork(WORK_NAME)

            AutoSource.TEMPERATURE -> {
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

            AutoSource.SUN -> enqueueNext(ctx, 0L, sunAttempt = 0)
        }
    }

    /** Enqueue the single next chain run (REPLACE keeps exactly one pending). */
    fun enqueueNext(context: Context, delayMs: Long, sunAttempt: Int) {
        val data = Data.Builder()
            .putInt(AutoUpdateWorker.KEY_SUN_ATTEMPT, sunAttempt)
            .build()
        val req = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    private fun ensureWatchdog(wm: WorkManager) {
        val req = PeriodicWorkRequestBuilder<WatchdogWorker>(24, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(WATCHDOG_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
```

- [ ] **Step 2: Create the watchdog worker**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/WatchdogWorker.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.auto

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Once-daily insurance: re-arm the chain in case it was dropped while the app was never opened. */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AutoUpdateScheduler.reschedule(applicationContext)
        return Result.success()
    }
}
```

- [ ] **Step 3: Create the main worker**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.auto

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.blizzardcaron.freeolleefaces.ble.OlleeBleClient
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.sun.SunCalc
import com.blizzardcaron.freeolleefaces.weather.OpenMeteoClient
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Performs one due send for the selected source, then enqueues the next chain run.
 * Reads all state from Prefs; uses the saved coordinates (no location fix).
 */
class AutoUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)

        val source = prefs.autoSource
        val lat = prefs.lastLat
        val lng = prefs.lastLng
        val address = prefs.watchAddress

        // OFF, or not enough config to send: stop the chain. Re-arm triggers restart it.
        if (source == AutoSource.OFF) return Result.success()
        if (lat == null || lng == null || address == null) {
            prefs.recordAutoSend("Skipped: set location/watch in app")
            return Result.success()
        }

        val now = ZonedDateTime.now(ZoneId.systemDefault())
        return when (source) {
            AutoSource.TEMPERATURE -> runTemperature(ctx, prefs, lat, lng, address, now)
            AutoSource.SUN -> runSun(ctx, prefs, lat, lng, address, now)
            AutoSource.OFF -> Result.success()
        }
    }

    private suspend fun runTemperature(
        ctx: Context,
        prefs: Prefs,
        lat: Double,
        lng: Double,
        address: String,
        now: ZonedDateTime,
    ): Result {
        val sleep = if (prefs.sleepEnabled) {
            SleepWindow(prefs.sleepStartMin, prefs.sleepEndMin)
        } else null
        val nowMinOfDay = now.hour * 60 + now.minute

        // Guard: if we somehow fired inside the sleep window, skip the send.
        val inSleep = sleep != null &&
            AutoUpdateSchedule.isInSleepWindow(nowMinOfDay, sleep.startMin, sleep.endMin)
        if (!inSleep) {
            OpenMeteoClient.currentTemp(lat, lng, prefs.tempUnit)
                .onSuccess { temp ->
                    val payload = DisplayFormatter.temperature(temp, prefs.tempUnit)
                    OlleeBleClient(ctx).send(address, payload)
                        .onSuccess { prefs.recordAutoSend("Sent '$payload'") }
                        .onFailure { prefs.recordAutoSend("Skipped: watch unreachable") }
                }
                .onFailure { prefs.recordAutoSend("Skipped: weather fetch failed") }
        }

        val fire = AutoUpdateSchedule.nextTemperatureFire(now, prefs.tempIntervalMinutes, sleep)
        val delayMs = Duration.between(now, fire).toMillis().coerceAtLeast(0)
        AutoUpdateScheduler.enqueueNext(ctx, delayMs, sunAttempt = 0)
        return Result.success()
    }

    private suspend fun runSun(
        ctx: Context,
        prefs: Prefs,
        lat: Double,
        lng: Double,
        address: String,
        now: ZonedDateTime,
    ): Result {
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
                scheduleAfterEvent(ctx, now, event.time)
            }
        }
        return Result.success()
    }

    private fun scheduleAfterEvent(ctx: Context, now: ZonedDateTime, eventTime: ZonedDateTime) {
        val wake = AutoUpdateSchedule.nextSunWake(eventTime)
        val delayMs = Duration.between(now, wake).toMillis().coerceAtLeast(0)
        AutoUpdateScheduler.enqueueNext(ctx, delayMs, sunAttempt = 0)
    }

    companion object {
        const val KEY_SUN_ATTEMPT = "sun_attempt"
        const val MAX_SUN_RETRIES = 3
    }
}
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduler.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/auto/WatchdogWorker.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt
git commit -m "Add auto-update worker, watchdog, and scheduler"
```

---

## Task 6: Boot receiver + manifest

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/BootReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create the receiver**

Create `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/BootReceiver.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the auto-update chain after a device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AutoUpdateScheduler.reschedule(context)
        }
    }
}
```

- [ ] **Step 2: Add the permission**

In `app/src/main/AndroidManifest.xml`, add after the existing `ACCESS_FINE_LOCATION` permission line:

```xml
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

- [ ] **Step 3: Register the receiver**

In `app/src/main/AndroidManifest.xml`, inside the `<application>` element, add after the closing `</activity>` tag:

```xml
        <receiver
            android:name=".auto.BootReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/auto/BootReceiver.kt \
        app/src/main/AndroidManifest.xml
git commit -m "Add boot receiver to re-arm auto-update after reboot"
```

---

## Task 7: UI — Auto-update card + MainActivity wiring

The `MainScreen` data classes and the `MainActivity` that constructs them must change together to compile.

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt`

- [ ] **Step 1: Add the AutoSource import to MainScreen**

In `MainScreen.kt`, add after `import com.blizzardcaron.freeolleefaces.format.TempUnit`:

```kotlin
import androidx.compose.ui.platform.LocalContext
import com.blizzardcaron.freeolleefaces.auto.AutoSource
```

- [ ] **Step 2: Add the new state fields**

In `MainScreen.kt`, in `data class MainScreenState`, add these fields after `sunPreview`:

```kotlin
    val autoSource: AutoSource = AutoSource.OFF,
    val tempIntervalText: String = "60",
    val sleepEnabled: Boolean = true,
    val sleepStartMin: Int = 22 * 60,
    val sleepEndMin: Int = 6 * 60,
    val lastAutoSummary: String = "No auto-updates yet",
```

- [ ] **Step 3: Add the new callbacks**

In `MainScreen.kt`, in `data class MainScreenCallbacks`, add these after `onRetryTemperature`:

```kotlin
    val onAutoSourceChange: (AutoSource) -> Unit,
    val onTempIntervalChange: (String) -> Unit,
    val onSleepEnabledChange: (Boolean) -> Unit,
    val onSleepStartChange: (Int) -> Unit,
    val onSleepEndChange: (Int) -> Unit,
```

- [ ] **Step 4: Insert the card into the layout**

In `MainScreen.kt`, find the second `HorizontalDivider()` (the one immediately after the "Next sun event" `PreviewCard`, before the custom `OutlinedTextField`). Insert directly after it:

```kotlin
        AutoUpdateCard(state = state, callbacks = callbacks)

        HorizontalDivider()
```

- [ ] **Step 5: Add the AutoUpdateCard composable and helpers**

In `MainScreen.kt`, add at the end of the file (after the `PreviewCard` composable):

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoUpdateCard(
    state: MainScreenState,
    callbacks: MainScreenCallbacks,
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Auto-update", style = MaterialTheme.typography.titleMedium)

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.autoSource == AutoSource.OFF,
                    onClick = { callbacks.onAutoSourceChange(AutoSource.OFF) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                ) { Text("Off") }
                SegmentedButton(
                    selected = state.autoSource == AutoSource.TEMPERATURE,
                    onClick = { callbacks.onAutoSourceChange(AutoSource.TEMPERATURE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                ) { Text("Temp") }
                SegmentedButton(
                    selected = state.autoSource == AutoSource.SUN,
                    onClick = { callbacks.onAutoSourceChange(AutoSource.SUN) },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                ) { Text("Sun") }
            }

            when (state.autoSource) {
                AutoSource.TEMPERATURE -> {
                    OutlinedTextField(
                        value = state.tempIntervalText,
                        onValueChange = callbacks.onTempIntervalChange,
                        label = { Text("Interval (minutes, min 15)") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Power-saving sleep")
                        Switch(
                            checked = state.sleepEnabled,
                            onCheckedChange = callbacks.onSleepEnabledChange,
                        )
                    }
                    if (state.sleepEnabled) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    showTimePicker(context, state.sleepStartMin, callbacks.onSleepStartChange)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("From ${minutesToLabel(state.sleepStartMin)}") }
                            OutlinedButton(
                                onClick = {
                                    showTimePicker(context, state.sleepEndMin, callbacks.onSleepEndChange)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("To ${minutesToLabel(state.sleepEndMin)}") }
                        }
                    }
                }
                AutoSource.SUN -> Text(
                    "Re-sends automatically after each sunrise/sunset; no schedule needed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                AutoSource.OFF -> Text(
                    "Auto-update is off.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                "Last auto-update: ${state.lastAutoSummary}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
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

- [ ] **Step 6: Add imports to MainActivity**

In `MainActivity.kt`, add after `import com.blizzardcaron.freeolleefaces.ble.OlleeBleClient`:

```kotlin
import com.blizzardcaron.freeolleefaces.auto.AutoSource
import com.blizzardcaron.freeolleefaces.auto.AutoUpdateScheduler
```

- [ ] **Step 7: Hydrate the new state from Prefs**

In `MainActivity.kt`, in the `var state by remember { mutableStateOf(MainScreenState(...)) }` initializer, add these fields after `tempUnit = prefs.tempUnit,`:

```kotlin
                    autoSource = prefs.autoSource,
                    tempIntervalText = prefs.tempIntervalMinutes.toString(),
                    sleepEnabled = prefs.sleepEnabled,
                    sleepStartMin = prefs.sleepStartMin,
                    sleepEndMin = prefs.sleepEndMin,
                    lastAutoSummary = formatLastSummary(prefs),
```

- [ ] **Step 8: Wire the new callbacks**

In `MainActivity.kt`, in the `MainScreenCallbacks(...)` constructor, add these after `onRetryTemperature = { ... },`:

```kotlin
        onAutoSourceChange = { src ->
            prefs.autoSource = src
            update { it.copy(autoSource = src) }
            AutoUpdateScheduler.reschedule(context)
        },
        onTempIntervalChange = { text ->
            update { it.copy(tempIntervalText = text) }
            val mins = text.toIntOrNull()
            if (mins != null) {
                prefs.tempIntervalMinutes = mins.coerceAtLeast(15)
                AutoUpdateScheduler.reschedule(context)
            }
        },
        onSleepEnabledChange = { enabled ->
            prefs.sleepEnabled = enabled
            update { it.copy(sleepEnabled = enabled) }
            AutoUpdateScheduler.reschedule(context)
        },
        onSleepStartChange = { min ->
            prefs.sleepStartMin = min
            update { it.copy(sleepStartMin = min) }
            AutoUpdateScheduler.reschedule(context)
        },
        onSleepEndChange = { min ->
            prefs.sleepEndMin = min
            update { it.copy(sleepEndMin = min) }
            AutoUpdateScheduler.reschedule(context)
        },
```

- [ ] **Step 9: Re-arm the chain on launch**

In `MainActivity.kt`, inside the existing `LaunchedEffect(Unit) { ... }`, add as the very last line (after the `refreshFromState()` call):

```kotlin
        AutoUpdateScheduler.reschedule(context)
```

- [ ] **Step 10: Add the summary formatter helper**

In `MainActivity.kt`, add at the end of the file (after the `labelForAddress` function):

```kotlin
private fun formatLastSummary(prefs: com.blizzardcaron.freeolleefaces.prefs.Prefs): String {
    val ms = prefs.lastAutoSendMs ?: return "No auto-updates yet"
    val time = java.time.Instant.ofEpochMilli(ms)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    return "$time — ${prefs.lastAutoSendSummary ?: ""}"
}
```

- [ ] **Step 11: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ui/MainScreen.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "Add auto-update card and wire scheduling into the UI"
```

---

## Task 8: Full build, test, and verification notes

**Files:**
- Modify: `docs/superpowers/plans/2026-05-14-freeollee-faces-app-verification.md`

- [ ] **Step 1: Run the full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest -q`
Expected: BUILD SUCCESSFUL — the new `AutoUpdateScheduleTest` (15 tests) plus all pre-existing tests pass.

- [ ] **Step 2: Build the debug APK (sanity check only — not committed)**

Run: `./gradlew :app:assembleDebug -q`
Expected: BUILD SUCCESSFUL; APK at `app/build/outputs/apk/debug/` (gitignored under `/build`). This step only confirms the app assembles. The APK is published via GitHub Releases (Task 9), never committed to the repo.

- [ ] **Step 3: Append the manual verification checklist**

Add this section to the end of `docs/superpowers/plans/2026-05-14-freeollee-faces-app-verification.md`:

```markdown
## Automatic background updates (2026-05-25)

Requires a physical device + the watch (background BLE timing can't be unit-tested).

- [ ] Select **Temp**, interval **15**, sleep **22:00–06:00**. Confirm a send lands
      within ~15 min, and that sends pause overnight and resume at 06:00.
- [ ] Set interval to **5** → field accepts text but the persisted/effective interval
      is clamped to 15 min.
- [ ] Select **Sun**. Confirm a fresh value lands shortly after the next sunrise/sunset
      passes (within ~1 min of the event).
- [ ] With **Temp** active, force-stop the app → sends continue on schedule.
- [ ] Reboot the phone → sends resume without opening the app.
- [ ] Turn the watch off during a tick → "Last auto-update" shows `watch unreachable`;
      the next tick recovers once the watch is back.
- [ ] Select **Off** → no further background sends occur.
```

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/plans/2026-05-14-freeollee-faces-app-verification.md
git commit -m "Add manual verification checklist for auto-update"
```

---

## Task 9: Publish the APK via GitHub Releases (stop committing binaries)

Switch distribution from a committed APK to a GitHub Release built by CI on tag push.
The debug-signed APK (installable for GrapheneOS sideloading) is the release artifact.

**Files:**
- Create: `.github/workflows/release.yml`
- Modify: `.gitignore`
- Delete: `dist/freeollee-faces-debug-v0.2.0.apk` (remove from git)
- Modify: `dist/README.md` and `README.md`

- [ ] **Step 1: Stop tracking APKs**

Append to `.gitignore`:

```
# Built APKs are published as GitHub Release assets, never committed.
*.apk
```

- [ ] **Step 2: Remove the committed APK from the repo**

```bash
git rm dist/freeollee-faces-debug-v0.2.0.apk
```

(History still contains it; that's fine. We just stop carrying it going forward.)

- [ ] **Step 3: Add the release workflow**

Create `.github/workflows/release.yml`:

```yaml
name: Release

on:
  push:
    tags:
      - 'v*'

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Check out
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Build debug APK
        run: ./gradlew :app:assembleDebug

      - name: Stage APK
        run: cp app/build/outputs/apk/debug/app-debug.apk "freeollee-faces-debug-${GITHUB_REF_NAME}.apk"

      - name: Publish release
        env:
          GH_TOKEN: ${{ github.token }}
        run: >
          gh release create "$GITHUB_REF_NAME"
          "freeollee-faces-debug-${GITHUB_REF_NAME}.apk"
          --title "$GITHUB_REF_NAME"
          --generate-notes
```

- [ ] **Step 4: Update the dist/ README to point at Releases**

Replace the contents of `dist/README.md` with:

```markdown
# Distribution

Built APKs are **no longer committed here**. Each tagged version is published as a
GitHub Release with the debug-signed APK attached:

https://github.com/kenblizzardcaron/FreeOllee-Faces/releases

To cut a release, push a tag like `v0.3.0`; the `Release` workflow builds the APK and
publishes it automatically.
```

- [ ] **Step 5: Update the main README build section**

In `README.md`, under the `## Building` section, after the existing debug-build lines, add:

```markdown
## Releases

Tagged versions are published as GitHub Releases with the debug-signed APK attached —
see the [Releases page](https://github.com/kenblizzardcaron/FreeOllee-Faces/releases).
Pushing a `v*` tag triggers `.github/workflows/release.yml`, which builds and publishes
the APK; APKs are not committed to the repository.
```

- [ ] **Step 6: Verify the workflow is well-formed**

The workflow can only be fully verified by pushing a tag (which happens after merge).
For now, confirm the YAML parses and the file is in place:

Run: `git status --short .github/workflows/release.yml && python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml')); print('YAML OK')"`
Expected: the file is staged/added and `YAML OK` prints. (If `pyyaml` isn't installed, skip the parse check — the real verification is the first tagged CI run.)

- [ ] **Step 7: Commit**

```bash
git add .gitignore .github/workflows/release.yml dist/README.md README.md
git commit -m "Publish APK via GitHub Releases instead of committing it"
```

> **Note:** The first real CI run (on the first `v*` tag) may need a tweak — AGP 9 + compileSdk 36 are new, so the runner's Android SDK platform may need adjusting. Cutting the release tag happens after this branch merges; verify the Actions run there and iterate if needed.

---

## Done

The feature is complete when: all unit tests pass, the debug APK builds locally, the manual
verification checklist has been walked on-device, and the release workflow is in place (its
first run verified when the version tag is pushed after merge). The pure scheduling math is
covered by automated tests; the Android wrappers (worker, scheduler, boot receiver, UI) are
verified manually per the checklist; the APK is distributed via GitHub Releases, not committed.
