# 5-Alarm Scheduler Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give FreeOllee-Faces up to 5 phone-side alarms (time + enabled + day-of-week mask + per-alarm chime) that compute the next fire and keep the watch's single `0x25` alarm armed, re-arming after each fire via AlarmManager.

**Architecture:** A pure commonMain core (`Alarm` model, `AlarmsJson`, `AlarmsRepository`, and `AlarmSchedule` which computes the next fire and the exact `0x25` packet to send) drives an Android re-arm engine (`AndroidAlarmScheduler` + `AlarmRearm` + `AlarmRearmReceiver`) that pushes over BLE and schedules an exact AlarmManager trigger. The ViewModel and a new `Screen.Alarms` (inline cards) expose CRUD; every edit calls `alarmScheduler.rearm()`.

**Tech Stack:** Kotlin Multiplatform (commonMain logic, androidMain host), Jetpack Compose Material3, `com.russhwolf.settings`, `kotlinx-datetime`, AlarmManager (`USE_EXACT_ALARM`), kotlinx-coroutines test. Build: `./gradlew`. Tests: `:app:testDebugUnitTest`.

**Branch:** `alarm-scheduler`, stacked on `timer-enhancements` (both ship in one release). **Source of truth:** `docs/superpowers/specs/2026-06-10-alarm-scheduler-design.md` (incl. the verified disarm finding).

---

## File Structure

**commonMain** (`app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/`)
- `alarm/Alarm.kt` — the `Alarm` data class + day-mask helpers (Task 1).
- `alarm/AlarmsJson.kt` — pure codec, never throws (Task 2).
- `alarm/AlarmsRepository.kt` — persistence, max 5 (Task 3).
- `alarm/AlarmSchedule.kt` — pure `nextFire`, `packetFor`, `formatNext` (Tasks 4–5).
- `auto/AlarmScheduler.kt` — `interface AlarmScheduler { fun rearm() }` (Task 6).
- `AppViewModel.kt` — alarm state + CRUD (Task 7).
- `ui/Screen.kt` — add `Alarms` (Task 8).
- `ui/AlarmsScreen.kt` — the inline-cards screen (Task 8).

**androidMain** (`app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/`)
- `prefs/AndroidSettings.kt` — add `alarmSettings(context)` (Task 6).
- `auto/AndroidAlarmScheduler.kt` — `AlarmScheduler` impl (Task 6).
- `auto/AlarmRearm.kt` — compute → push → schedule AlarmManager (Task 6).
- `auto/AlarmRearmReceiver.kt` — exact-alarm receiver (Task 6).
- `auto/BootReceiver.kt` — also re-arm alarms on boot (Task 6).
- `AndroidManifest.xml` — `USE_EXACT_ALARM` + receiver (Task 6).
- `MainActivity.kt` — inject repo/scheduler; render `Screen.Alarms`; Home button (Tasks 7–8).

**Tests** (`app/src/commonTest/.../`): `alarm/AlarmTest.kt`, `alarm/AlarmsJsonTest.kt`, `alarm/AlarmsRepositoryTest.kt`, `alarm/AlarmScheduleTest.kt`, `AppViewModelTest.kt` additions, `fakes/Fakes.kt` (`FakeAlarmScheduler`).

---

## Task 1: Alarm model + day-mask helpers

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/alarm/Alarm.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blizzardcaron.freeolleefaces.alarm

