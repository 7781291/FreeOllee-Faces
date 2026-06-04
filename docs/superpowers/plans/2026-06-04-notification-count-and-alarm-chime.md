# Notification Count (+ Alarm-Chime BLE Spike) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the count of undismissed, non-persistent notifications in the watch's
weekday slot (a new `NOTIFICATIONS` face), and run a bounded BLE capture spike to decode
the alarm-set/chime command for a possible future watch beep.

**Architecture:** Phase A adds a `NOTIFICATIONS` value to the existing one-active-face
model. A `NotificationListenerService` keeps a live count in `Prefs`; a debounced
coroutine pushes it to the watch via `OlleeProtocol.buildWeekdayPacket` (all 7 weekday
slots set identical) only when `NOTIFICATIONS` is active. Pure count/format/packet logic
is unit-tested; the service, UI, and worker backstop are thin glue. Phase B is a
reverse-engineering runbook in the `ollee-graphene` repo, not code.

**Tech Stack:** Kotlin, Android (Compose, `NotificationListenerService`, WorkManager),
JUnit4 unit tests, existing `OlleeBleClient`/`OlleeProtocol` BLE stack.

**Spec:** `docs/superpowers/specs/2026-06-04-notification-count-and-alarm-chime-design.md`

**Conventions used throughout:**
- Unit tests: `./gradlew :app:testDebugUnitTest`
- Build: `./gradlew :app:assembleDebug`
- New code package: `com.blizzardcaron.freeolleefaces.notifications` (distinct from the
  existing `notify` package, which is *outbound error* notifications — do not confuse them).

---

## Phase A — Notification count (FreeOllee-Faces)

### Task 1: Pure count + format logic

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/notifications/NotificationCount.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/notifications/NotificationCountTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `NotificationCountTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationCountTest {

    private fun n(
        pkg: String = "com.example.app",
        clearable: Boolean = true,
        ongoing: Boolean = false,
        groupSummary: Boolean = false,
    ) = NotificationCount.ActiveNotification(pkg, clearable, ongoing, groupSummary)

    @Test fun countsOrdinaryNotifications() {
        val list = listOf(n(), n(pkg = "com.other"))
        assertEquals(2, NotificationCount.countFrom(list, ownPackage = "com.blizzardcaron.freeolleefaces"))
    }

    @Test fun excludesOngoing() {
        assertEquals(0, NotificationCount.countFrom(listOf(n(ongoing = true)), "com.me"))
    }

    @Test fun excludesNonClearable() {
        assertEquals(0, NotificationCount.countFrom(listOf(n(clearable = false)), "com.me"))
    }

    @Test fun excludesGroupSummary() {
        // A bundled app posts a summary + its children; only the children count.
        val list = listOf(n(groupSummary = true), n(), n())
        assertEquals(2, NotificationCount.countFrom(list, "com.me"))
    }

    @Test fun excludesOwnPackage() {
        val list = listOf(n(pkg = "com.blizzardcaron.freeolleefaces"), n(pkg = "com.other"))
        assertEquals(1, NotificationCount.countFrom(list, ownPackage = "com.blizzardcaron.freeolleefaces"))
    }

    @Test fun multipleFromOneAppEachCount() {
        val list = listOf(n(pkg = "com.chat"), n(pkg = "com.chat"), n(pkg = "com.chat"))
        assertEquals(3, NotificationCount.countFrom(list, "com.me"))
    }

    @Test fun formatZeroIsNull() {
        assertNull(NotificationCount.format(0))
    }

    @Test fun formatSingleDigitIsZeroPadded() {
        assertEquals("01", NotificationCount.format(1))
        assertEquals("09", NotificationCount.format(9))
    }

    @Test fun formatTwoDigits() {
        assertEquals("10", NotificationCount.format(10))
        assertEquals("99", NotificationCount.format(99))
    }

    @Test fun formatCapsAtNinetyNine() {
        assertEquals("99", NotificationCount.format(100))
        assertEquals("99", NotificationCount.format(4321))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*NotificationCountTest"`
