# Alarm-mode Quick Timer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an "Alarm mode" toggle to the Quick-timer card that lets you enter a wall-clock time (H:M AM/PM) and sends it as a calculated one-shot countdown the watch starts immediately — and fix the latent timer-header encoding bug that truncates any quick timer ≥ 256 minutes.

**Architecture:** A new pure `QuickAlarm.countdownSeconds` computes seconds-until-next-occurrence from the current local time. `OlleeProtocol.buildTimerPacket`'s header is corrected from `[00, MM, SS, mode]` to `[HH, MM, SS, mode]` (byte 0 = hours, decoded from a 2026-06-15 BLE capture of the official app). `AppViewModel` gains persisted alarm-mode state and a `sendQuickAlarm()` that reuses the existing single-countdown send path with `START_SINGLE`. The Timer card swaps its H/M/S inputs for an H:M:AM/PM row when alarm mode is on, reusing the Alarms screen's time controls (extracted to shared widgets).

**Tech Stack:** Kotlin Multiplatform (commonMain logic, androidMain UI host), Jetpack Compose Material3, `com.russhwolf.settings` (multiplatform-settings), `kotlinx-datetime`, kotlinx-coroutines test. Build: Gradle (`./gradlew`). Tests: `:app:testDebugUnitTest`.

**Spec:** `docs/superpowers/specs/2026-06-15-alarm-mode-quick-timer-design.md`

**Note on one spec refinement:** The spec proposed tightening `buildTimerPacket`'s `require` to `0..86_399`. The manual H/M/S quick-timer field allows H up to 99, so a hard upper `require` would throw on a legitimate manual entry (e.g. 30h). Instead we keep `require(headerSeconds >= 0)` and **clamp the hours byte at `0xFF`** for safety; the 23:59:59 cap is applied only on the alarm-mode path (Task 4). This preserves the spec's intent (no silent truncation in the realistic ≤24h range) without regressing manual entry.

---

## File Structure

- **Modify** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt` — fix header encoding (Task 1).
- **Create** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/timer/QuickAlarm.kt` — pure countdown math (Task 2).
- **Modify** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt` — three persisted fields (Task 3).
- **Modify** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt` — clock injection, state, `sendQuickAlarm()` (Task 4).
- **Modify** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerWidgets.kt` — shared `HourField` + hour math (Task 5).
- **Modify** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AlarmsScreen.kt` — use shared widgets (Task 5).
- **Modify** `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt` — alarm-mode card (Task 6).
- **Modify** `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt` — wiring (Task 7).
- Tests in the mirrored `commonTest` tree: `OlleeProtocolTest`, `QuickAlarmTest` (new), `PrefsTest`, `AppViewModelTest`, `HourMathTest` (new).

---

## Task 1: Fix the timer-header encoding (`buildTimerPacket`)

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt:144-168`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocolTest.kt`

**Context:** The 0x26 timer header is `[HH, MM, SS, byte3]`. Captured 2026-06-15: the official app sending 20h05m00s produced header `14 05 00 01` (byte0=0x14=20 hours). Current code leaves byte 0 = 0 and packs minutes into byte 1 clamped to `0xFF`, silently truncating any header ≥ 256 min to 4h15m. Header bytes land at packet offsets `[8]=HH, [9]=MM, [10]=SS, [11]=byte3` (see existing tests).

- [ ] **Step 1: Replace the clamp test and add hour-boundary tests**

In `OlleeProtocolTest.kt`, **delete** the test `buildTimerPacket clamps the header minutes byte when headerSeconds over 255 minutes` (lines ~236-248) and **add** these tests in its place:

```kotlin
@Test
fun `buildTimerPacket encodes the header as hours, minutes, seconds in bytes 0-2`() {
    // 20h05m00s = 72300 s -> header 14 05 00 (the 2026-06-15 captured value).
    val packet = OlleeProtocol.buildTimerPacket(List(10) { 0 }, headerSeconds = 72_300)
    assertEquals(20.toByte(), packet[8])  // hours
    assertEquals(5.toByte(), packet[9])   // minutes
    assertEquals(0.toByte(), packet[10])  // seconds
}

@Test
fun `buildTimerPacket carries minutes over 59 into the hours byte`() {
    // 75 min = 4500 s -> 1h15m -> header 01 0F 00 (was 00 4B 00 under the old model).
    val packet = OlleeProtocol.buildTimerPacket(List(10) { 0 }, headerSeconds = 4_500)
    assertEquals(1.toByte(), packet[8])
    assertEquals(15.toByte(), packet[9])
    assertEquals(0.toByte(), packet[10])
}

@Test
fun `buildTimerPacket encodes the 23h59m59s ceiling exactly`() {
    val packet = OlleeProtocol.buildTimerPacket(List(10) { 0 }, headerSeconds = 86_399)
    assertEquals(23.toByte(), packet[8])
    assertEquals(59.toByte(), packet[9])
    assertEquals(59.toByte(), packet[10])
}

@Test
fun `buildTimerPacket clamps the hours byte at 0xFF for absurd values`() {
    // 256 h = 921_600 s -> hours byte would be 256; clamp to 0xFF. Seconds/minutes still encode.
    val packet = OlleeProtocol.buildTimerPacket(List(10) { 0 }, headerSeconds = 921_600)
    assertEquals(0xFF.toByte(), packet[8])
}
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.ble.OlleeProtocolTest"`
Expected: FAIL — `packet[8]` is `0` (hours never set) and `packet[9]` is `20`/`75`/clamped, not the expected values.

- [ ] **Step 3: Implement the new header encoding**

In `OlleeProtocol.kt`, replace the three header-byte lines inside `buildTimerPacket` (currently `payload[1] = (headerSeconds / 60)...` / `payload[2] = (headerSeconds % 60)...`) with:

```kotlin
payload[0] = (headerSeconds / 3600).coerceAtMost(0xFF).toByte() // HH (hours; clamp for safety)
payload[1] = ((headerSeconds % 3600) / 60).toByte()             // MM (0-59)
payload[2] = (headerSeconds % 60).toByte()                      // SS (0-59)
payload[3] = startMode.byte3.toByte()                           // start/mode selector
```

Then update the KDoc above `buildTimerPacket` (the paragraph describing `[00, MM, SS, byte3]` and "Minutes are clamped to one byte (0xFF max) when headerSeconds ≥ 256 minutes"): change it to describe `[HH, MM, SS, byte3]` — byte 0 = hours, decoded from a 2026-06-15 capture of the official app sending 20h05m00s (`14 05 00 01`); the hours byte is clamped to `0xFF` only for values beyond ~255h.

- [ ] **Step 4: Run the whole protocol suite to verify pass and no regressions**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.ble.OlleeProtocolTest"`
Expected: PASS. The captured-frame tests (180 s, 427 s, 284 s) still pass unchanged — those values are < 1 h so hours = 0 and the bytes/CRC are identical.

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocolTest.kt
git commit -m "fix(timer): encode quick-timer header as [HH, MM, SS] (byte0 = hours)

Decoded from a BLE capture of the official app sending 20h05m00s
(header 14 05 00 01). Fixes silent 4h15m truncation for headers >=256 min.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `QuickAlarm.countdownSeconds` pure logic

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/timer/QuickAlarm.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/timer/QuickAlarmTest.kt`

**Context:** Pure math, no UI/Android deps, mirroring the `AlarmSchedule`/`TimerSetEditing` style. Returns seconds until the next occurrence of a target wall-clock time, rolling forward a full day when the target is at or before now.

- [ ] **Step 1: Write the failing test**

Create `QuickAlarmTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.timer