import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmTest {
    @Test fun `repeatsOn maps bit0 to Monday and bit6 to Sunday`() {
        val monAndSun = Alarm.bit(DayOfWeek.MONDAY) or Alarm.bit(DayOfWeek.SUNDAY)
        val a = Alarm(id = "1", hour = 7, minute = 0, daysMask = monAndSun)
        assertTrue(a.repeatsOn(DayOfWeek.MONDAY))
        assertTrue(a.repeatsOn(DayOfWeek.SUNDAY))
        assertFalse(a.repeatsOn(DayOfWeek.TUESDAY))
    }

    @Test fun `ALL_DAYS repeats every weekday`() {
        val a = Alarm(id = "1", hour = 6, minute = 30, daysMask = Alarm.ALL_DAYS)
        DayOfWeek.entries.forEach { assertTrue(a.repeatsOn(it), "should repeat on $it") }
        assertEquals(0x7F, Alarm.ALL_DAYS)
    }

    @Test fun `rejects out-of-range fields`() {
        assertFailsWith<IllegalArgumentException> { Alarm(id = "1", hour = 24, minute = 0) }
        assertFailsWith<IllegalArgumentException> { Alarm(id = "1", hour = 0, minute = 60) }
        assertFailsWith<IllegalArgumentException> { Alarm(id = "1", hour = 0, minute = 0, daysMask = 0x80) }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.alarm.AlarmTest"`
Expected: FAIL — `Alarm` unresolved.

- [ ] **Step 3: Create the model**

```kotlin
package com.blizzardcaron.freeolleefaces.alarm

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber

/**
 * One phone-side alarm. The watch stores only HH:MM + chime + enable (no day field), so [daysMask]
 * lives here: bit (isoDayNumber-1) set means "repeats that weekday" — bit0=Mon … bit6=Sun. [label]
 * is phone-side only; [chimeIndex] is the watch chime (0..13). A new alarm defaults to all 7 days.
 */
data class Alarm(
    val id: String,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val daysMask: Int = ALL_DAYS,
    val chimeIndex: Int = 0,
    val label: String = "",
) {
    init {
        require(hour in 0..23) { "hour must be 0..23 (got $hour)" }
        require(minute in 0..59) { "minute must be 0..59 (got $minute)" }
        require(daysMask in 0..0x7F) { "daysMask is 7 bits (got $daysMask)" }
        require(chimeIndex in 0..0xFF) { "chimeIndex must be a single byte (got $chimeIndex)" }
    }

    /** True if this alarm repeats on [day] (its bit is set in [daysMask]). */
    fun repeatsOn(day: DayOfWeek): Boolean = (daysMask shr (day.isoDayNumber - 1)) and 1 == 1

    companion object {
        const val ALL_DAYS = 0x7F
        /** The single-bit mask for [day] (Mon=bit0 … Sun=bit6). */
        fun bit(day: DayOfWeek): Int = 1 shl (day.isoDayNumber - 1)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.alarm.AlarmTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/alarm/Alarm.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmTest.kt
git commit -m "feat: Alarm model with day-of-week mask helpers"
```

---

## Task 2: AlarmsJson codec

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmsJson.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmsJsonTest.kt`

Mirror `timer/TimerSetsJson.kt`: decoding NEVER throws (malformed/missing → empty list); malformed entries are skipped.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blizzardcaron.freeolleefaces.alarm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlarmsJsonTest {
    @Test fun `round-trips a list of alarms`() {
        val alarms = listOf(
            Alarm(id = "a", hour = 7, minute = 5, enabled = true, daysMask = 0x1F, chimeIndex = 1, label = "Work"),
            Alarm(id = "b", hour = 9, minute = 0, enabled = false, daysMask = 0x60, chimeIndex = 0, label = ""),
        )
        val decoded = AlarmsJson.decode(AlarmsJson.encode(alarms))
        assertEquals(alarms, decoded)
    }

    @Test fun `decode of null or garbage yields empty list`() {
        assertTrue(AlarmsJson.decode(null).isEmpty())
        assertTrue(AlarmsJson.decode("not json").isEmpty())
        assertTrue(AlarmsJson.decode("{}").isEmpty())
    }

    @Test fun `decode skips entries with out-of-range fields`() {
        // hour 99 is invalid -> that entry dropped, the valid one kept.
        val json = """[{"id":"x","hour":99,"minute":0,"enabled":true,"daysMask":127,"chime":0,"label":""},
                       {"id":"y","hour":8,"minute":0,"enabled":true,"daysMask":127,"chime":0,"label":""}]"""
        val decoded = AlarmsJson.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("y", decoded[0].id)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.alarm.AlarmsJsonTest"`
Expected: FAIL — `AlarmsJson` unresolved.

- [ ] **Step 3: Implement the codec**

```kotlin
package com.blizzardcaron.freeolleefaces.alarm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure JSON codec for persisting alarms. Decoding NEVER throws — malformed/missing input yields an
 * empty list, and any single entry that violates the [Alarm] invariants is skipped — so corrupt
 * prefs can never crash the UI. Mirrors [com.blizzardcaron.freeolleefaces.timer.TimerSetsJson].
 */
object AlarmsJson {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(alarms: List<Alarm>): String = buildJsonArray {
        for (a in alarms) add(buildJsonObject {
            put("id", JsonPrimitive(a.id))
            put("hour", JsonPrimitive(a.hour))
            put("minute", JsonPrimitive(a.minute))
            put("enabled", JsonPrimitive(a.enabled))
            put("daysMask", JsonPrimitive(a.daysMask))
            put("chime", JsonPrimitive(a.chimeIndex))
            put("label", JsonPrimitive(a.label))
        })
    }.toString()

    fun decode(json: String?): List<Alarm> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            this.json.parseToJsonElement(json).jsonArray.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                runCatching {
                    Alarm(
                        id = o["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        hour = o["hour"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                        minute = o["minute"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                        enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                        daysMask = o["daysMask"]?.jsonPrimitive?.intOrNull ?: Alarm.ALL_DAYS,
                        chimeIndex = o["chime"]?.jsonPrimitive?.intOrNull ?: 0,
                        label = o["label"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                }.getOrNull()   // Alarm init{} threw on a bad range -> skip this entry
            }
        }.getOrDefault(emptyList())
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.alarm.AlarmsJsonTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmsJson.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmsJsonTest.kt
git commit -m "feat: AlarmsJson codec (never throws, skips malformed entries)"
```

---

## Task 3: AlarmsRepository (max 5)

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmsRepository.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmsRepositoryTest.kt`

Mirror `timer/TimerSetsRepository.kt` (no active-id concept — alarms have no "active").

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blizzardcaron.freeolleefaces.alarm

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlarmsRepositoryTest {
    @Test fun `save inserts then replaces by id, capped at MAX_ALARMS`() {
        val repo = AlarmsRepository(MapSettings())
        repeat(6) { i -> repo.save(Alarm(id = "id$i", hour = i.coerceAtMost(23), minute = 0)) }
        // 6th insert is dropped by the cap.
        assertEquals(AlarmsRepository.MAX_ALARMS, repo.getAll().size)
        assertEquals(5, AlarmsRepository.MAX_ALARMS)

        // Replace keeps position and count.
        repo.save(Alarm(id = "id0", hour = 22, minute = 15))
        assertEquals(22, repo.get("id0")!!.hour)
        assertEquals(5, repo.getAll().size)
    }

    @Test fun `delete removes by id`() {
        val repo = AlarmsRepository(MapSettings())
        repo.save(Alarm(id = "a", hour = 7, minute = 0))
        repo.delete("a")
        assertNull(repo.get("a"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.alarm.AlarmsRepositoryTest"`
Expected: FAIL — `AlarmsRepository` unresolved.

- [ ] **Step 3: Implement the repository**

```kotlin
package com.blizzardcaron.freeolleefaces.alarm

import com.russhwolf.settings.Settings

/**
 * Persists up to [MAX_ALARMS] alarms (JSON via [AlarmsJson]) in a dedicated [Settings] store.
 * Thin glue over the codec; mirrors [com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository].
 */
class AlarmsRepository(private val settings: Settings) {

    fun getAll(): List<Alarm> = AlarmsJson.decode(settings.getStringOrNull(KEY_ALARMS))

    fun get(id: String): Alarm? = getAll().firstOrNull { it.id == id }

    /** Insert or replace [alarm] by id. Replace keeps position; insert appends (capped at [MAX_ALARMS]). */
    fun save(alarm: Alarm) {
        val existing = getAll()
        val merged = if (existing.any { it.id == alarm.id }) {
            existing.map { if (it.id == alarm.id) alarm else it }
        } else {
            (existing + alarm).take(MAX_ALARMS)
        }
        settings.putString(KEY_ALARMS, AlarmsJson.encode(merged))
    }

    fun delete(id: String) {
        settings.putString(KEY_ALARMS, AlarmsJson.encode(getAll().filter { it.id != id }))
    }

    companion object {
        const val MAX_ALARMS = 5
        private const val KEY_ALARMS = "alarms"
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.alarm.AlarmsRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmsRepository.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmsRepositoryTest.kt
git commit -m "feat: AlarmsRepository persists up to 5 alarms"
```

---

## Task 4: AlarmSchedule.nextFire (the pure core)

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmSchedule.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmScheduleTest.kt`

Pure `kotlinx-datetime` logic, no Android deps (like `AutoUpdateSchedule`). The date-scan APIs
(`LocalDate.plus(n, DateTimeUnit.DAY)`, `LocalTime`) are already used by `sun/SunCalc.kt`.

- [ ] **Step 1: Write the failing test**

2026-06-10 is a **Wednesday** (anchors the weekday math below).

```kotlin
package com.blizzardcaron.freeolleefaces.alarm

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlarmScheduleTest {

    // Wed 2026-06-10 10:00.
    private val now = LocalDateTime(2026, 6, 10, 10, 0)

    @Test fun `today counts only when the time is strictly in the future`() {
        val later = Alarm(id = "a", hour = 10, minute = 1)
        assertEquals(LocalDateTime(2026, 6, 10, 10, 1), AlarmSchedule.nextFire(listOf(later), now)?.dateTime)

        // 10:00 is not strictly after 10:00 -> rolls to tomorrow.
        val exactlyNow = Alarm(id = "b", hour = 10, minute = 0)
        assertEquals(LocalDateTime(2026, 6, 11, 10, 0), AlarmSchedule.nextFire(listOf(exactlyNow), now)?.dateTime)
    }

    @Test fun `skips days not in the mask`() {
        // Mon-only alarm checked on a Wednesday -> next Monday, 2026-06-15.
        val monday = Alarm(id = "a", hour = 7, minute = 0, daysMask = Alarm.bit(DayOfWeek.MONDAY))
        assertEquals(LocalDateTime(2026, 6, 15, 7, 0), AlarmSchedule.nextFire(listOf(monday), now)?.dateTime)
    }

    @Test fun `wraps a full week when today's only occurrence has passed`() {
        // Wed-only alarm at 09:00, checked Wed 10:00 -> next Wednesday, 2026-06-17.
        val wed = Alarm(id = "a", hour = 9, minute = 0, daysMask = Alarm.bit(DayOfWeek.WEDNESDAY))
        assertEquals(LocalDateTime(2026, 6, 17, 9, 0), AlarmSchedule.nextFire(listOf(wed), now)?.dateTime)
    }

    @Test fun `picks the soonest across alarms and carries that alarm's time and chime`() {
        val late = Alarm(id = "a", hour = 22, minute = 0, chimeIndex = 3)
        val soon = Alarm(id = "b", hour = 11, minute = 30, chimeIndex = 1)
        val next = AlarmSchedule.nextFire(listOf(late, soon), now)!!
        assertEquals(LocalDateTime(2026, 6, 10, 11, 30), next.dateTime)
        assertEquals(11, next.hour)
        assertEquals(30, next.minute)
        assertEquals(1, next.chimeIndex)
    }

    @Test fun `tie goes to the earliest-listed alarm`() {
        val first = Alarm(id = "a", hour = 11, minute = 0, chimeIndex = 2)
        val second = Alarm(id = "b", hour = 11, minute = 0, chimeIndex = 9)
        assertEquals(2, AlarmSchedule.nextFire(listOf(first, second), now)?.chimeIndex)
    }

    @Test fun `disabled and empty-days alarms contribute nothing`() {
        val disabled = Alarm(id = "a", hour = 11, minute = 0, enabled = false)
        val inert = Alarm(id = "b", hour = 11, minute = 0, daysMask = 0)
        assertNull(AlarmSchedule.nextFire(listOf(disabled, inert), now))
        assertNull(AlarmSchedule.nextFire(emptyList(), now))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.alarm.AlarmScheduleTest"`
Expected: FAIL — `AlarmSchedule` unresolved.

- [ ] **Step 3: Implement nextFire**

```kotlin
package com.blizzardcaron.freeolleefaces.alarm

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * Pure next-fire computation over the 5 logical alarms. The watch stores a single alarm with no
 * day-of-week field, so the phone computes which occurrence is next and (in the re-arm engine)
 * keeps the watch's one slot pointed at it. No Android deps — mirrors
 * [com.blizzardcaron.freeolleefaces.auto.AutoUpdateSchedule].
 */
object AlarmSchedule {

    /** The soonest upcoming occurrence across all enabled alarms. */
    data class NextFire(val dateTime: LocalDateTime, val hour: Int, val minute: Int, val chimeIndex: Int)

    /**
     * The minimum next occurrence across [alarms], or null if nothing is due (none enabled, or
     * enabled but with empty day masks — the inert state). Today's HH:MM counts only if strictly
     * after [now]. Ties go to the earliest-listed alarm.
     */
    fun nextFire(alarms: List<Alarm>, now: LocalDateTime): NextFire? =
        alarms.filter { it.enabled }
            .mapNotNull { a -> nextOccurrence(a, now)?.let { NextFire(it, a.hour, a.minute, a.chimeIndex) } }
            .minByOrNull { it.dateTime }

    private fun nextOccurrence(alarm: Alarm, now: LocalDateTime): LocalDateTime? {
        // Scan today..+7 days; the +7 covers "only weekday bit is today's, but the time passed".
        for (offset in 0..7) {
            val date = now.date.plus(offset, DateTimeUnit.DAY)
            if (!alarm.repeatsOn(date.dayOfWeek)) continue
            val candidate = LocalDateTime(date, LocalTime(alarm.hour, alarm.minute))
            if (candidate > now) return candidate
        }
        return null
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.alarm.AlarmScheduleTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmSchedule.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmScheduleTest.kt
git commit -m "feat: AlarmSchedule.nextFire computes the soonest occurrence across alarms"
```

---

## Task 5: AlarmSchedule.packetFor + formatNext

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmSchedule.kt`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmScheduleTest.kt`

`packetFor` is the single place that maps "next fire or nothing" to the exact `0x25` frame —
**armed** (`enabled=true, playNow=false`, verified to ring for real) or **disarm**
(`enabled=false`, verified silent even over an armed alarm). `formatNext` renders the UI summary.
This also covers the spec's "OlleeProtocolTest disarm/armed frame" ask: the assertions below pin
the byte layouts via `buildAlarmPacket` equivalence plus direct payload-byte checks.

- [ ] **Step 1: Write the failing tests** (append to `AlarmScheduleTest`)

```kotlin
    // Frame layout: [0]=00 [1]=LEN [2..3]=AA55 [4..5]=CRC [6]=CMD 02 [7]=TARGET 25 [8..]=payload.
    // payload[0]=enable, [3]=hour, [4]=minute, [6]=chime, [8]=playNow.

    @Test fun `packetFor a next fire arms a real alarm frame`() {
        val next = AlarmSchedule.NextFire(LocalDateTime(2026, 6, 10, 7, 30), hour = 7, minute = 30, chimeIndex = 1)
        val packet = AlarmSchedule.packetFor(next)
        assertContentEquals(
            OlleeProtocol.buildAlarmPacket(hour = 7, minute = 30, chimeIndex = 1, playNow = false, enabled = true),
            packet,
        )
        assertEquals(0x01, packet[8].toInt())            // enabled
        assertEquals(7, packet[11].toInt())              // hour
        assertEquals(30, packet[12].toInt())             // minute
        assertEquals(1, packet[14].toInt())              // chime
        assertEquals(0x00, packet[16].toInt())           // playNow=false: arm, don't preview
    }

    @Test fun `packetFor null disarms the watch`() {
        val packet = AlarmSchedule.packetFor(null)
        assertContentEquals(
            OlleeProtocol.buildAlarmPacket(hour = 0, minute = 0, chimeIndex = 0, playNow = false, enabled = false),
            packet,
        )
        assertEquals(0x00, packet[8].toInt())            // enabled=false: verified silent on-device
    }

    @Test fun `formatNext renders day, 12-hour time, and chime name`() {
        val breeze = AlarmSchedule.NextFire(LocalDateTime(2026, 6, 16, 7, 0), hour = 7, minute = 0, chimeIndex = 1)
        assertEquals("Next: Tue 7:00 AM · Breeze", AlarmSchedule.formatNext(breeze))

        val midnight = AlarmSchedule.NextFire(LocalDateTime(2026, 6, 11, 0, 5), hour = 0, minute = 5, chimeIndex = 0)
        assertEquals("Next: Thu 12:05 AM · Classic", AlarmSchedule.formatNext(midnight))

        val noonHighChime = AlarmSchedule.NextFire(LocalDateTime(2026, 6, 13, 12, 0), hour = 12, minute = 0, chimeIndex = 7)
        assertEquals("Next: Sat 12:00 PM · Chime 8", AlarmSchedule.formatNext(noonHighChime))

        assertEquals("No alarms", AlarmSchedule.formatNext(null))
    }
```

New test imports: `com.blizzardcaron.freeolleefaces.ble.OlleeProtocol`, `kotlin.test.assertContentEquals`.

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.alarm.AlarmScheduleTest"`
Expected: FAIL — `packetFor`/`formatNext` unresolved.

- [ ] **Step 3: Implement** (append inside `object AlarmSchedule`; import `com.blizzardcaron.freeolleefaces.ble.OlleeProtocol`)

```kotlin
    /**
     * The exact `0x25` frame for [next]: an armed real alarm (`enabled=true, playNow=false` —
     * verified to ring ~35 s and self-stop), or the disarm frame when nothing is due
     * (`enabled=false` — verified silent on-device, even over an already-armed alarm).
     */
    fun packetFor(next: NextFire?): ByteArray =
        if (next != null) {
            OlleeProtocol.buildAlarmPacket(
                hour = next.hour, minute = next.minute, chimeIndex = next.chimeIndex,
                playNow = false, enabled = true,
            )
        } else {
            OlleeProtocol.buildAlarmPacket(hour = 0, minute = 0, chimeIndex = 0, playNow = false, enabled = false)
        }

    /** UI summary, e.g. "Next: Tue 7:00 AM · Breeze" — or "No alarms". */
    fun formatNext(next: NextFire?): String {
        if (next == null) return "No alarms"
        val day = next.dateTime.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        val h12 = when { next.hour == 0 -> 12; next.hour > 12 -> next.hour - 12; else -> next.hour }
        val amPm = if (next.hour < 12) "AM" else "PM"
        val mm = next.minute.toString().padStart(2, '0')
        return "Next: $day $h12:$mm $amPm · ${chimeName(next.chimeIndex)}"
    }

    /** Watch chime tone name; only the first few of the 14 are named in the protocol doc. */
    fun chimeName(index: Int): String = CHIME_NAMES.getOrNull(index) ?: "Chime ${index + 1}"

    private val CHIME_NAMES = listOf("Classic", "Breeze", "Westminster")
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.alarm.AlarmScheduleTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmSchedule.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/alarm/AlarmScheduleTest.kt
git commit -m "feat: AlarmSchedule maps next fire to armed/disarm 0x25 frames + UI summary"
```

---

## Task 6: Re-arm engine — AlarmScheduler interface + Android glue

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AlarmScheduler.kt`
- Create: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AndroidAlarmScheduler.kt`
- Create: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AlarmRearm.kt`
- Create: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AlarmRearmReceiver.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/AndroidSettings.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/BootReceiver.kt`
- Modify: `app/src/androidMain/AndroidManifest.xml`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/fakes/Fakes.kt`

This task is Android-host glue (AlarmManager + BLE) with **no JVM-testable logic** — the pure parts
were tested in Tasks 4–5, and the fake added here gets exercised by Task 7's ViewModel tests.
Verification is "everything compiles, full suite stays green" now, and on-device in Task 9.

- [ ] **Step 1: commonMain interface** (`auto/AlarmScheduler.kt`, beside `Scheduler.kt`)

```kotlin
package com.blizzardcaron.freeolleefaces.auto

/**
 * Lets shared code request an alarm re-arm pass — recompute the next fire, push the armed/disarm
 * frame to the watch, and (re)schedule the post-fire trigger — without knowing about AlarmManager.
 */
interface AlarmScheduler {
    fun rearm()
}
```

- [ ] **Step 2: FakeAlarmScheduler** (append to `fakes/Fakes.kt`, mirroring `FakeScheduler`)

```kotlin
// ---------------------------------------------------------------------------
// FakeAlarmScheduler
// ---------------------------------------------------------------------------

/** Records "alarmScheduler.rearm" into the shared [callLog]. */
class FakeAlarmScheduler(
    private val callLog: MutableList<String> = mutableListOf(),
) : AlarmScheduler {

    override fun rearm() {
        callLog += "alarmScheduler.rearm"
    }
}
```

(Import `com.blizzardcaron.freeolleefaces.auto.AlarmScheduler`.)

- [ ] **Step 3: alarmSettings store** (append to `prefs/AndroidSettings.kt`)

```kotlin
/**
 * [Settings] backed by the dedicated alarms SharedPreferences file. The file name must stay
 * `"alarms"` forever — existing users' saved alarms live there.
 */
fun alarmSettings(context: Context): Settings =
    SharedPreferencesSettings(context.applicationContext.getSharedPreferences("alarms", Context.MODE_PRIVATE))
```

- [ ] **Step 4: AlarmRearm — the one re-arm operation** (`auto/AlarmRearm.kt`)

```kotlin
package com.blizzardcaron.freeolleefaces.auto

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blizzardcaron.freeolleefaces.alarm.AlarmSchedule
import com.blizzardcaron.freeolleefaces.alarm.AlarmsRepository
import com.blizzardcaron.freeolleefaces.ble.AndroidBleClient
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.prefs.alarmSettings
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * The single alarm re-arm pass: compute the next fire across the saved alarms, push the matching
 * armed/disarm `0x25` frame to the watch, and schedule (or cancel) the exact AlarmManager trigger
 * that re-runs this pass ~1 minute after the alarm fires — which is what advances repeat-day
 * alarms to their next occurrence (the watch itself has no day-of-week field).
 *
 * The watch cannot tell the phone an alarm fired, so the trigger time is our own computed
 * `nextFire + 60 s`. A failed BLE push is tolerated: the watch keeps its last good alarm and the
 * next trigger / app open / boot retries — eventually consistent.
 *
 * The BLE push is **debounced** ([PUSH_DEBOUNCE_MS]): inline alarm cards call rearm on every edit
 * (each H/M digit, each day-chip tap), and back-to-back GATT connects would collide. Each call
 * bumps a generation counter; only the latest survives the delay and pushes, with the final state.
 * The AlarmManager trigger is NOT debounced — it is idempotent and must never be dropped.
 */
object AlarmRearm {

    const val TAG = "ALARM_REARM"
    private const val REQUEST_CODE = 4025   // one slot: each schedule replaces the last
    private const val PUSH_DEBOUNCE_MS = 750L
    private val generation = AtomicInteger()

    /** Runs the full pass. [onComplete] fires after the push attempt or when superseded. */
    fun rearm(context: Context, onComplete: () -> Unit = {}) {
        val ctx = context.applicationContext

        // Schedule the trigger first — it must survive even if the push below fails.
        val am = ctx.getSystemService(AlarmManager::class.java)
        val trigger = PendingIntent.getBroadcast(
            ctx, REQUEST_CODE, Intent(ctx, AlarmRearmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val zone = TimeZone.currentSystemDefault()
        val next = AlarmSchedule.nextFire(
            AlarmsRepository(alarmSettings(ctx)).getAll(),
            Clock.System.now().toLocalDateTime(zone),
        )
        if (next != null) {
            val atMs = next.dateTime.toInstant(zone).toEpochMilliseconds() + 60_000L
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, trigger)
            Log.i(TAG, "next fire ${next.dateTime}; trigger set for fire+60s")
        } else {
            am.cancel(trigger)
            Log.i(TAG, "no alarms due; trigger cancelled, will push disarm")
        }

        val address = Prefs(appSettings(ctx)).watchAddress
        if (address == null) {
            Log.w(TAG, "no watch selected — skipping push")
            onComplete()
            return
        }
        val myGen = generation.incrementAndGet()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(PUSH_DEBOUNCE_MS)
                if (generation.get() != myGen) return@launch   // superseded by a newer rearm
                // Recompute at push time so a burst of edits sends the final state.
                val latest = AlarmSchedule.nextFire(
                    AlarmsRepository(alarmSettings(ctx)).getAll(),
                    Clock.System.now().toLocalDateTime(zone),
                )
                val result = AndroidBleClient(ctx).sendPacket(address, AlarmSchedule.packetFor(latest))
                Log.i(
                    TAG,
                    if (result.isSuccess) "push OK (${if (latest != null) "armed ${latest.dateTime}" else "disarm"})"
                    else "push FAIL ${result.exceptionOrNull()?.message} (will retry on next trigger/open/boot)",
                )
            } finally {
                onComplete()
            }
        }
    }
}
```

- [ ] **Step 5: AndroidAlarmScheduler** (`auto/AndroidAlarmScheduler.kt`, mirroring `AndroidScheduler`)

```kotlin
package com.blizzardcaron.freeolleefaces.auto

import android.content.Context

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    override fun rearm() = AlarmRearm.rearm(context)
}
```

- [ ] **Step 6: AlarmRearmReceiver** (`auto/AlarmRearmReceiver.kt`)

```kotlin
package com.blizzardcaron.freeolleefaces.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires ~1 minute after each computed watch-alarm fire (exact AlarmManager trigger set by
 * [AlarmRearm]) and re-runs the re-arm pass, advancing the watch's single alarm to the next
 * occurrence. [goAsync] keeps the process alive for the BLE push (same pattern as
 * DevToolsReceiver).
 */
class AlarmRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        AlarmRearm.rearm(context) { pending.finish() }
    }
}
```

- [ ] **Step 7: BootReceiver re-arms alarms too**

```kotlin
/** Re-arms the auto-update chain and the watch alarm after a device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AutoUpdateScheduler.reschedule(context)
            val pending = goAsync()
            AlarmRearm.rearm(context) { pending.finish() }
        }
    }
}
```

- [ ] **Step 8: Manifest** — add the permission (with the other `uses-permission` lines) and the
receiver (after `.auto.BootReceiver`; no intent-filter — the PendingIntent is explicit):

```xml
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```

```xml
<receiver
    android:name=".auto.AlarmRearmReceiver"
    android:exported="false" />
```

(`USE_EXACT_ALARM` is auto-granted for alarm-clock apps — no runtime prompt; we now are one.)

- [ ] **Step 9: Verify everything compiles and the suite stays green**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all existing tests pass (androidMain compiles via the shared compile task).

- [ ] **Step 10: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AlarmScheduler.kt \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AndroidAlarmScheduler.kt \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AlarmRearm.kt \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AlarmRearmReceiver.kt \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/BootReceiver.kt \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/AndroidSettings.kt \
        app/src/androidMain/AndroidManifest.xml \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/fakes/Fakes.kt
git commit -m "feat: alarm re-arm engine (AlarmManager exact trigger + BLE push + boot hook)"
```

---

## Task 7: AppViewModel alarm state + CRUD

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/AppViewModelTest.kt`

The ctor gains `alarmRepo` + `alarmScheduler` (after `scheduler`, before `versionLabel`).
**Every existing `AppViewModel(` construction must add the two params** — 6 sites in
`AppViewModelTest.kt` (lines ~98/167/215/263/310/347) plus the one in `MainActivity.kt:78`.
Test sites use `AlarmsRepository(MapSettings())` and `FakeAlarmScheduler(callLog)` (or a fresh
`FakeAlarmScheduler()` where the test has no shared log).

- [ ] **Step 1: Write the failing tests** (append to `AppViewModelTest`; reuse the file's existing
imports/patterns — add `com.blizzardcaron.freeolleefaces.alarm.Alarm`,
`com.blizzardcaron.freeolleefaces.alarm.AlarmsRepository`, `fakes.FakeAlarmScheduler`)

```kotlin
    @Test
    fun `alarm CRUD persists, updates state, and re-arms on every change`() {
        val callLog = mutableListOf<String>()
        val settings = MapSettings()
        val vm = AppViewModel(
            prefs = Prefs(MapSettings()),
            ble = FakeBleClient(callLog),
            steps = FakeStepsProvider(),
            location = FakeLocationProvider(),
            notificationAccess = FakeNotificationAccessChecker(),
            timerRepo = TimerSetsRepository(MapSettings()),
            scheduler = FakeScheduler(callLog),
            alarmRepo = AlarmsRepository(settings),
            alarmScheduler = FakeAlarmScheduler(callLog),
        )

        vm.addAlarm()
        assertEquals(1, vm.alarms.size)
        assertEquals(1, callLog.count { it == "alarmScheduler.rearm" })

        val alarm = vm.alarms[0]
        vm.saveAlarm(alarm.copy(hour = 6, minute = 45))
        assertEquals(6, vm.alarms[0].hour)

        // Label-only change persists but does NOT re-arm (the label never reaches the watch).
        vm.saveAlarm(vm.alarms[0].copy(label = "Work"))
        assertEquals("Work", vm.alarms[0].label)
        assertEquals(2, callLog.count { it == "alarmScheduler.rearm" })

        vm.toggleAlarm(alarm.id, enabled = false)
        assertFalse(vm.alarms[0].enabled)

        vm.deleteAlarm(alarm.id)
        assertTrue(vm.alarms.isEmpty())
        assertNull(AlarmsRepository(settings).get(alarm.id))   // really deleted from the store
        assertEquals(4, callLog.count { it == "alarmScheduler.rearm" })
    }

    @Test
    fun `addAlarm caps at MAX_ALARMS`() {
        val vm = AppViewModel(
            prefs = Prefs(MapSettings()),
            ble = FakeBleClient(),
            steps = FakeStepsProvider(),
            location = FakeLocationProvider(),
            notificationAccess = FakeNotificationAccessChecker(),
            timerRepo = TimerSetsRepository(MapSettings()),
            scheduler = FakeScheduler(),
            alarmRepo = AlarmsRepository(MapSettings()),
            alarmScheduler = FakeAlarmScheduler(),
        )
        repeat(AlarmsRepository.MAX_ALARMS + 1) { vm.addAlarm() }
        assertEquals(AlarmsRepository.MAX_ALARMS, vm.alarms.size)
    }
```

- [ ] **Step 2: Update the 6 existing constructions, run, verify the new tests fail**

Add `alarmRepo = AlarmsRepository(MapSettings()), alarmScheduler = FakeAlarmScheduler(callLog)`
(or a fresh `FakeAlarmScheduler()` where no log is shared) to each existing site — they won't
compile otherwise once the ctor changes; do it now so Step 3's run isolates the real failures.

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.AppViewModelTest"`
Expected: FAIL — ctor params / `alarms` / `addAlarm` unresolved.

- [ ] **Step 3: Implement in AppViewModel**

Ctor (insert after `scheduler`):

```kotlin
    private val alarmRepo: AlarmsRepository,
    private val alarmScheduler: AlarmScheduler,
```

State + summary (beside the timer state; imports: `alarm.Alarm`, `alarm.AlarmSchedule`,
`alarm.AlarmsRepository`, `auto.AlarmScheduler`, `kotlinx.datetime.Clock`,
`kotlinx.datetime.TimeZone`, `kotlinx.datetime.toLocalDateTime`):

```kotlin
    var alarms by mutableStateOf(alarmRepo.getAll())
        private set

    /** e.g. "Next: Tue 7:00 AM · Breeze" — or "No alarms". */
    val nextAlarmSummary: String
        get() = AlarmSchedule.formatNext(
            AlarmSchedule.nextFire(alarms, Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())),
        )