Expected: FAIL — `NotificationCount` is unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `NotificationCount.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.notifications

/**
 * Pure logic for the notification badge shown in the watch's weekday slot. Framework-free
 * so it unit-tests without Android objects; the service maps live notifications onto
 * [ActiveNotification] before calling in.
 */
object NotificationCount {

    /** A framework-free view of one active (shade) notification. */
    data class ActiveNotification(
        val packageName: String,
        val isClearable: Boolean,
        val isOngoing: Boolean,
        val isGroupSummary: Boolean,
    )

    /**
     * Counts undismissed, non-persistent notifications: clearable, not ongoing, not a group
     * summary row, and not posted by us ([ownPackage]). Each surviving entry counts once, so
     * multiple notifications from one app all count.
     */
    fun countFrom(notifications: List<ActiveNotification>, ownPackage: String): Int =
        notifications.count {
            it.isClearable &&
                !it.isOngoing &&
                !it.isGroupSummary &&
                it.packageName != ownPackage
        }

    /**
     * Formats a count for the 2-cell slot: null at zero (caller restores the real weekday),
     * zero-padded for 1..9, plain for 10..99, capped "99" beyond (two cells can't show three
     * digits).
     */
    fun format(n: Int): String? = when {
        n <= 0 -> null
        n >= 99 -> "99"
        else -> "%02d".format(n)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*NotificationCountTest"`