import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class QuickAlarmTest {

    @Test
    fun `target later today returns the forward delta`() {
        // 22:00 -> 23:00 = 1 h.
        assertEquals(3600, QuickAlarm.countdownSeconds(LocalTime(22, 0), 23, 0))
    }

    @Test
    fun `target earlier today rolls to the next day`() {
        // 22:00 -> 07:00 next day = 9 h.
        assertEquals(9 * 3600, QuickAlarm.countdownSeconds(LocalTime(22, 0), 7, 0))
    }

    @Test
    fun `target equal to now rolls a full day`() {
        assertEquals(86_400, QuickAlarm.countdownSeconds(LocalTime(22, 0), 22, 0))
    }

    @Test
    fun `accounts for the current seconds within the minute`() {
        // 22:00:30 -> 22:01:00 = 30 s.
        assertEquals(30, QuickAlarm.countdownSeconds(LocalTime(22, 0, 30), 22, 1))
    }

    @Test
    fun `wraps across midnight`() {
        // 23:59:00 -> 00:00 = 60 s.
        assertEquals(60, QuickAlarm.countdownSeconds(LocalTime(23, 59, 0), 0, 0))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.timer.QuickAlarmTest"`
Expected: FAIL — `QuickAlarm` is unresolved.

- [ ] **Step 3: Write the implementation**

Create `QuickAlarm.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.timer

import kotlinx.datetime.LocalTime

/** Pure clock math for the alarm-mode quick timer — no UI/Android deps. */
object QuickAlarm {

    /**
     * Seconds from [now] until the next occurrence of [targetHour]:[targetMinute] (target
     * seconds = 0). Rolls forward a full day when the target is at or before [now], so the
     * result is always in 1..86400. A target equal to [now] returns 86400 (next day).
     */
    fun countdownSeconds(now: LocalTime, targetHour: Int, targetMinute: Int): Int {
        val target = targetHour * 3600 + targetMinute * 60
        val nowSec = now.hour * 3600 + now.minute * 60 + now.second
        val delta = ((target - nowSec) % 86_400 + 86_400) % 86_400
        return if (delta == 0) 86_400 else delta
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.timer.QuickAlarmTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/timer/QuickAlarm.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/timer/QuickAlarmTest.kt
git commit -m "feat(timer): add QuickAlarm.countdownSeconds for alarm-mode quick timer

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Persist alarm-mode state in `Prefs`

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt` (add fields after `quickTimerIntervalMode` at line ~97; add keys in the `companion object` after `KEY_QUICK_TIMER_INTERVAL` at line ~179)
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/prefs/PrefsTest.kt`

**Context:** Three new persisted fields, consistent with the existing `quickTimerSeconds` / `quickTimerStartFromApp` / `quickTimerIntervalMode`. The alarm hour is stored 0–23 (24h, like the `Alarm` model); the UI shows 12h + AM/PM.

- [ ] **Step 1: Write the failing test**

Add to `PrefsTest.kt` (after the `quickTimerSeconds` test block, ~line 221):

```kotlin
@Test
fun `quickTimerAlarmMode defaults to false and round-trips`() {
    val settings = MapSettings()
    assertEquals(false, Prefs(settings).quickTimerAlarmMode)
    Prefs(settings).quickTimerAlarmMode = true
    assertEquals(true, Prefs(settings).quickTimerAlarmMode)
}

@Test
fun `quickTimerAlarmHour defaults to 7 and round-trips`() {
    val settings = MapSettings()
    assertEquals(7, Prefs(settings).quickTimerAlarmHour)
    Prefs(settings).quickTimerAlarmHour = 22
    assertEquals(22, Prefs(settings).quickTimerAlarmHour)
}

@Test
fun `quickTimerAlarmMinute defaults to 0 and round-trips`() {
    val settings = MapSettings()
    assertEquals(0, Prefs(settings).quickTimerAlarmMinute)
    Prefs(settings).quickTimerAlarmMinute = 45
    assertEquals(45, Prefs(settings).quickTimerAlarmMinute)
}
```

(`MapSettings` and `assertEquals` are already imported in `PrefsTest.kt`.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.prefs.PrefsTest"`
Expected: FAIL — `quickTimerAlarmMode`/`Hour`/`Minute` unresolved.

- [ ] **Step 3: Add the fields**

In `Prefs.kt`, after the `quickTimerIntervalMode` property (line ~97):

```kotlin
/** Alarm-mode quick timer: when on, the card takes a wall-clock target time instead of H/M/S. */
var quickTimerAlarmMode: Boolean
    get() = settings.getBoolean(KEY_QUICK_TIMER_ALARM_MODE, false)
    set(value) = settings.putBoolean(KEY_QUICK_TIMER_ALARM_MODE, value)

/** Alarm-mode target hour, 0..23 (24h; UI renders 12h + AM/PM). */
var quickTimerAlarmHour: Int
    get() = settings.getInt(KEY_QUICK_TIMER_ALARM_HOUR, 7)
    set(value) = settings.putInt(KEY_QUICK_TIMER_ALARM_HOUR, value.coerceIn(0, 23))

/** Alarm-mode target minute, 0..59. */
var quickTimerAlarmMinute: Int
    get() = settings.getInt(KEY_QUICK_TIMER_ALARM_MINUTE, 0)
    set(value) = settings.putInt(KEY_QUICK_TIMER_ALARM_MINUTE, value.coerceIn(0, 59))
```

In the `companion object`, after `KEY_QUICK_TIMER_INTERVAL` (line ~179):

```kotlin
private const val KEY_QUICK_TIMER_ALARM_MODE = "quick_timer_alarm_mode"
private const val KEY_QUICK_TIMER_ALARM_HOUR = "quick_timer_alarm_hour"
private const val KEY_QUICK_TIMER_ALARM_MINUTE = "quick_timer_alarm_minute"
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.prefs.PrefsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/prefs/PrefsTest.kt
git commit -m "feat(timer): persist quick-timer alarm-mode state in Prefs

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: AppViewModel — clock injection, state, `sendQuickAlarm()`

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt` (constructor ~line 80-92; state props ~line 103-108; new setters/send near `sendQuickTimer` ~line 250-300)
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/AppViewModelTest.kt`

**Context:** `AppViewModel` reads `Clock.System` directly today. To make `sendQuickAlarm` testable, add an injectable `clock: Clock = Clock.System` constructor param (mirrors `Prefs`). `sendQuickAlarm` computes the countdown from the injected clock and pushes a `START_SINGLE` frame via the existing `pushTimerFrame` path — the same slot-selection logic as `sendQuickTimer` (active set's durations, or zeros). `FakeBleClient` exposes `ble.sentPackets` (a `List<ByteArray>`); header bytes are at offsets `[8]=HH, [9]=MM, [10]=SS, [11]=byte3`.

- [ ] **Step 1: Write the failing test**

Add to `AppViewModelTest.kt` (after Test C2b, ~line 388). Note: it computes the expected header from the same clock + `QuickAlarm` so it is timezone-independent.

```kotlin
@Test
fun sendQuickAlarm_sendsStartSingleFrameWithComputedCountdown() = runTest(testScheduler) {
    val callLog = mutableListOf<String>()
    val ble = FakeBleClient(callLog)
    val settings = MapSettings()
    val prefs = Prefs(settings)
    prefs.watchAddress = "00:11:22:33:44:55"
    // Fixed instant so the computed countdown is deterministic relative to the assertion.
    val fixedClock = object : Clock {
        override fun now() = kotlinx.datetime.Instant.parse("2026-06-15T12:00:00Z")
    }
    val vm = AppViewModel(
        prefs = prefs,
        ble = ble,
        steps = FakeStepsProvider(),
        location = FakeLocationProvider(),
        notificationAccess = FakeNotificationAccessChecker(),
        timerRepo = TimerSetsRepository(MapSettings()),
        scheduler = FakeScheduler(callLog),
        alarmRepo = AlarmsRepository(MapSettings()),
        alarmScheduler = FakeAlarmScheduler(callLog),
        clock = fixedClock,
    )

    vm.saveQuickTimerAlarmTime(hour = 14, minute = 30)
    vm.sendQuickAlarm()
    advanceUntilIdle()

    // Expected countdown via the same path the VM uses (timezone-agnostic).
    val now = fixedClock.now()
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).time
    val expected = com.blizzardcaron.freeolleefaces.timer.QuickAlarm
        .countdownSeconds(now, 14, 30).coerceAtMost(86_399)

    val pkt = ble.sentPackets.single()
    assertEquals(0x01.toByte(), pkt[11], "alarm send must be START_SINGLE")
    assertEquals((expected / 3600).toByte(), pkt[8], "header hours")
    assertEquals(((expected % 3600) / 60).toByte(), pkt[9], "header minutes")
    assertEquals((expected % 60).toByte(), pkt[10], "header seconds")
}

@Test
fun toggleQuickTimerAlarmMode_persistsAndUpdatesState() = runTest(testScheduler) {
    val settings = MapSettings()
    val prefs = Prefs(settings)
    val vm = AppViewModel(
        prefs = prefs,
        ble = FakeBleClient(),
        steps = FakeStepsProvider(),
        location = FakeLocationProvider(),
        notificationAccess = FakeNotificationAccessChecker(),
        timerRepo = TimerSetsRepository(MapSettings()),
        scheduler = FakeScheduler(),
        alarmRepo = AlarmsRepository(MapSettings()),
        alarmScheduler = FakeAlarmScheduler(),
    )
    vm.toggleQuickTimerAlarmMode(true)
    assertTrue(vm.quickTimerAlarmMode, "state updated")
    assertTrue(prefs.quickTimerAlarmMode, "persisted to prefs")
}
```

Add the import `import kotlinx.datetime.toLocalDateTime` at the top of `AppViewModelTest.kt` (the file already imports `kotlinx.datetime.Clock`).

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.AppViewModelTest"`
Expected: FAIL — `clock` param, `saveQuickTimerAlarmTime`, `sendQuickAlarm`, `toggleQuickTimerAlarmMode`, `quickTimerAlarmMode` unresolved.

- [ ] **Step 3: Add the constructor param, state, setters, and send**

In `AppViewModel.kt` constructor (after `watchConnection` at line ~91), add:

```kotlin
    private val clock: Clock = Clock.System,
```

After the `quickTimerIntervalMode` state property (line ~108), add:

```kotlin
    var quickTimerAlarmMode by mutableStateOf(prefs.quickTimerAlarmMode)
        private set
    var quickTimerAlarmHour by mutableStateOf(prefs.quickTimerAlarmHour)
        private set
    var quickTimerAlarmMinute by mutableStateOf(prefs.quickTimerAlarmMinute)
        private set
```

After `toggleQuickTimerIntervalMode` (line ~265), add:

```kotlin
    fun toggleQuickTimerAlarmMode(enabled: Boolean) {
        prefs.quickTimerAlarmMode = enabled
        quickTimerAlarmMode = enabled
    }

    fun saveQuickTimerAlarmTime(hour: Int, minute: Int) {
        prefs.quickTimerAlarmHour = hour
        prefs.quickTimerAlarmMinute = minute
        quickTimerAlarmHour = prefs.quickTimerAlarmHour     // read back to apply coercion
        quickTimerAlarmMinute = prefs.quickTimerAlarmMinute
    }

    /**
     * Alarm-mode quick timer: compute the countdown to the next occurrence of the saved
     * wall-clock target and push it as a single countdown the watch starts immediately.
     * Capped at 23:59:59 so the header hours byte stays <= 23 (the watch UI's own max).
     */
    fun sendQuickAlarm() {
        val now = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
        val seconds = QuickAlarm.countdownSeconds(now, quickTimerAlarmHour, quickTimerAlarmMinute)
            .coerceAtMost(86_399)
        val slots = timerActiveId?.let { timerRepo.get(it) }?.durations() ?: List(10) { 0 }
        val packet = OlleeProtocol.buildTimerPacket(
            slots, headerSeconds = seconds, startMode = OlleeProtocol.TimerStartMode.START_SINGLE)
        pushTimerFrame(packet, "Started alarm timer on watch")
    }
```

Add the import `import com.blizzardcaron.freeolleefaces.timer.QuickAlarm` near the other `timer.*` imports. (`LocalTime`, `TimeZone`, `toLocalDateTime`, `Clock`, `OlleeProtocol` are already imported.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.AppViewModelTest"`
Expected: PASS (all existing AppViewModel tests still pass — the new `clock` param is defaulted, so their constructor calls are unchanged).

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/AppViewModelTest.kt
git commit -m "feat(timer): sendQuickAlarm computes countdown from wall-clock target

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Extract shared time controls into `TimerWidgets.kt`

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerWidgets.kt` (add `HourField`, `hour24`, `hour12Of`, `isPm`)
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AlarmsScreen.kt` (remove the private copies; use the shared ones)
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ui/HourMathTest.kt` (new)

**Context:** Both the Alarms screen and the new alarm-mode timer card need the H + AM/PM controls. `AlarmsScreen` currently holds a private `HourField` composable (lines ~196-211) and a private `hour24` helper (line ~214), plus inline `isPm`/`hour12` math in `AlarmCard` (lines ~111-112). Move the reusable pieces to `TimerWidgets.kt` (alongside `NumberField`) as `internal`, and add small pure helpers so both screens share one source of truth. Pure refactor — no behavior change on the Alarms screen.

- [ ] **Step 1: Write the failing test for the pure hour math**

Create `HourMathTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class HourMathTest {

    @Test
    fun `hour24 maps 12-hour + AM PM to 0..23`() {
        assertEquals(0, hour24(12, pm = false))   // 12 AM = midnight
        assertEquals(12, hour24(12, pm = true))   // 12 PM = noon
        assertEquals(7, hour24(7, pm = false))    // 7 AM
        assertEquals(19, hour24(7, pm = true))    // 7 PM
    }

    @Test
    fun `hour12Of maps 0..23 to a 12-hour dial`() {
        assertEquals(12, hour12Of(0))   // midnight shows 12
        assertEquals(12, hour12Of(12))  // noon shows 12
        assertEquals(7, hour12Of(7))
        assertEquals(7, hour12Of(19))
    }

    @Test
    fun `isPm reflects the 24-hour value`() {
        assertEquals(false, isPm(0))
        assertEquals(false, isPm(11))
        assertEquals(true, isPm(12))
        assertEquals(true, isPm(23))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.ui.HourMathTest"`
Expected: FAIL — `hour24`/`hour12Of`/`isPm` unresolved in package `ui`.

- [ ] **Step 3: Add the shared widgets and helpers to `TimerWidgets.kt`**

Append to `TimerWidgets.kt` (it already imports `OutlinedTextField`, `Text`, `KeyboardOptions`, `KeyboardType`, `Modifier`, `width`, `dp`; add `import androidx.compose.runtime.getValue`, `import androidx.compose.runtime.mutableStateOf`, `import androidx.compose.runtime.remember`, `import androidx.compose.runtime.setValue`):

```kotlin
/** 12-hour clock + AM/PM back to the 0..23 hour stored by the alarm/timer models (12 AM = 0, 12 PM = 12). */
internal fun hour24(hour12: Int, pm: Boolean): Int = (hour12 % 12) + if (pm) 12 else 0

/** The 12-hour dial value (1..12) for a 0..23 hour (0 and 12 both show 12). */
internal fun hour12Of(hour24: Int): Int = if (hour24 % 12 == 0) 12 else hour24 % 12

/** True when a 0..23 hour is in the PM half. */
internal fun isPm(hour24: Int): Boolean = hour24 >= 12

/**
 * Hour entry for the 12-hour clock. Keeps a local edit buffer so the field can pass through
 * empty/out-of-range text while typing (e.g. clearing "12" to type "8"), committing only 1..12.
 */
@Composable
internal fun HourField(value: Int, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val t = raw.filter(Char::isDigit).take(2)
            text = t
            t.toIntOrNull()?.takeIf { it in 1..12 }?.let(onCommit)
        },
        label = { Text("H") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(80.dp),
    )
}
```

Also add `import androidx.compose.runtime.Composable` if not already present.

- [ ] **Step 4: Update `AlarmsScreen.kt` to use the shared versions**

In `AlarmsScreen.kt`: delete the private `HourField` composable (lines ~189-211) and the private `hour24` function (lines ~213-214). In `AlarmCard`, replace the inline lines:

```kotlin
val isPm = alarm.hour >= 12
val hour12 = if (alarm.hour % 12 == 0) 12 else alarm.hour % 12
```

with:

```kotlin
val isPm = isPm(alarm.hour)
val hour12 = hour12Of(alarm.hour)
```

(`HourField`, `hour24`, `isPm`, `hour12Of` now resolve from the same `ui` package — no import needed. Remove the now-unused `KeyboardOptions`/`KeyboardType`/`width`/`remember`/`mutableStateOf`/`getValue`/`setValue` imports from `AlarmsScreen.kt` only if they are no longer referenced there.)

- [ ] **Step 5: Run the hour-math test and build to verify**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.ui.HourMathTest"`
Expected: PASS.
Run: `./gradlew :app:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL (AlarmsScreen still compiles against the shared widgets).

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerWidgets.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AlarmsScreen.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ui/HourMathTest.kt
git commit -m "refactor(ui): share HourField + hour math between Alarms and Timer

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Alarm-mode UI on the Quick-timer card (`TimerSetsScreen`)

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt` (function signature ~line 30-52; Quick-timer `Card` ~line 60-93)

**Context:** Add alarm-mode params/callbacks and branch the card body. When alarm mode is off the card is exactly as today. When on, the H/M/S row is replaced by an H : M : AM/PM row, both existing toggles are hidden, and the send button reads "▶ Send alarm". A preview line shows the resolved fire time and the countdown, recomputed each recomposition from `Clock.System`. No automated UI test (this codebase has no Compose UI tests); verified by compile + the hardware check in Task 8.

- [ ] **Step 1: Add the new parameters to `TimerSetsScreen`**

In the parameter list (after `onToggleIntervalMode` at line ~40), add:

```kotlin
    quickTimerAlarmMode: Boolean,
    quickTimerAlarmHour: Int,
    quickTimerAlarmMinute: Int,
    onToggleAlarmMode: (Boolean) -> Unit,
    onSaveAlarmTime: (Int, Int) -> Unit,
    onSendAlarm: () -> Unit,
```

- [ ] **Step 2: Replace the Quick-timer `Card` body with the alarm-mode branch**

Replace the inner `Column` of the Quick-timer `Card` (the block from `Text("Quick timer", ...)` through the closing of that `Column`, lines ~64-92) with:

```kotlin
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Quick timer", style = MaterialTheme.typography.titleMedium)
                ToggleRow("Alarm mode", quickTimerAlarmMode, onToggleAlarmMode)

                if (quickTimerAlarmMode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val pm = isPm(quickTimerAlarmHour)
                        HourField(hour12Of(quickTimerAlarmHour)) {
                            onSaveAlarmTime(hour24(it, pm), quickTimerAlarmMinute)
                        }
                        NumberField("M", quickTimerAlarmMinute) {
                            onSaveAlarmTime(quickTimerAlarmHour, it.coerceIn(0, 59))
                        }
                        TextButton(onClick = {
                            onSaveAlarmTime(hour24(hour12Of(quickTimerAlarmHour), !pm), quickTimerAlarmMinute)
                        }) { Text(if (pm) "PM" else "AM") }
                    }
                    Text(
                        alarmPreview(quickTimerAlarmHour, quickTimerAlarmMinute),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onSendAlarm, enabled = !sending, modifier = Modifier.fillMaxWidth()) {
                        Text("▶ Send alarm")
                    }
                } else {
                    val (h, m, s) = TimerSetEditing.secondsToHms(quickTimerSeconds)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NumberField("H", h) { onSaveQuick(TimerSetEditing.hmsToSeconds(it, m, s)) }
                        NumberField("M", m) { onSaveQuick(TimerSetEditing.hmsToSeconds(h, it, s)) }
                        NumberField("S", s) { onSaveQuick(TimerSetEditing.hmsToSeconds(h, m, it)) }
                    }
                    ToggleRow("Start timer from app", quickTimerStartFromApp, onToggleStartFromApp)
                    ToggleRow(
                        "Interval mode",
                        quickTimerIntervalMode,
                        onToggleIntervalMode,
                        enabled = quickTimerStartFromApp,
                    )
                    val sendLabel = when {
                        !quickTimerStartFromApp -> "Send to watch"
                        quickTimerIntervalMode -> "▶ Send & start intervals"
                        else -> "▶ Send & start quick timer"
                    }
                    Button(onClick = onSendQuick, enabled = !sending, modifier = Modifier.fillMaxWidth()) {
                        Text(sendLabel)
                    }
                }
            }
```

- [ ] **Step 3: Add the `alarmPreview` helper and imports**

At the bottom of `TimerSetsScreen.kt` (file scope, after the `TimerSetRow` composable), add:

```kotlin
/** "Fires 7:00 AM · in 9h 0m" — resolved from the current time; recomputed on recomposition. */
private fun alarmPreview(targetHour: Int, targetMinute: Int): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
    val raw = QuickAlarm.countdownSeconds(now, targetHour, targetMinute)
    val capped = raw > 86_399
    val delta = raw.coerceAtMost(86_399)
    val ampm = if (isPm(targetHour)) "PM" else "AM"
    val fires = "${hour12Of(targetHour)}:${targetMinute.toString().padStart(2, '0')} $ampm"
    val span = "${delta / 3600}h ${(delta % 3600) / 60}m"
    return "Fires $fires · in $span" + if (capped) " (capped)" else ""
}
```

Add these imports to `TimerSetsScreen.kt`:

```kotlin
import com.blizzardcaron.freeolleefaces.timer.QuickAlarm
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
```

(`Button`, `Text`, `TextButton`, `Row`, `Alignment`, `Arrangement`, `MaterialTheme`, `NumberField`, `HourField`, `hour12Of`, `hour24`, `isPm` are already in scope — `NumberField`/`HourField`/the hour helpers are same-package from Task 5.)

- [ ] **Step 4: Compile to verify the screen builds**

Run: `./gradlew :app:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt
git commit -m "feat(timer): alarm-mode input on the Quick-timer card

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: Wire the new state/callbacks in `MainActivity`

**Files:**
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt:319-340`

- [ ] **Step 1: Pass the alarm-mode state and callbacks**

In the `TimerSetsScreen(...)` call, after `onSendQuick = { viewModel.sendQuickTimer() },` (line ~329), add:

```kotlin
            quickTimerAlarmMode = viewModel.quickTimerAlarmMode,
            quickTimerAlarmHour = viewModel.quickTimerAlarmHour,
            quickTimerAlarmMinute = viewModel.quickTimerAlarmMinute,
            onToggleAlarmMode = { viewModel.toggleQuickTimerAlarmMode(it) },
            onSaveAlarmTime = { h, m -> viewModel.saveQuickTimerAlarmTime(h, m) },
            onSendAlarm = { viewModel.sendQuickAlarm() },
```

- [ ] **Step 2: Build the debug APK to verify the host compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "feat(timer): wire alarm-mode quick timer into MainActivity

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: Full verification, docs, and memory

**Files:**
- Modify: `README.md` (Timers feature paragraph)
- Modify: `app/src/commonMain/.../ble/OlleeProtocol.kt` KDoc already updated in Task 1 — no change here.

- [ ] **Step 1: Run the entire test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all suites green (`OlleeProtocolTest`, `QuickAlarmTest`, `PrefsTest`, `AppViewModelTest`, `HourMathTest`, and the untouched rest).

- [ ] **Step 2: Hardware verification (human-in-loop, needs the watch + Ken)**

This is the whole point of the feature — confirm a long alarm is NOT truncated. The debug build logs every outgoing frame under `OLLEE_BLE` as `FreeOllee TX <addr> <hex>`.

```bash
./gradlew :app:installDebug
adb logcat -c
adb logcat -s OLLEE_BLE   # leave running / capture to a file
```

Ask Ken to wake the watch (press-and-hold ALARM on the clock face), open the **debug** FreeOllee app's Timer screen, toggle **Alarm mode** on, set a target ~6 hours ahead, and tap **Send alarm**. In the captured `FreeOllee TX` frame, locate the bytes after `...0226`: the header should be `[HH, MM, SS, 01]` with **HH ≈ 06** (not `00`/clamped). Confirm the watch's Timer face shows roughly a 6-hour countdown. Record the result.

- [ ] **Step 3: Update the README Timers paragraph**

In `README.md`, in the **Timers** feature bullet, add a sentence after the quick-timer description:

```markdown
The quick timer also has an **alarm mode** — flip it on to enter a wall-clock time
(H:M AM/PM) instead of a duration, and the app sends the watch a one-shot countdown that
fires at that time (rolling to the next day for times already past, up to ~24h out).
```

- [ ] **Step 4: Commit the docs**

```bash
git add README.md
git commit -m "docs: note alarm-mode quick timer in README

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 5: Update memory after hardware confirmation**

Once Step 2 confirms the hours byte on hardware, update `project_capture_rig.md` / `project_timer_decouple.md` to drop the "buggy / needs fix" framing and record that the `[HH, MM, SS]` encoding is shipped and hardware-verified, with the alarm-mode feature landed. (Memory edits are not committed to git.)

- [ ] **Step 6: Finish the branch**

Use `superpowers:finishing-a-development-branch` to open a PR (respecting the no-workday-push window). Suggested PR title: "feat(timer): alarm-mode quick timer + [HH,MM,SS] header fix".

---

## Self-Review

**Spec coverage:**
- Behavior (alarm-mode toggle, H:M:AM/PM, START_SINGLE, next-day rollover) → Tasks 2, 4, 6. ✓
- Encoding fix `[HH, MM, SS, mode]` → Task 1. ✓
- `QuickAlarm.countdownSeconds` pure logic → Task 2. ✓
- Prefs state (mode/hour/minute), separate from `quickTimerSeconds`, recomputed at send → Tasks 3, 4. ✓
- UI: Alarm-mode `ToggleRow`, H:M:AM/PM row, hidden toggles, "Send alarm", preview line; shared `HourField`/`hour24` extraction → Tasks 5, 6. ✓
- Edge cases: target == now → +24h, cap 23:59:59 → Task 2 (returns 86400), Task 4/6 (`coerceAtMost(86_399)`). ✓
- Testing: `QuickAlarmTest`, `OlleeProtocolTest` boundaries, `AppViewModelTest` alarm send, plus `PrefsTest` and `HourMathTest` → Tasks 1-5. ✓
- Out of scope (no live tick, no phone scheduling, single one-shot) respected. ✓

**Deviation from spec (documented in the header):** `buildTimerPacket` keeps `require(headerSeconds >= 0)` and clamps the hours byte at `0xFF` rather than adding a hard `0..86_399` require, to avoid throwing on manual H>23 entries. The 23:59:59 cap is enforced on the alarm-mode path instead. Net effect matches the spec's intent (no silent truncation in the realistic range).

**Placeholder scan:** No TBD/TODO; every code step shows full code. ✓

**Type consistency:** `countdownSeconds(now: LocalTime, targetHour: Int, targetMinute: Int): Int`, `saveQuickTimerAlarmTime(hour, minute)`, `toggleQuickTimerAlarmMode(Boolean)`, `sendQuickAlarm()`, `hour24(hour12, pm)`, `hour12Of(hour24)`, `isPm(hour24)` are used identically across Tasks 2–7. Header offsets `[8]/[9]/[10]/[11]` consistent with existing `OlleeProtocolTest`. ✓