```

Rename the file-private `randomTimerSetId()` to `randomId()` (it now serves alarms too; 2 existing
call sites in this file). CRUD — each persists, refreshes state, then re-arms:

```kotlin
    fun refreshAlarms() { alarms = alarmRepo.getAll() }

    fun addAlarm() {
        if (alarms.size >= AlarmsRepository.MAX_ALARMS) return
        alarmRepo.save(Alarm(id = randomId(), hour = 7, minute = 0))
        alarms = alarmRepo.getAll()
        alarmScheduler.rearm()
    }

    fun saveAlarm(alarm: Alarm) {
        val before = alarmRepo.get(alarm.id)
        alarmRepo.save(alarm)
        alarms = alarmRepo.getAll()
        // The label is phone-side only — a label-only edit (every keystroke lands here) must not
        // re-push the watch. Anything schedule-affecting re-arms.
        if (before == null || before.copy(label = alarm.label) != alarm) alarmScheduler.rearm()
    }

    fun toggleAlarm(id: String, enabled: Boolean) {
        alarmRepo.get(id)?.let { saveAlarm(it.copy(enabled = enabled)) }
    }

    fun deleteAlarm(id: String) {
        alarmRepo.delete(id)
        alarms = alarmRepo.getAll()
        alarmScheduler.rearm()
    }