Expected: PASS (all 11 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/notifications/NotificationCount.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/notifications/NotificationCountTest.kt
git commit -m "feat(notifications): pure count + 2-char format logic"
```

---

### Task 2: Weekday packet for a count (badge vs. restore)

Builds the actual BLE payload: a count shows the number in all 7 weekday slots; a zero
restores the captured default `MO…SU` so the watch looks normal.

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/notifications/NotificationCount.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/notifications/NotificationCountTest.kt`

- [ ] **Step 1: Add the failing tests**

Append these tests inside `NotificationCountTest` (and add the import
`import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol` at the top):

```kotlin
    @Test fun packetForCountFillsAllSevenSlots() {
        // 3 notifications -> "03" in every weekday slot.
        val expected = OlleeProtocol.buildWeekdayPacket(List(7) { "03" })
        assertArrayEquals(expected, NotificationCount.packetFor(3))
    }

    @Test fun packetForZeroRestoresRealWeekdays() {
        val expected = OlleeProtocol.buildWeekdayPacket(NotificationCount.REAL_WEEKDAYS)
        assertArrayEquals(expected, NotificationCount.packetFor(0))
    }

    @Test fun realWeekdaysAreTheCapturedDefault() {
        assertEquals(listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU"), NotificationCount.REAL_WEEKDAYS)
    }
```

Add the import `import org.junit.Assert.assertArrayEquals` near the other JUnit imports.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*NotificationCountTest"`
Expected: FAIL — `packetFor` / `REAL_WEEKDAYS` unresolved.

- [ ] **Step 3: Implement**

Add to `NotificationCount` (below `format`):

```kotlin
    /** The captured default weekday table (Mon..Sun), restored when the count is zero. */
    val REAL_WEEKDAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    /**
     * The weekday-table BLE packet for [n]: the formatted count in all 7 slots (so it shows
     * regardless of the current day), or the real weekday table when [n] is zero.
     */
    fun packetFor(n: Int): ByteArray {
        val label = format(n)
        val slots = if (label == null) REAL_WEEKDAYS else List(7) { label }
        return com.blizzardcaron.freeolleefaces.ble.OlleeProtocol.buildWeekdayPacket(slots)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*NotificationCountTest"`
Expected: PASS (14 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/notifications/NotificationCount.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/notifications/NotificationCountTest.kt
git commit -m "feat(notifications): weekday packet for count (badge vs. restore)"
```

---

### Task 3: Persist the live count in Prefs

The listener service (a separate process from the UI) and the worker both read the count
from `Prefs` — the single source of truth. `Prefs` is thin SharedPreferences glue with no
unit test in this repo, so this task has no test; correctness is covered where the value
is *used*.

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt`

- [ ] **Step 1: Add the property**

In `Prefs.kt`, add this property after `customSentMs` (around line 67):

```kotlin
    /** Live count of undismissed, non-persistent notifications, kept by the listener service. */
    var notificationCount: Int
        get() = sp.getInt(KEY_NOTIFICATION_COUNT, 0)
        set(value) = sp.edit { putInt(KEY_NOTIFICATION_COUNT, value) }
```

- [ ] **Step 2: Add the key constant**

In the `companion object`, after `KEY_CUSTOM_SENT_MS` (around line 139):

```kotlin
        private const val KEY_NOTIFICATION_COUNT = "notification_count"
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt
git commit -m "feat(prefs): persist live notificationCount"
```

---

### Task 4: Add NOTIFICATIONS to ActiveFace and satisfy every exhaustive `when`

Adding the enum value breaks four exhaustive `when` blocks (compile errors). This task adds
the value and a real branch in each, so the project is green and the new face already
pushes the count. Live listener pushes come in Task 5; the full UI body comes in Task 6.

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/ActiveFace.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt`

> Add the two `HomeState` fields **as part of this task** (the temporary `NotificationsBody`
> reads `state.notificationCount`). In `HomeScreen.kt`, add after `customSent` (around line
> 62):
> ```kotlin
>     val notificationCount: Int = 0,
>     val notificationAccessGranted: Boolean = false,
> ```
> Do **not** touch `HomeCallbacks` yet — adding a required field there without providing it
> in `MainActivity`'s `homeCallbacks` would break the build. That field is added together
> with its value in Task 6.

- [ ] **Step 1: Add the enum value**

In `ActiveFace.kt`, add `NOTIFICATIONS` after `CUSTOM` (note `CUSTOM;` becomes `CUSTOM,`):

```kotlin
enum class ActiveFace {
    TEMPERATURE,
    SUN,

    /** Today's step count, read from Health Connect. */
    STEPS,

    /** Not reachable via [fromLegacyAutoSource] — the legacy AutoSource enum had no CUSTOM value. */
    CUSTOM,

    /** Count of undismissed, non-persistent notifications, shown in the weekday slot. */
    NOTIFICATIONS;
```

(`fromLegacyAutoSource` is unchanged — no legacy source maps to `NOTIFICATIONS`, exactly
like `CUSTOM`.)

- [ ] **Step 2: Handle NOTIFICATIONS in the worker (backstop push)**

In `AutoUpdateWorker.kt`, add a NOTIFICATIONS early-branch mirroring STEPS, immediately
after the STEPS block (after line 55, before the `lat/lng/address` location guard):

```kotlin
        // NOTIFICATIONS needs only a watch; push the cached count as a backstop to the
        // listener's live pushes. Handle before the location guard.
        if (face == ActiveFace.NOTIFICATIONS) {
            if (address == null) {
                prefs.recordAutoSend("Skipped: set watch in app")
                applyHealth(ctx, prefs, FailureKind.SETUP_INCOMPLETE, inSleepNow(prefs))
                return Result.success()
            }
            return runNotifications(ctx, prefs, address, ZonedDateTime.now(ZoneId.systemDefault()))
        }
```

Then make the terminal `when (face)` (currently lines 64-68) exhaustive — NOTIFICATIONS is
already handled above, like STEPS/CUSTOM:

```kotlin
        return when (face) {
            ActiveFace.TEMPERATURE -> runTemperature(ctx, prefs, lat, lng, address, now)
            ActiveFace.SUN -> runSun(ctx, prefs, lat, lng, address, now)
            ActiveFace.STEPS, ActiveFace.CUSTOM, ActiveFace.NOTIFICATIONS -> Result.success() // handled above
        }
```

Add `runNotifications` next to `runSteps` (mirrors it; pushes the weekday packet instead
of a nameplate string):

```kotlin
    private suspend fun runNotifications(
        ctx: Context,
        prefs: Prefs,
        address: String,
        now: ZonedDateTime,
    ): Result {
        val sleep = if (prefs.sleepEnabled) {
            SleepWindow(prefs.sleepStartMin, prefs.sleepEndMin)
        } else null
        val nowMinOfDay = now.hour * 60 + now.minute
        val inSleep = sleep != null &&
            AutoUpdateSchedule.isInSleepWindow(nowMinOfDay, sleep.startMin, sleep.endMin)

        var backstopped = false
        if (!inSleep) {
            val count = prefs.notificationCount
            val packet = NotificationCount.packetFor(count)
            OlleeBleClient(ctx).sendPacket(address, packet)
                .onSuccess {
                    prefs.recordAutoSend("Sent notifications: $count")
                    applyHealth(ctx, prefs, null, inSleep)
                }
                .onFailure {
                    backstopped = handleSendFailure(ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep)
                }
        } else {
            prefs.recordAutoSend("Asleep (power saving)")
        }

        if (!backstopped) {
            val fire = AutoUpdateSchedule.nextTemperatureFire(now, prefs.updateIntervalMinutes, sleep)
            val delayMs = Duration.between(now, fire).toMillis().coerceAtLeast(0)
            AutoUpdateScheduler.enqueueNext(ctx, delayMs, sendAttempt = 0)
        }
        return Result.success()
    }
```

Add the import at the top of `AutoUpdateWorker.kt`:

```kotlin
import com.blizzardcaron.freeolleefaces.notifications.NotificationCount
```

- [ ] **Step 3: Handle NOTIFICATIONS in HomeScreen's two `when`s**

In `HomeScreen.kt`, add a branch to the body `when (state.activeFace)` (lines 112-117):

```kotlin
            when (state.activeFace) {
                ActiveFace.TEMPERATURE -> TemperatureBody(state, callbacks)
                ActiveFace.SUN -> SunBody(state, callbacks)
                ActiveFace.STEPS -> StepsBody(state, callbacks)
                ActiveFace.CUSTOM -> CustomBody(state, callbacks)
                ActiveFace.NOTIFICATIONS -> NotificationsBody(state, callbacks)
            }
```

Add a temporary `NotificationsBody` at the bottom of the file (Task 6 replaces it):

```kotlin
@Composable
private fun NotificationsBody(state: HomeState, callbacks: HomeCallbacks) {
    Text("Notifications: ${state.notificationCount}", style = MaterialTheme.typography.headlineMedium)
}
```

Extend `faceTitle` (lines 136-141):

```kotlin
private fun faceTitle(face: ActiveFace): String = when (face) {
    ActiveFace.TEMPERATURE -> "Temperature"
    ActiveFace.SUN -> "Sun event"
    ActiveFace.STEPS -> "Steps"
    ActiveFace.CUSTOM -> "Custom"
    ActiveFace.NOTIFICATIONS -> "Notifications"
}
```

- [ ] **Step 4: Handle NOTIFICATIONS in MainActivity's two `when`s**

In `MainActivity.kt`, `refreshActive` (lines 307-314) — push the cached count:

```kotlin
    fun refreshActive(force: Boolean, push: Boolean) {
        when (state.activeFace) {
            ActiveFace.TEMPERATURE -> refreshTemp(force, push)
            ActiveFace.SUN -> refreshSun(push)
            ActiveFace.STEPS -> refreshSteps(push)
            ActiveFace.CUSTOM -> {}
            ActiveFace.NOTIFICATIONS -> if (push) pushCountIfWatch()
        }
    }
```

And `activate` (lines 316-330):

```kotlin
        when (face) {
            ActiveFace.TEMPERATURE -> refreshTemp(force = false, push = true)
            ActiveFace.SUN -> refreshSun(push = true)
            ActiveFace.STEPS -> refreshSteps(push = true)
            ActiveFace.CUSTOM -> {
                val text = prefs.customText
                if (text.isNotEmpty()) sendCustom(text)
            }
            ActiveFace.NOTIFICATIONS -> pushCountIfWatch()
        }
```

Add the `pushCountIfWatch` helper next to `pushIfWatch` (after line 187):

```kotlin
    fun pushCountIfWatch() {
        val addr = prefs.watchAddress ?: return
        val packet = NotificationCount.packetFor(prefs.notificationCount)
        scope.launch {
            ble.sendPacket(addr, packet)
                .onSuccess { showSnackbar("Sent notifications: ${prefs.notificationCount}") }
                .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
        }
    }
```

Add the import at the top of `MainActivity.kt`:

```kotlin
import com.blizzardcaron.freeolleefaces.notifications.NotificationCount
```

- [ ] **Step 5: Build and run all unit tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all existing tests + the 14 NotificationCount tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/auto/ActiveFace.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "feat(notifications): NOTIFICATIONS face wired through worker, home, activate"
```

---

### Task 5: NotificationListenerService with debounced live push

Reads active notifications, keeps `prefs.notificationCount` current, and pushes the badge
within ~2 s of any change (coalescing bursts) when `NOTIFICATIONS` is the active face. This
is Android glue verified on-device (Task 7); it has no unit test — the logic it calls
(`NotificationCount`) is already covered.

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/notifications/NotificationCountService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Create the service**

```kotlin
package com.blizzardcaron.freeolleefaces.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.blizzardcaron.freeolleefaces.auto.ActiveFace
import com.blizzardcaron.freeolleefaces.ble.OlleeBleClient
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Counts undismissed, non-persistent notifications and pushes the badge to the watch's
 * weekday slot. Requires the user to grant "Notification access" in system settings. Live
 * pushes are debounced (~2 s) and only happen while [ActiveFace.NOTIFICATIONS] is active;
 * [com.blizzardcaron.freeolleefaces.auto.AutoUpdateWorker] is the periodic backstop.
 */
class NotificationCountService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pushJob: Job? = null
    private val prefs by lazy { Prefs(applicationContext) }

    override fun onListenerConnected() {
        // Seed and sync on (re)bind, even if the count is unchanged.
        prefs.notificationCount = computeCount()
        schedulePush()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = recomputeAndPush()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recomputeAndPush()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun recomputeAndPush() {
        val count = computeCount()
        val changed = count != prefs.notificationCount
        prefs.notificationCount = count
        if (changed) schedulePush()
    }

    private fun computeCount(): Int {
        val active = activeNotifications ?: return prefs.notificationCount
        val mapped = active.map { sbn ->
            val flags = sbn.notification.flags
            NotificationCount.ActiveNotification(
                packageName = sbn.packageName,
                isClearable = sbn.isClearable,
                isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0,
                isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            )
        }
        return NotificationCount.countFrom(mapped, ownPackage = packageName)
    }

    private fun schedulePush() {
        // Debounce: a flurry of posts/removals collapses into one push.
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(DEBOUNCE_MS)
            if (prefs.activeFace != ActiveFace.NOTIFICATIONS) return@launch
            val addr = prefs.watchAddress ?: return@launch
            OlleeBleClient(applicationContext)
                .sendPacket(addr, NotificationCount.packetFor(prefs.notificationCount))
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 2_000L
    }
}
```

- [ ] **Step 2: Add the listener label string**

In `app/src/main/res/values/strings.xml`, add inside `<resources>`:

```xml
    <string name="notification_listener_label">FreeOllee notification count</string>
```

- [ ] **Step 3: Register the service in the manifest**

In `AndroidManifest.xml`, inside `<application>` (next to the other `<service>`/`<receiver>`
entries), add:

```xml
        <service
            android:name=".notifications.NotificationCountService"
            android:label="@string/notification_listener_label"
            android:exported="false"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/notifications/NotificationCountService.kt \
        app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml
git commit -m "feat(notifications): listener service with debounced live push"
```

---

### Task 6: Notifications UI (Faces row, Home body, access grant, reserved beep)

Adds the dedicated Home body (count, access-grant card, "Update now", reserved beep
toggle), the Faces-list row, and the MainActivity access-check + grant-intent wiring. This
is Compose UI verified on-device; no unit test.

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/FacesListScreen.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt`

- [ ] **Step 1: Add the HomeCallbacks field**

The two `HomeState` fields (`notificationCount`, `notificationAccessGranted`) were already
added in Task 4. Now add to `HomeCallbacks` (after `onGrantHealth`, around line 75):

```kotlin
    val onGrantNotificationAccess: () -> Unit,
```

This makes `HomeCallbacks` require the new field; Step 4 below provides it in
`MainActivity`'s `homeCallbacks`, so do Step 1 and Step 4 together before building.

- [ ] **Step 2: Replace the temporary NotificationsBody with the full body**

In `HomeScreen.kt`, replace the temporary `NotificationsBody` from Task 4 with:

```kotlin
@Composable
private fun NotificationsBody(state: HomeState, callbacks: HomeCallbacks) {
    if (!state.notificationAccessGranted) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Notification access needed", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Grant notification access so the watch can show how many unread " +
                        "notifications you have, in the weekday slot of the Clock face.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = callbacks.onGrantNotificationAccess,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant notification access") }
            }
        }
    }
    Text("${state.notificationCount} unread", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Shown in the weekday slot on the Clock face. Persistent notifications are ignored; " +
            "zero restores the weekday.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = callbacks.onUpdateNow, modifier = Modifier.fillMaxWidth()) { Text("Update now") }
    HorizontalDivider()
    // Reserved: a watch beep on new notifications, gated on the alarm-chime BLE spike (Phase B).
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text("Beep on watch", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Coming soon — under investigation",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(checked = false, onCheckedChange = null, enabled = false)
    }
}
```

Add the import for `Switch` near the other material3 imports in `HomeScreen.kt`:

```kotlin
import androidx.compose.material3.Switch
```

- [ ] **Step 3: Add the Faces-list row**

In `FacesListScreen.kt`, after the Custom row (line 42):

```kotlin
        FaceRow("Notifications", ActiveFace.NOTIFICATIONS, active, onSelect)
