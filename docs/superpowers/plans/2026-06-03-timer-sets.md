# Timer Sets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a phone-side library of up to 10 named "Timer Sets" (each = the watch's 10 timer slots); the user edits sets on the phone and pushes the active set to the watch with one `02 26` BLE write.

**Architecture:** A pure protocol encoder (`buildTimerPacket`) reuses the v0.8.0 `buildRawPacket` framing and the chunked `sendPacket`/`deliver` write path. An immutable data model (`TimerSet`/`TimerSlot`) is persisted as JSON (`TimerSetsJson` codec + thin `TimerSetsRepository` over SharedPreferences). Pure editor transforms (`TimerSetEditing`) power a list screen and an editor screen wired into the existing state-based navigation. Timers are static config — pushing is manual, not on the auto-update schedule.

**Tech Stack:** Kotlin, Android `BluetoothGatt` (existing `OlleeBleClient`), Jetpack Compose Material3, Android built-in `org.json`, JUnit4. Build: `./gradlew :app:testDebugUnitTest` / `:app:assembleDebug`. Toolchain (Raspberry Pi 500, ARM64): JDK 17, `ANDROID_HOME=/home/kbcaron/Android/Sdk`.

**Spec:** `docs/superpowers/specs/2026-06-03-timer-sets-design.md`

**Constraint:** No FreeOllee-Faces code or test may import or path-reference the separate `ollee-graphene` repo.

---

## File Structure

| File | Responsibility | Task |
|------|----------------|------|
| `ble/OlleeProtocol.kt` (modify) | `TARGET_TIMERS` + `buildTimerPacket` | 1 |
| `timer/TimerModels.kt` (create) | `TimerSlot`, `TimerSet` data model | 2 |
| `timer/TimerSetEditing.kt` (create) | pure fill-down/duplicate + H:M:S helpers | 3 |
| `timer/TimerSetsJson.kt` (create) | pure JSON codec (never throws) | 4 |
| `timer/TimerSetsRepository.kt` (create) | SharedPreferences glue (sets + active id) | 5 |
| `ui/TimerSetsScreen.kt` (create) | list screen (Compose) | 6 |
| `ui/TimerSetEditScreen.kt` (create) | editor screen (Compose) | 7 |
| `ui/Screen.kt`, `ui/HomeScreen.kt`, `MainActivity.kt` (modify) | nav routes, Home entry, push wiring | 8 |
| on-device validation | header gate + real `02 26` write + face-enabled | 9 |

Test files: `ble/OlleeProtocolTest.kt` (modify), `timer/TimerModelsTest.kt`, `timer/TimerSetEditingTest.kt`, `timer/TimerSetsJsonTest.kt` (create).

---