```

App-open is a re-arm trigger (spec §3) — extend `onStart()`:

```kotlin
    fun onStart() { scheduler.reschedule(); alarmScheduler.rearm() }
```

- [ ] **Step 4: Wire MainActivity** (`MainActivity.kt:78` construction; imports:
`alarm.AlarmsRepository`, `auto.AndroidAlarmScheduler`, `prefs.alarmSettings`)

```kotlin
            alarmRepo = AlarmsRepository(alarmSettings(context)),
            alarmScheduler = AndroidAlarmScheduler(context),
```

- [ ] **Step 5: Run the full suite to verify it passes**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — new tests green, no existing test regressions (the only behavior change to
existing flows is `onStart` also calling `rearm()`, which no existing test asserts against).

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/AppViewModelTest.kt
git commit -m "feat: alarm CRUD in AppViewModel, every change re-arms the watch"
```

---

## Task 8: Alarms screen + navigation

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AlarmsScreen.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Screen.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt`

Compose UI has no unit tests in this project — verification is compile + full suite green here,
visual/manual in Task 9. Styling mirrors `TimerSetsScreen` exactly: same header row + "Done", same
`Card`/`Button`/`NumberField` idioms (`NumberField` is `internal` in this `ui` package, from
`TimerWidgets.kt`).

- [ ] **Step 1: `Screen.kt`** — add to the sealed interface:

```kotlin
    data object Alarms : Screen