```

- [ ] **Step 4: Wire access check + grant intent in MainActivity**

In `MainActivity.kt`, add these imports (none are present yet):

```kotlin
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
```

Add a top-level helper next to `labelForAddress` (near line 666):

```kotlin
private fun isNotificationAccessGranted(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
```

In the initial `HomeState { ... }` builder (lines 119-138), add:

```kotlin
                notificationCount = prefs.notificationCount,
                notificationAccessGranted = isNotificationAccessGranted(context),
```

Add the callback to `homeCallbacks` (after `onGrantHealth`, around line 547):

```kotlin
        onGrantNotificationAccess = {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
```

- [ ] **Step 5: Re-sync notification state when the app comes back from settings**

In `MainActivity.kt`, find the existing `LaunchedEffect(Unit)` (line 428) that requests
POST_NOTIFICATIONS. Immediately **after** that `LaunchedEffect` block, add a second one so
the count and access flag refresh each time Home is shown (e.g. returning from the system
notification-access screen):

```kotlin
    LaunchedEffect(screen) {
        if (screen == Screen.Home) {
            update { it.copy(
                notificationCount = prefs.notificationCount,
                notificationAccessGranted = isNotificationAccessGranted(context),
            ) }
        }
    }
```

- [ ] **Step 6: Build and run unit tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/ui/FacesListScreen.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "feat(notifications): Notifications face UI + access grant flow"
```

---

### Task 7: On-device verification (Phase A)

No code — a manual checklist on a real watch + phone (GrapheneOS). Record results in the
commit message or a short note.

- [ ] **Step 1: Install**

Run: `./gradlew :app:installDebug` (device connected via adb), or sideload the debug APK.

- [ ] **Step 2: Grant notification access**

Open the app → Faces → Notifications → "Grant notification access" → enable
"FreeOllee notification count" in the system list → back to the app. The body should now
show the count without the grant card.

- [ ] **Step 3: Select watch and activate**

In Settings (⚙) pick the bonded watch. Activate the Notifications face. With one or more
clearable notifications present, confirm the watch's upper-left weekday pair shows the
zero-padded count (e.g. `03`) on the Clock face, with the live time still in the main
digits.

- [ ] **Step 4: Live update + debounce**

Post a new notification (e.g. send yourself a message); within a few seconds the count on
the watch increments. Dismiss all notifications; the slot returns to the real weekday
(`MO`…`SU`). Trigger a burst (several at once) and confirm a single coalesced update, not
a flurry of writes.

- [ ] **Step 5: Persistent exclusion + glyph check**

Start a media/foreground-service (ongoing) notification and confirm it does **not** change
the count. Eyeball that digits render cleanly in the letter-shaped pair (per
`ollee-graphene/docs/reference/ollee-segment-font.md`).

- [ ] **Step 6: Mutual exclusivity**

Switch to the Temperature (or Steps/Custom) face; confirm the count disappears (the panel
blanks under the name-tag value) — expected, not a bug. Switch back to Notifications; the
count returns.

---

## Phase B — Alarm/chime BLE capture spike (ollee-graphene)

A reverse-engineering runbook, **not** code or TDD. It runs in the separate
`ollee-graphene` repo (`~/github/ollee-graphene`) using that repo's existing capture
harness. Goal: decode the alarm-set command (target, time, enable, chime selector) so we
can decide whether a watch beep is feasible — and to unlock a future custom-chime feature.
All commits land in `ollee-graphene`, not `FreeOllee-Faces`.

**Prerequisites:** the official Ollee app installed on a phone, the watch bonded and awake
(long-press ALARM), and `adb` access. Build artifacts and tools are already present in
`ollee-graphene` (`scripts/build.sh`, `tools/`).

### Task 8: Build and attach the capture harness

- [ ] **Step 1: Build the capture APK**

In `~/github/ollee-graphene`:

Run: `CAPTURE=1 scripts/build.sh`
Expected: `build/ollee-graphene-capture.apk` produced (applies
`patches/capture/04-ble-logging.patch`).

- [ ] **Step 2: Install it and start logging**

```bash
adb install -r build/ollee-graphene-capture.apk
adb logcat -c            # clear the buffer
adb logcat -s OLLEE_BLE  # leave running in a dedicated terminal; TX/RX print as uppercase hex
```

Confirm baseline traffic appears (e.g. selecting a watch logs `TX …`). If nothing logs,
re-check that the capture build (not the official APK) is the one driving the watch.

### Task 9: Capture the alarm-set command, varying one field at a time

The aim is to isolate which bytes encode the **time**, the **enable** flag, and the
**chime selector**. Capture one change per run, annotating each in a journal.

- [ ] **Step 1: Baseline alarm**

In the capture app, set a single alarm at a known time (e.g. 07:30) with a known chime
(`alarmChime1`), enabled. Save / sync to the watch. Record the `TX` frame(s) — look for a
write whose inner bytes start `02 ??` with a **new** target (not one already documented:
`23` time, `26` timers, `2F` nameplate, `34` weekday, `36` faces-table). Note the full hex.

- [ ] **Step 2: Vary the time only**

Change just the alarm time (e.g. 07:30 → 18:45), keep chime + enabled. Capture again. Diff
against Step 1: the bytes that change locate the **time encoding** (expect hour/minute,
possibly BCD or LE; compare with the `02 23` clock encoding for a hint).

- [ ] **Step 3: Vary the chime only**

Hold time constant; change the chime selection across several runs (`alarmChime1` →
`alarmChime2` → … → `alarmChimeClassic`). Capture each. The single byte (or small field)
that tracks the selection index is the **chime selector**.

- [ ] **Step 4: Toggle enable/disable**

Disable the alarm and sync; re-enable and sync. Capture both. The byte that flips locates
the **enable** flag.

- [ ] **Step 5: Save the raw logs**

Save the annotated logcat capture and a short journal (what was changed before each frame)
into `ollee-graphene/docs/reference/`, following the existing naming
(`ollee-capture-YYYY-MM-DD.log` + `…-journal.txt`).

- [ ] **Step 6: Cross-check (optional)**

If any frame is ambiguous, capture the Bluetooth HCI snoop log via `adb bugreport` and open
in Wireshark, filtering ATT writes to `6e400002-…`, to confirm fragmentation/ordering.

### Task 10: Decode and document the alarm-set command

- [ ] **Step 1: Write up the command**

In `ollee-graphene/docs/reference/ollee-ble-protocol.md`, add an "Alarm set" section: the
target byte, time encoding, enable byte, chime-selector mapping (`alarmChime1..14` /
`Classic` → byte values), and any remaining unknown bytes. Resolve the Alarm-face content
`❓` markers.

- [ ] **Step 2: Update the capabilities table**

In `ollee-graphene/docs/reference/ollee-watch-capabilities.md`, update the **Alarm** row
(record ID `05`) from "content ❓" to the decoded command, linking the protocol section.

- [ ] **Step 3: Commit (in ollee-graphene)**

```bash
git add docs/reference/
git commit -m "docs(reference): decode the alarm-set command (time + enable + chime selector)"
```

### Task 11: Beep decision gate

- [ ] **Step 1: Evaluate the beep**

Using the decoded command, judge whether a momentary/near-immediate one-shot alarm can
serve as a notification beep. Expected verdict: **no** — firing an alarm enters a
must-clear "Alarm ring" state (snooze, "press any button to clear"), which is not a brief
beep. Confirm by setting an immediate alarm and observing the watch.

- [ ] **Step 2: Record the verdict and route follow-up**

Append the verdict to the protocol doc's Alarm section.

- **If negative (likely):** the reserved "Beep on watch" toggle in FreeOllee-Faces stays
  disabled (Phase A ships as count-only). Optionally open a separate spec for a phone-side
  beep. The decoded command still stands as the foundation for a future custom-chime
  feature.
- **If positive:** open a small follow-up task in FreeOllee-Faces to wire the reserved
  toggle to a momentary-alarm push via the newly-decoded command (its own spec/plan).

- [ ] **Step 3: Commit the verdict (in ollee-graphene)**

```bash
git add docs/reference/ollee-ble-protocol.md
git commit -m "docs(reference): alarm-fire beep verdict + custom-chime opportunity"
```

---

## Notes for the implementer

- **Two repos.** Tasks 1–7 commit in `FreeOllee-Faces`; Tasks 8–11 commit in
  `ollee-graphene`. Don't cross the streams.
- **Phase B does not block Phase A.** Ship Tasks 1–7 (count) independently; Phase B is
  research that gates only the optional beep.
- **Don't confuse the packages:** `notify` = outbound *error* notifications (delivery
  failures); `notifications` = this feature (reading the user's notifications).
- The `NOTIFICATIONS` face and a name-tag value can never co-display (hardware blanks the
  upper panel under a name tag) — this is by design, surfaced in Task 7 Step 6.