## Task 1: `buildTimerPacket` (protocol encoder)

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/ble/OlleeProtocolTest.kt`

- [ ] **Step 1: Write the failing tests** (append inside the existing `OlleeProtocolTest` class, before the closing brace; the class already has a `private fun hex(...)` helper)

```kotlin
    // --- Timer slots (0x26) ---

    @Test
    fun `buildTimerPacket with all-zero durations equals a zero-header raw 0x26 packet`() {
        val packet = OlleeProtocol.buildTimerPacket(List(10) { 0 })
        // 4-byte zero header + 10 * 4-byte zero words = 44 zero payload bytes.
        val expected = OlleeProtocol.buildRawPacket(OlleeProtocol.TARGET_TIMERS, ByteArray(44))
        assertArrayEquals(expected, packet)
    }

    @Test
    fun `buildTimerPacket encodes each slot as a little-endian uint32 of seconds`() {
        // slot 1 = 83 s (the captured 00:01:23); rest blank.
        val packet = OlleeProtocol.buildTimerPacket(listOf(83, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        // Layout: [0..5] frame header, [6..7] = 02 26, [8..11] = 4-byte slot header,
        // [12..15] = slot-1 little-endian uint32.
        assertEquals(0x02.toByte(), packet[6])
        assertEquals(0x26.toByte(), packet[7])
        assertEquals(0x00.toByte(), packet[8])  // header byte 0
        assertEquals(0x53.toByte(), packet[12]) // 83 low byte
        assertEquals(0x00.toByte(), packet[13])
        assertEquals(0x00.toByte(), packet[14])
        assertEquals(0x00.toByte(), packet[15])
    }

    @Test
    fun `buildTimerPacket round-trips through parseFrame to target 0x26 with valid CRC`() {
        val packet = OlleeProtocol.buildTimerPacket(listOf(83, 100, 100, 100, 100, 100, 0, 600, 900, 1800))
        val f = OlleeProtocol.parseFrame(packet)!!
        assertEquals(0x26, f.target)
        assertTrue(f.crcOk)
        // payload = 4-byte header + 10 LE uint32; decode slot 8 (index 7) = 600.
        val slot8 = (f.payload[4 + 7 * 4].toInt() and 0xFF) or
            ((f.payload[5 + 7 * 4].toInt() and 0xFF) shl 8)
        assertEquals(600, slot8)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildTimerPacket rejects a list that is not exactly 10 slots`() {
        OlleeProtocol.buildTimerPacket(listOf(1, 2, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildTimerPacket rejects an out-of-range duration`() {
        OlleeProtocol.buildTimerPacket(listOf(360_000, 0, 0, 0, 0, 0, 0, 0, 0, 0))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd /home/kbcaron/github/FreeOllee-Faces && ANDROID_HOME=/home/kbcaron/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "*OlleeProtocolTest*"`
Expected: FAIL — `buildTimerPacket` / `TARGET_TIMERS` unresolved (compile error).

- [ ] **Step 3: Implement `TARGET_TIMERS` and `buildTimerPacket`** in `OlleeProtocol.kt`. Add the constant beside the other `TARGET_*` constants (after `TARGET_WEEKDAYS`):

```kotlin
    /** Timer-face slots (10 countdown durations) — write target. Ack at 0x46. */
    const val TARGET_TIMERS = 0x26
```

Add the function immediately after `buildWeekdayPacket`:

```kotlin
    /**
     * Builds the Timer-slots write (0x26). [durationsSeconds] must be exactly 10 entries, each a
     * countdown length in seconds (0 = blank slot). Emits a 4-byte zero header followed by ten
     * little-endian uint32 durations, then delegates to [buildRawPacket]. The header is a transient
     * field the official app fills with the last-edited timer's H:M:S; the watch stores the ten
     * words regardless (validated on-device). Per-slot labels are phone-side only and never sent.
     */
    fun buildTimerPacket(durationsSeconds: List<Int>): ByteArray {
        require(durationsSeconds.size == 10) {
            "timer table needs exactly 10 slots (got ${durationsSeconds.size})"
        }
        require(durationsSeconds.all { it in 0..359_999 }) {
            "each duration must be 0..359999 s (got $durationsSeconds)"
        }
        val payload = ByteArray(4) + durationsSeconds.flatMap { s ->
            listOf(s and 0xFF, (s shr 8) and 0xFF, (s shr 16) and 0xFF, (s shr 24) and 0xFF)
        }.map { it.toByte() }.toByteArray()
        return buildRawPacket(TARGET_TIMERS, payload)
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /home/kbcaron/github/FreeOllee-Faces && ANDROID_HOME=/home/kbcaron/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "*OlleeProtocolTest*"`
Expected: PASS (all OlleeProtocolTest cases).

- [ ] **Step 5: Commit**

```bash
cd /home/kbcaron/github/FreeOllee-Faces
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/ble/OlleeProtocolTest.kt
git commit -m "feat(ble): buildTimerPacket — 10 LE-uint32 seconds into a 02 26 frame"
```

---

## Task 2: `TimerSlot` / `TimerSet` data model

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/timer/TimerModels.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/timer/TimerModelsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blizzardcaron.freeolleefaces.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerModelsTest {

    @Test
    fun `blank creates exactly 10 empty slots`() {
        val set = TimerSet.blank("id1", "Morning")
        assertEquals(10, set.slots.size)
        assertEquals(List(10) { 0 }, set.durations())
        assertEquals("", set.slots[0].label)
    }

    @Test
    fun `durations maps slot order to seconds`() {
        val slots = (1..10).map { TimerSlot("L$it", it * 10) }
        val set = TimerSet("id", "name", slots)
        assertEquals((1..10).map { it * 10 }, set.durations())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `constructing a set with the wrong slot count throws`() {
        TimerSet("id", "name", listOf(TimerSlot()))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /home/kbcaron/github/FreeOllee-Faces && ANDROID_HOME=/home/kbcaron/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "*TimerModelsTest*"`
Expected: FAIL — `TimerSet` / `TimerSlot` unresolved.

- [ ] **Step 3: Create `TimerModels.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.timer

/**
 * One of the watch's 10 timer slots. [label] is phone-side only (never sent to the watch);
 * [durationSeconds] is the countdown length in seconds (0 = blank/unused).
 */
data class TimerSlot(val label: String = "", val durationSeconds: Int = 0)

/** A named set of exactly 10 timer slots, stored on the phone. */
data class TimerSet(val id: String, val name: String, val slots: List<TimerSlot>) {

    init { require(slots.size == 10) { "a timer set has exactly 10 slots (got ${slots.size})" } }

    /** The 10 durations in slot order — the payload pushed to the watch. */
    fun durations(): List<Int> = slots.map { it.durationSeconds }

    companion object {
        /** A set of 10 blank slots. */
        fun blank(id: String, name: String): TimerSet =
            TimerSet(id, name, List(10) { TimerSlot() })
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /home/kbcaron/github/FreeOllee-Faces && ANDROID_HOME=/home/kbcaron/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "*TimerModelsTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /home/kbcaron/github/FreeOllee-Faces
git add app/src/main/java/com/blizzardcaron/freeolleefaces/timer/TimerModels.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/timer/TimerModelsTest.kt
git commit -m "feat(timer): TimerSlot/TimerSet model (10-slot invariant)"
```

---

## Task 3: `TimerSetEditing` (pure editor transforms)

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/timer/TimerSetEditing.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/timer/TimerSetEditingTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blizzardcaron.freeolleefaces.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class TimerSetEditingTest {

    private fun slots() = (0 until 10).map { TimerSlot("L$it", it) }

    @Test
    fun `fillDown copies the source duration into every slot below, leaving labels`() {
        val result = TimerSetEditing.fillDown(slots(), fromIndex = 1)
        assertEquals(1, result[1].durationSeconds)        // source unchanged
        assertEquals(0, result[0].durationSeconds)        // above untouched
        assertEquals(List(8) { 1 }, result.drop(2).map { it.durationSeconds }) // below = source
        assertEquals("L5", result[5].label)               // labels preserved
        assertEquals(10, result.size)
    }

    @Test
    fun `fillDown from the last index is a no-op on durations`() {
        val result = TimerSetEditing.fillDown(slots(), fromIndex = 9)
        assertEquals(slots().map { it.durationSeconds }, result.map { it.durationSeconds })
    }

    @Test
    fun `duplicateToNext copies label and duration into the next slot`() {
        val result = TimerSetEditing.duplicateToNext(slots(), index = 3)
        assertEquals(slots()[3], result[4])
        assertEquals(slots()[2], result[2]) // others untouched
        assertEquals(10, result.size)
    }

    @Test
    fun `duplicateToNext at the last index is a no-op`() {
        assertEquals(slots(), TimerSetEditing.duplicateToNext(slots(), index = 9))
    }

    @Test
    fun `hms and seconds round-trip`() {
        assertEquals(83, TimerSetEditing.hmsToSeconds(0, 1, 23))
        assertEquals(Triple(1, 0, 0), TimerSetEditing.secondsToHms(3600))
        assertEquals(Triple(0, 1, 23), TimerSetEditing.secondsToHms(83))
        assertEquals("00:10:00", TimerSetEditing.formatHms(600))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /home/kbcaron/github/FreeOllee-Faces && ANDROID_HOME=/home/kbcaron/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "*TimerSetEditingTest*"`
Expected: FAIL — `TimerSetEditing` unresolved.

- [ ] **Step 3: Create `TimerSetEditing.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.timer

/** Pure editor transforms for the Timer-set editor — no Android/UI dependencies. */
object TimerSetEditing {

    /**
     * Copy `slots[fromIndex].durationSeconds` into every slot below it; labels untouched.
     * The fast path for interval mode (fill 6 slots with one duration). Returns a new list.
     */
    fun fillDown(slots: List<TimerSlot>, fromIndex: Int): List<TimerSlot> {
        val d = slots[fromIndex].durationSeconds
        return slots.mapIndexed { i, slot ->
            if (i > fromIndex) slot.copy(durationSeconds = d) else slot
        }
    }

    /** Copy `slots[index]` (label + duration) into slot `index+1`; no-op if `index` is last. */
    fun duplicateToNext(slots: List<TimerSlot>, index: Int): List<TimerSlot> {
        if (index >= slots.lastIndex) return slots
        return slots.mapIndexed { i, slot -> if (i == index + 1) slots[index] else slot }
    }

    /** Combine an H:M:S triple into seconds (negatives clamped to 0). */
    fun hmsToSeconds(h: Int, m: Int, s: Int): Int =
        (h.coerceAtLeast(0) * 3600) + (m.coerceAtLeast(0) * 60) + s.coerceAtLeast(0)

    /** Split a seconds total into (hours, minutes, seconds). */
    fun secondsToHms(total: Int): Triple<Int, Int, Int> {
        val t = total.coerceAtLeast(0)
        return Triple(t / 3600, (t % 3600) / 60, t % 60)
    }

    /** "HH:MM:SS" for display. */
    fun formatHms(total: Int): String {
        val (h, m, s) = secondsToHms(total)
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /home/kbcaron/github/FreeOllee-Faces && ANDROID_HOME=/home/kbcaron/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "*TimerSetEditingTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /home/kbcaron/github/FreeOllee-Faces
git add app/src/main/java/com/blizzardcaron/freeolleefaces/timer/TimerSetEditing.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/timer/TimerSetEditingTest.kt
git commit -m "feat(timer): pure fill-down/duplicate + H:M:S helpers"
```

---

## Task 4: `TimerSetsJson` (pure JSON codec)

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/timer/TimerSetsJson.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/timer/TimerSetsJsonTest.kt`

`org.json` is built into the Android framework at runtime and is on the unit-test classpath via
`testImplementation(libs.org.json)` — no new dependency.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blizzardcaron.freeolleefaces.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerSetsJsonTest {

    private fun sampleSet(id: String) = TimerSet(
        id, "HIIT",
        (0 until 10).map { TimerSlot(if (it == 0) "Sprint" else "", it * 5) },
    )

    @Test
    fun `encode then decode round-trips sets, labels and durations`() {
        val sets = listOf(sampleSet("a"), TimerSet.blank("b", "Rest day"))
        val decoded = TimerSetsJson.decode(TimerSetsJson.encode(sets))
        assertEquals(sets, decoded)
    }

    @Test
    fun `decode of null or blank yields an empty list`() {
        assertTrue(TimerSetsJson.decode(null).isEmpty())
        assertTrue(TimerSetsJson.decode("").isEmpty())
        assertTrue(TimerSetsJson.decode("   ").isEmpty())
    }

    @Test
    fun `decode of malformed json yields an empty list, never throws`() {
        assertTrue(TimerSetsJson.decode("{not json").isEmpty())
        assertTrue(TimerSetsJson.decode("42").isEmpty())
    }

    @Test
    fun `decode skips a set whose slot count is not ten`() {
        // One valid set, one with only 2 slots -> only the valid one survives.
        val json = """[
          {"id":"ok","name":"n","slots":${"[" + (0 until 10).joinToString(",") { "{\"label\":\"\",\"dur\":0}" } + "]"}},
          {"id":"bad","name":"n","slots":[{"label":"","dur":0},{"label":"","dur":0}]}
        ]"""
        val decoded = TimerSetsJson.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("ok", decoded[0].id)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /home/kbcaron/github/FreeOllee-Faces && ANDROID_HOME=/home/kbcaron/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "*TimerSetsJsonTest*"`
Expected: FAIL — `TimerSetsJson` unresolved.

- [ ] **Step 3: Create `TimerSetsJson.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.timer

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON codec for persisting timer sets. Decoding NEVER throws — malformed/missing input
 * yields an empty list — so corrupt prefs can never crash the UI. A set whose slot count is not
 * exactly 10 is skipped (it would violate the [TimerSet] invariant).
 */
object TimerSetsJson {

    fun encode(sets: List<TimerSet>): String {
        val arr = JSONArray()
        for (set in sets) {
            val slots = JSONArray()
            for (slot in set.slots) {
                slots.put(JSONObject().put("label", slot.label).put("dur", slot.durationSeconds))
            }
            arr.put(JSONObject().put("id", set.id).put("name", set.name).put("slots", slots))
        }
        return arr.toString()
    }

    fun decode(json: String?): List<TimerSet> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val slotsArr = obj.optJSONArray("slots") ?: return@mapNotNull null
                if (slotsArr.length() != 10) return@mapNotNull null
                val slots = (0 until 10).map { j ->
                    val s = slotsArr.optJSONObject(j) ?: JSONObject()
                    TimerSlot(s.optString("label", ""), s.optInt("dur", 0))
                }
                TimerSet(obj.optString("id"), obj.optString("name", ""), slots)
            }
        }.getOrDefault(emptyList())
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /home/kbcaron/github/FreeOllee-Faces && ANDROID_HOME=/home/kbcaron/Android/Sdk ./gradlew :app:testDebugUnitTest --tests "*TimerSetsJsonTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /home/kbcaron/github/FreeOllee-Faces
git add app/src/main/java/com/blizzardcaron/freeolleefaces/timer/TimerSetsJson.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/timer/TimerSetsJsonTest.kt
git commit -m "feat(timer): JSON codec for timer sets (decode never throws)"
```

---

## Task 5: `TimerSetsRepository` (SharedPreferences glue)

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/timer/TimerSetsRepository.kt`

No unit test: this is thin SharedPreferences glue (consistent with the untested `Prefs`); its only
logic (codec, upsert/replace branch) is covered by `TimerSetsJsonTest` and verified at build +
on-device (Task 9). The `replace-in-place vs append` branch keeps display order stable on edit and
appends new sets; the UI caps creation at `MAX_SETS` (Task 8), so the `.take(MAX_SETS)` is a belt-
and-braces guard, not the primary limiter.

- [ ] **Step 1: Create `TimerSetsRepository.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.timer

import android.content.Context
import androidx.core.content.edit

/**
 * Persists up to [MAX_SETS] timer sets (JSON via [TimerSetsJson]) plus the active set id, in a
 * dedicated SharedPreferences file. Thin glue over the codec; mirrors the app's `Prefs` pattern.
 */
class TimerSetsRepository(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getAll(): List<TimerSet> = TimerSetsJson.decode(sp.getString(KEY_SETS, null))

    fun get(id: String): TimerSet? = getAll().firstOrNull { it.id == id }

    /** Insert or replace [set] by id. Replace keeps position; insert appends (capped at [MAX_SETS]). */
    fun save(set: TimerSet) {
        val existing = getAll()
        val merged = if (existing.any { it.id == set.id }) {
            existing.map { if (it.id == set.id) set else it }
        } else {
            (existing + set).take(MAX_SETS)
        }
        sp.edit { putString(KEY_SETS, TimerSetsJson.encode(merged)) }
    }

    fun delete(id: String) {
        val remaining = getAll().filter { it.id != id }
        sp.edit {
            putString(KEY_SETS, TimerSetsJson.encode(remaining))
            if (sp.getString(KEY_ACTIVE, null) == id) remove(KEY_ACTIVE)
        }
    }

    fun setActive(id: String) = sp.edit { putString(KEY_ACTIVE, id) }

    fun activeId(): String? = sp.getString(KEY_ACTIVE, null)

    companion object {
        const val MAX_SETS = 10
        private const val FILE = "timer_sets"
        private const val KEY_SETS = "sets"
        private const val KEY_ACTIVE = "active_id"
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /home/kbcaron/github/FreeOllee-Faces && ANDROID_HOME=/home/kbcaron/Android/Sdk ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/kbcaron/github/FreeOllee-Faces
git add app/src/main/java/com/blizzardcaron/freeolleefaces/timer/TimerSetsRepository.kt
git commit -m "feat(timer): TimerSetsRepository (sets + active id in SharedPreferences)"
```

---

## Task 6: `TimerSetsScreen` (list screen)

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt`

Compose UI — no unit test (verified by `compileDebugKotlin` + the on-device pass in Task 9). The
composable is pure presentation: it takes data + callbacks and owns no repository/Context.

- [ ] **Step 1: Create `TimerSetsScreen.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.timer.TimerSet
import com.blizzardcaron.freeolleefaces.timer.TimerSetEditing
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository

@Composable
fun TimerSetsScreen(
    sets: List<TimerSet>,
    activeId: String?,
    onOpen: (TimerSet) -> Unit,
    onNew: () -> Unit,
    onDuplicate: (TimerSet) -> Unit,
    onDelete: (TimerSet) -> Unit,
    onSend: (TimerSet) -> Unit,
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
            Text("Timer Sets", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Done") }
        }
        HorizontalDivider()

        val atMax = sets.size >= TimerSetsRepository.MAX_SETS
        Button(onClick = onNew, enabled = !atMax, modifier = Modifier.fillMaxWidth()) {
            Text(if (atMax) "Max ${TimerSetsRepository.MAX_SETS} sets" else "New set")
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (sets.isEmpty()) {
                Text("No sets yet. Tap “New set” to create one.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            for (set in sets) {
                TimerSetRow(
                    set = set,
                    active = set.id == activeId,
                    onOpen = { onOpen(set) },
                    onDuplicate = { onDuplicate(set) },
                    onDelete = { onDelete(set) },
                    onSend = { onSend(set) },
                )
            }
        }
    }
}

@Composable
private fun TimerSetRow(
    set: TimerSet,
    active: Boolean,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSend: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (set.name.isBlank()) "(unnamed)" else set.name,
                    style = MaterialTheme.typography.titleMedium)
                if (active) Text("● active", style = MaterialTheme.typography.labelMedium)
            }
            val count = set.slots.count { it.durationSeconds > 0 }
            val first = set.slots.firstOrNull { it.durationSeconds > 0 }?.durationSeconds
            val summary = if (first != null) {
                "$count of 10 set · first ${TimerSetEditing.formatHms(first)}"
            } else "all blank"
            Text(summary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpen) { Text("Edit") }
                TextButton(onClick = onSend) { Text("Send") }
                TextButton(onClick = onDuplicate) { Text("Duplicate") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /home/kbcaron/github/FreeOllee-Faces && ANDROID_HOME=/home/kbcaron/Android/Sdk ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/kbcaron/github/FreeOllee-Faces
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt
git commit -m "feat(ui): Timer Sets list screen"
```

---

## Task 7: `TimerSetEditScreen` (editor screen)

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/TimerSetEditScreen.kt`

Compose UI — no unit test (the fill-down/duplicate/H:M:S logic it calls is already covered by
`TimerSetEditingTest`). Verified by `compileDebugKotlin` + Task 9.

- [ ] **Step 1: Create `TimerSetEditScreen.kt`**

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.timer.TimerSet
import com.blizzardcaron.freeolleefaces.timer.TimerSetEditing
import com.blizzardcaron.freeolleefaces.timer.TimerSlot

@Composable
fun TimerSetEditScreen(
    set: TimerSet,
    onSave: (TimerSet) -> Unit,
    onSend: (TimerSet) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var working by remember(set.id) { mutableStateOf(set) }
    BackHandler { onBack() }

    fun updateSlot(index: Int, transform: (TimerSlot) -> TimerSlot) {
        working = working.copy(
            slots = working.slots.mapIndexed { i, s -> if (i == index) transform(s) else s },
        )
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Edit set", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Cancel") }
        }
        HorizontalDivider()

        OutlinedTextField(
            value = working.name,
            onValueChange = { working = working.copy(name = it) },
            label = { Text("Set name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            working.slots.forEachIndexed { index, slot ->
                SlotEditor(
                    index = index,
                    slot = slot,
                    onLabelChange = { newLabel -> updateSlot(index) { s -> s.copy(label = newLabel) } },
                    onDurationChange = { secs -> updateSlot(index) { s -> s.copy(durationSeconds = secs) } },
                    onFillDown = { working = working.copy(slots = TimerSetEditing.fillDown(working.slots, index)) },
                    onDuplicate = { working = working.copy(slots = TimerSetEditing.duplicateToNext(working.slots, index)) },
                )
            }
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onSave(working) }, modifier = Modifier.weight(1f)) { Text("Save") }
            Button(onClick = { onSend(working) }, modifier = Modifier.weight(1f)) { Text("Save & send") }
        }
    }
}

@Composable
private fun SlotEditor(
    index: Int,
    slot: TimerSlot,
    onLabelChange: (String) -> Unit,
    onDurationChange: (Int) -> Unit,
    onFillDown: () -> Unit,
    onDuplicate: () -> Unit,
) {
    val (h, m, s) = TimerSetEditing.secondsToHms(slot.durationSeconds)
    var menu by remember { mutableStateOf(false) }
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
                Text("Slot ${index + 1}", style = MaterialTheme.typography.titleSmall)
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋯") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Fill down") },
                            onClick = { menu = false; onFillDown() })
                        DropdownMenuItem(text = { Text("Duplicate to next") },
                            onClick = { menu = false; onDuplicate() })
                    }
                }
            }
            OutlinedTextField(
                value = slot.label,
                onValueChange = onLabelChange,
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("H", h) { onDurationChange(TimerSetEditing.hmsToSeconds(it, m, s)) }
                NumberField("M", m) { onDurationChange(TimerSetEditing.hmsToSeconds(h, it, s)) }
                NumberField("S", s) { onDurationChange(TimerSetEditing.hmsToSeconds(h, m, it)) }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { onChange(it.filter(Char::isDigit).take(2).toIntOrNull() ?: 0) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(80.dp),
    )
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /home/kbcaron/github/FreeOllee-Faces && ANDROID_HOME=/home/kbcaron/Android/Sdk ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /home/kbcaron/github/FreeOllee-Faces
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ui/TimerSetEditScreen.kt
git commit -m "feat(ui): Timer set editor (H:M:S, fill-down, duplicate)"
```

---

## Task 8: Navigation + Home entry + push wiring (integration)

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/Screen.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt`

- [ ] **Step 1: Add the two routes** to `ui/Screen.kt`

```kotlin
sealed interface Screen {
    data object Home : Screen
    data object FacesList : Screen
    data object Settings : Screen
    data object TimerSets : Screen
    data object TimerSetEdit : Screen
}
```

- [ ] **Step 2: Add the Home callback + button** in `ui/HomeScreen.kt`

Add a field to the `HomeCallbacks` data class (after `onOpenFaces`):

```kotlin
    val onOpenFaces: () -> Unit,
    val onOpenTimerSets: () -> Unit,
    val onOpenSettings: () -> Unit,
```

In the header `Row` (currently `TextButton(onClick = callbacks.onOpenFaces) { Text("Faces") }`),
add a Timers button before it:

```kotlin
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = callbacks.onOpenTimerSets) { Text("Timers") }
                TextButton(onClick = callbacks.onOpenFaces) { Text("Faces") }
                IconButton(onClick = callbacks.onOpenSettings) {
                    Text("⚙", style = MaterialTheme.typography.titleLarge)
                }
            }
```

- [ ] **Step 3: Wire repository, state, and actions** in `MainActivity.kt`

Add imports (near the other `com.blizzardcaron.freeolleefaces` imports):

```kotlin
import com.blizzardcaron.freeolleefaces.timer.TimerSet
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import com.blizzardcaron.freeolleefaces.ui.TimerSetEditScreen
import com.blizzardcaron.freeolleefaces.ui.TimerSetsScreen
import java.util.UUID
```

After `val ble = remember { OlleeBleClient(context) }` (≈ line 94), add repository + UI state:

```kotlin
    val timerRepo = remember { TimerSetsRepository(context) }
    var timerSets by remember { mutableStateOf(timerRepo.getAll()) }
    var timerActiveId by remember { mutableStateOf(timerRepo.activeId()) }
    var editingSet by remember { mutableStateOf<TimerSet?>(null) }
```

Near the other local `fun`s (e.g. after `fun update(...)` ≈ line 133), add:

```kotlin
    fun refreshTimers() {
        timerSets = timerRepo.getAll()
        timerActiveId = timerRepo.activeId()
    }

    fun newTimerSet() {
        val set = TimerSet.blank(UUID.randomUUID().toString(), "Set ${timerSets.size + 1}")
        timerRepo.save(set)
        refreshTimers()
        editingSet = set
        screen = Screen.TimerSetEdit
    }

    fun sendTimerSet(set: TimerSet) {
        val addr = prefs.watchAddress
        if (addr == null) { showSnackbar("No watch selected — open Settings (⚙)"); return }
        scope.launch {
            update { it.copy(sending = true) }
            val result = ble.sendPacket(addr, OlleeProtocol.buildTimerPacket(set.durations()))
            update { it.copy(sending = false) }
            result
                .onSuccess {
                    timerRepo.setActive(set.id)
                    timerActiveId = set.id
                    showSnackbar("Sent '${set.name}' to watch")
                }
                .onFailure {
                    showSnackbar("Send failed — long-press ALARM to wake the watch, then retry")
                }
        }
    }
```

- [ ] **Step 4: Add the Home callback value** in the `HomeCallbacks(...)` constructor (after `onOpenFaces = ...`):

```kotlin
        onOpenFaces = { screen = Screen.FacesList },
        onOpenTimerSets = { refreshTimers(); screen = Screen.TimerSets },
        onOpenSettings = { screen = Screen.Settings },
```

- [ ] **Step 5: Add the two nav branches** in the `when (screen) { ... }` block (after the `Screen.Settings -> ...` branch)

```kotlin
        Screen.TimerSets -> TimerSetsScreen(
            sets = timerSets,
            activeId = timerActiveId,
            onOpen = { editingSet = it; screen = Screen.TimerSetEdit },
            onNew = { newTimerSet() },
            onDuplicate = { src ->
                if (timerSets.size < TimerSetsRepository.MAX_SETS) {
                    timerRepo.save(src.copy(id = UUID.randomUUID().toString(), name = src.name + " copy"))
                    refreshTimers()
                }
            },
            onDelete = { timerRepo.delete(it.id); refreshTimers() },
            onSend = { sendTimerSet(it) },
            onBack = { screen = Screen.Home },
            modifier = modifier,
        )
        Screen.TimerSetEdit -> {
            val editing = editingSet
            if (editing == null) {
                screen = Screen.TimerSets
            } else {
                TimerSetEditScreen(
                    set = editing,
                    onSave = { s -> timerRepo.save(s); refreshTimers(); screen = Screen.TimerSets },
                    onSend = { s -> timerRepo.save(s); refreshTimers(); sendTimerSet(s) },
                    onBack = { screen = Screen.TimerSets },
                    modifier = modifier,
                )
            }
        }
```

- [ ] **Step 6: Build the whole app + run all unit tests**

Run: `cd /home/kbcaron/github/FreeOllee-Faces && ANDROID_HOME=/home/kbcaron/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; all tests pass.

- [ ] **Step 7: Verify the no-ollee-graphene constraint**

Run: `cd /home/kbcaron/github/FreeOllee-Faces && git grep -n "ollee-graphene" -- app/ ; echo "exit: $?"`
Expected: no matches in `app/` (exit 1 from grep = clean).

- [ ] **Step 8: Commit**

```bash
cd /home/kbcaron/github/FreeOllee-Faces
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ui/Screen.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "feat(timer): wire Timer Sets into nav + Home + manual push"
```

---

## Task 9: On-device validation (the two spec gates)

**Goal:** confirm the real `02 26` write lands and that all 10 durations persist with our zero
header. Requires the watch paired to the test phone and the capture build of the official app
available for read-back (optional but ideal). Devices: phone adb `192.168.4.20:37229` (FreeOllee
debug build), watch `00:80:E1:26:DC:86`.

- [ ] **Step 1: Install the debug build**

Run:
```bash
export ANDROID_HOME=/home/kbcaron/Android/Sdk; export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb connect 192.168.4.20:37229
adb -s 192.168.4.20:37229 install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Success`.

- [ ] **Step 2: Create + send a test set (manual, on phone)**
  - Open FreeOllee-Faces → **Timers** → **New set**.
  - Set slot 1 = `00:01:40`, use the slot-1 overflow → **Fill down** → confirm slots 2–10 all show `00:01:40`.
  - Set slot 7 = `00:00:00` (label "Interval stop"); leave a couple blank.
  - Tap **Save & send**. Expect the "Sent '…' to watch" snackbar and the set marked **active** in the list.

- [ ] **Step 3: GATE 1 — confirm durations persist on the watch.** On the watch, open the Timer
  face and scroll the slots; verify the durations match what was sent (slots 1–6 = `00:01:40`,
  slot 7 blank, etc.). This proves the zero header (`00 00 00 00`) is accepted and the firmware
  stores all 10 words.
  - **If the watch did NOT store them** (e.g. only the last-edited shows, or values are wrong):
    the header is not inert. Change `buildTimerPacket` to echo slot 1's H:M:S in the header
    (`secondsToHms(durationsSeconds[0])` → bytes `[H, M, S, 0]`) and repeat. Document the result
    in the spec.

- [ ] **Step 4: GATE 2 — Timer face visible.** Confirm the Timer face is enabled on the watch
  (if not, enable it once via the official app's Faces list). Note this prerequisite in the app
  if helpful (out of scope to automate per spec §10).

- [ ] **Step 5 (optional cross-check): capture the real frame.** With the capture build of the
  official app NOT running, our app's write can be confirmed via the phone's Bluetooth HCI snoop
  or by re-reading on the official app. At minimum, eyeball the watch. Record the outcome.

- [ ] **Step 6: Record the validation result** in `docs/superpowers/specs/2026-06-03-timer-sets-design.md`
  under §9 (header gate: pass/fail + final header choice). Commit:

```bash
cd /home/kbcaron/github/FreeOllee-Faces
git add docs/superpowers/specs/2026-06-03-timer-sets-design.md
git commit -m "docs(spec): record on-device timer header validation result"
```

---

## Self-Review

**Spec coverage:**
- §2 protocol → Task 1 (`buildTimerPacket`, LE-uint32, zero header) + Task 9 (header gate). ✓
- §3 data model (10-slot invariant, range) → Task 2 + Task 1 range guard. ✓
- §4 persistence (JSON in prefs, never throws, caps) → Task 4 (codec) + Task 5 (repo). ✓
- §6 fill-down/duplicate + H:M:S → Task 3 + editor Task 7. ✓
- §7 push via `sendPacket`, mark active, failure copy → Task 8 `sendTimerSet`. ✓ (Deviation:
  failure surfaces as a **foreground snackbar** with the wake wording, not the background
  `ErrorNotifier` notification — appropriate for a manual action; the notification path stays
  owned by the WorkManager chain.)
- §8 UI (list + editor, Home entry, nav) → Tasks 6, 7, 8. ✓
- §10 out-of-scope honored (no read-back, no faces-table write). ✓
- §11 testing → Tasks 1–4 tests; UI/glue verified by build + Task 9. ✓

**Placeholder scan:** none — every code step is complete.

**Type consistency:** `TimerSlot(label, durationSeconds)`, `TimerSet(id, name, slots)`,
`TimerSet.blank`, `durations()`, `TimerSetEditing.{fillDown,duplicateToNext,hmsToSeconds,secondsToHms,formatHms}`,
`TimerSetsJson.{encode,decode}`, `TimerSetsRepository.{getAll,get,save,delete,setActive,activeId,MAX_SETS}`,
`OlleeProtocol.{TARGET_TIMERS,buildTimerPacket}`, `Screen.{TimerSets,TimerSetEdit}`,
`HomeCallbacks.onOpenTimerSets` — names match across all tasks. ✓