```

- [ ] **Step 2: `Callbacks.kt`** — add to `HomeCallbacks` (after `onOpenTimerSets`):

```kotlin
    val onOpenAlarms: () -> Unit,
```

- [ ] **Step 3: `HomeScreen.kt`** — add the button beside "Timers" (in the header `Row`):

```kotlin
                TextButton(onClick = callbacks.onOpenAlarms) { Text("Alarms") }
```

- [ ] **Step 4: `AlarmsScreen.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.alarm.Alarm
import com.blizzardcaron.freeolleefaces.alarm.AlarmSchedule
import com.blizzardcaron.freeolleefaces.alarm.AlarmsRepository
import kotlinx.datetime.DayOfWeek

@Composable
fun AlarmsScreen(
    alarms: List<Alarm>,
    nextSummary: String,
    onAdd: () -> Unit,
    onSave: (Alarm) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Alarms", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Done") }
        }
        HorizontalDivider()

        Text(nextSummary, style = MaterialTheme.typography.titleMedium)

        val atMax = alarms.size >= AlarmsRepository.MAX_ALARMS
        Button(onClick = onAdd, enabled = !atMax, modifier = Modifier.fillMaxWidth()) {
            Text(if (atMax) "Max ${AlarmsRepository.MAX_ALARMS} alarms" else "Add alarm")
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (alarms.isEmpty()) {
                Text("No alarms yet. Tap \"Add alarm\" to create one.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            for (alarm in alarms) {
                AlarmCard(
                    alarm = alarm,
                    onSave = onSave,
                    onToggle = { onToggle(alarm.id, it) },
                    onDelete = { onDelete(alarm.id) },
                )
            }
        }
    }
}

@Composable
private fun AlarmCard(
    alarm: Alarm,
    onSave: (Alarm) -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NumberField("H", alarm.hour) { onSave(alarm.copy(hour = it.coerceIn(0, 23))) }
                    NumberField("M", alarm.minute) { onSave(alarm.copy(minute = it.coerceIn(0, 59))) }
                }
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            }

            DayChips(mask = alarm.daysMask) { onSave(alarm.copy(daysMask = it)) }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ChimePicker(index = alarm.chimeIndex) { onSave(alarm.copy(chimeIndex = it)) }
                TextButton(onClick = onDelete) { Text("Delete") }
            }

            OutlinedTextField(
                value = alarm.label,
                onValueChange = { onSave(alarm.copy(label = it.take(24))) },
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One toggle chip per weekday, Mon-first: M T W T F S S. */
@Composable
private fun DayChips(mask: Int, onChange: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (day in DayOfWeek.entries) {
            val bit = Alarm.bit(day)
            val selected = mask and bit != 0
            FilterChip(
                selected = selected,
                onClick = { onChange(if (selected) mask and bit.inv() else mask or bit) },
                label = { Text(day.name.take(1)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ChimePicker(index: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("♪ ${AlarmSchedule.chimeName(index)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            repeat(14) { i ->   // the watch's 14 tones, indices 0x00..0x0D
                DropdownMenuItem(
                    text = { Text(AlarmSchedule.chimeName(i)) },
                    onClick = { onChange(i); open = false },
                )
            }
        }
    }
}
```

Notes for the implementer:
- `NumberField` shows `0` as blank (existing timer behavior) — accepted for hour 0 / minute 0,
  consistent with the timer screens.
- `coerceIn` keeps a momentarily out-of-range second digit (e.g. typing "9" then "99" for hours)
  from hitting the `Alarm` `init {}` `require`.

- [ ] **Step 5: MainActivity** — wire the callback and render the screen.

In the `HomeCallbacks(...)` construction (beside `onOpenTimerSets` at `MainActivity.kt:237`):

```kotlin
        onOpenAlarms = { viewModel.refreshAlarms(); viewModel.navigateTo(Screen.Alarms) },
```

In the screen `when` (beside `Screen.TimerSets ->` at `MainActivity.kt:277`):

```kotlin
        Screen.Alarms -> AlarmsScreen(
            alarms = viewModel.alarms,
            nextSummary = viewModel.nextAlarmSummary,
            onAdd = { viewModel.addAlarm() },
            onSave = { viewModel.saveAlarm(it) },
            onToggle = { id, enabled -> viewModel.toggleAlarm(id, enabled) },
            onDelete = { viewModel.deleteAlarm(it) },
            onBack = { viewModel.navigateTo(Screen.Home) },
        )
```

- [ ] **Step 6: Verify compile + suite green**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass. (The exhaustive `when` over `Screen` forces the
`Screen.Alarms` branch to exist — a missed branch is a compile error, not a runtime surprise.)

- [ ] **Step 7: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/AlarmsScreen.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Screen.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "feat: Alarms screen with inline cards, day chips, and chime picker"
```

---

## Task 9: On-device verification

**No code changes** — proves the end-to-end loop on real hardware (phone over adb Wi-Fi, watch
`00:80:E1:26:DC:86`). Run from the main session (needs the phone + watch), not a subagent.

Ground rules (from prior sessions):
- adb lives at `~/Android/Sdk/platform-tools/adb`; the phone was last at `192.168.4.87:46045` —
  reconnect with `adb connect <ip:port>` if detached (ask the user if unreachable).
- `./gradlew :app:installDebug` fails (adb path); use
  `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- **Drive taps via uiautomator** (`adb shell uiautomator dump /sdcard/ui.xml`, parse
  `bounds="[x1,y1][x2,y2]"`, tap centers) — never guess coordinates from scaled screenshots.

- [ ] **Step 1: Build + install**

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: UI walk** — launch the app, tap **Alarms** on Home, then verify via uiautomator
dumps: summary shows "No alarms"; **Add alarm** creates a card (Switch on, all 7 day chips
selected, chime "Classic"); after 5 adds the button reads "Max 5 alarms" and is disabled; delete
works; H/M edits stick after leaving and re-entering the screen (persistence).

- [ ] **Step 3: Live fire + auto re-arm** — the core loop:

1. `adb shell date` for current phone time. Set a single alarm **2–3 min ahead**, all days,
   chime Breeze; clear other alarms.
2. Confirm the exact trigger armed: `adb shell dumpsys alarm | grep -A4 freeollee` shows an
   RTC_WAKEUP entry ~fire+60 s.
3. Watch `adb logcat -s ALARM_REARM` — expect "next fire …; trigger set" then "push OK".
4. **At the set time the watch must ring** (~35 s, self-stops). User confirms audibly.
5. ~1 min after the fire, logcat shows the receiver's re-arm pass: next fire = same time
   **tomorrow**, "push OK" again. `dumpsys alarm` shows the new trigger.

- [ ] **Step 4: Disarm path** — toggle the alarm **off** (or set its time 2 min ahead first and
disable before it fires): logcat shows "no alarms due; trigger cancelled", push OK; the watch
**stays silent** at the set time (the sticky face display may still show the old time — known
watch quirk, ring behavior is what matters).

- [ ] **Step 5: Record results** — append a short "Verified on-device <date>" note to this plan
file (what rang, what stayed silent, re-arm observed) and commit it:

```bash
git add docs/superpowers/plans/2026-06-10-alarm-scheduler.md
git commit -m "docs: on-device verification results for the alarm scheduler"
```

---

## Task 10: Cross-repo protocol doc update (ollee-graphene)

**Files:**
- Modify: `~/github/ollee-graphene/docs/reference/ollee-ble-protocol.md` (separate repo, separate commit)

Update the `0x25` section with the 2026-06-10 on-device findings (currently byte 0 is marked
"persist untested"):

- [ ] **Step 1: Edit the doc**
  - **Byte 0 (enable) is a verified enable/disable** [✅ on hardware 2026-06-10]: `enabled=false`
    reliably silences the alarm — even pushed over an already-armed alarm at the same `HH:MM`.
  - **`PL=00, enable=01` arms a real firing alarm**: it rings at `HH:MM` for ~35 s and self-stops
    (no dismiss interaction). Previously only the `PL=01` transient preview had been confirmed.
  - The alarm-face **time display is sticky** after a disable (kept showing the last enabled
    time) — UI quirk only; byte 0 governs the ring.
  - Note the day-of-week implication where the section discusses the official app: the record has
    no day field, so repeat days are computed phone-side (as FreeOllee-Faces now also does).

- [ ] **Step 2: Commit (in ollee-graphene)**

```bash
cd ~/github/ollee-graphene
git add docs/reference/ollee-ble-protocol.md
git commit -m "docs: 0x25 byte-0 enable verified on hardware; PL=00 arms a real ~35s alarm"
```

(Local commit only — no pushes during workday hours per standing instruction.)

---

## Final verification

- [ ] `./gradlew :app:testDebugUnitTest` — full suite green.
- [ ] `git log --oneline timer-enhancements..alarm-scheduler` — one commit per task, plan + spec at the base.
- [ ] All plan checkboxes above ticked (including Task 9's on-device evidence note).
- [ ] Then: superpowers:finishing-a-development-branch — merge `alarm-scheduler` into
      `timer-enhancements`' release line; both features ship as **one release** (single `VERSION`
      bump, e.g. 0.12.0 → 0.13.0, when the combined branch merges to `main`). **No push before
      5 PM MT on workdays.**
