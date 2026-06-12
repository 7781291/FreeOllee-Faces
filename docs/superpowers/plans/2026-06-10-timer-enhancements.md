# Timer Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persistent "Quick timer" and remote "Start on watch" to the Timer feature, driven entirely by the existing `0x26` write — the header `MM:SS` is the Quick timer and header `byte3` selects start/mode.

**Architecture:** Extend `OlleeProtocol.buildTimerPacket` with an explicit `headerSeconds` (the Quick timer) and a `TimerStartMode` enum that writes header `byte3` (`00` save / `01` start-interval / `02` start-single). Persist the Quick timer in `Prefs`. `AppViewModel` gains save/start methods over the existing `ble.sendPacket` path. The Timer Sets screen gets a Quick Timer card and per-set Start buttons.

**Tech Stack:** Kotlin Multiplatform (commonMain logic, androidMain UI host), Jetpack Compose Material3, `com.russhwolf.settings` (multiplatform-settings), kotlinx-coroutines test. Build: Gradle (`./gradlew`). Tests: `:app:testDebugUnitTest`.

**Source of truth:** `docs/superpowers/specs/2026-06-10-timer-enhancements-design.md` (incl. the verified capture frames used below as golden vectors).

---

## File Structure

- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt` — add `quickTimerSeconds` (Task 1).
- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt` — `TimerStartMode` enum + new `buildTimerPacket` signature (Task 2).
- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt` — Quick-timer state, `pushTimerFrame` helper, `saveQuickTimer`, `startTimerSet`, `startQuickTimer` (Tasks 2–3).
- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerWidgets.kt` — **new**, holds the shared `NumberField` extracted from the edit screen (Task 4).
- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetEditScreen.kt` — use the shared `NumberField` (Task 4).
- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt` — Quick Timer card + per-set Start button + new callbacks (Task 5).
- `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt` — wire the new callbacks (Task 6).
- Tests: `prefs/PrefsTest.kt`, `ble/OlleeProtocolTest.kt`, `AppViewModelTest.kt`, `fakes/Fakes.kt` (packet capture).
- Cross-repo: `ollee-graphene/docs/reference/ollee-ble-protocol.md` (Task 8).

---

## Task 1: Persist the Quick timer in Prefs

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/prefs/PrefsTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `PrefsTest.kt` (uses the same `MapSettings`/`Prefs` pattern already in that file):

```kotlin
@Test
fun `quickTimerSeconds defaults to 180 and round-trips, clamping negatives to zero`() {
    val settings = MapSettings()
    val prefs = Prefs(settings)
    assertEquals(180, prefs.quickTimerSeconds)        // default 03:00

    prefs.quickTimerSeconds = 427                      // 07:07
    assertEquals(427, Prefs(settings).quickTimerSeconds)

    prefs.quickTimerSeconds = -5                        // clamped
    assertEquals(0, prefs.quickTimerSeconds)
}
```

If `MapSettings`/`assertEquals` imports are not already present in the file, add:
`import com.russhwolf.settings.MapSettings` and `import kotlin.test.assertEquals`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.prefs.PrefsTest"`
Expected: FAIL — `quickTimerSeconds` unresolved reference.

- [ ] **Step 3: Add the property and key**

In `Prefs.kt`, add the property next to the other simple accessors (e.g. just after `customText`):

```kotlin
var quickTimerSeconds: Int
    get() = settings.getInt(KEY_QUICK_TIMER_SECONDS, 180)
    set(value) = settings.putInt(KEY_QUICK_TIMER_SECONDS, value.coerceAtLeast(0))
```

In the `companion object`, add the key alongside the others:

```kotlin
private const val KEY_QUICK_TIMER_SECONDS = "quick_timer_seconds"
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.prefs.PrefsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/prefs/Prefs.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/prefs/PrefsTest.kt
git commit -m "feat: persist quick timer seconds in Prefs"
```

---

## Task 2: TimerStartMode + new buildTimerPacket signature

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt:163-181` (`sendTimerSet`, to keep the build green)
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocolTest.kt`

This task **changes** `buildTimerPacket`'s signature, so the test updates, the implementation, and the one production caller all land in the same commit.

- [ ] **Step 1: Update existing timer tests and add new ones**

In `OlleeProtocolTest.kt`, the existing `// --- Timer slots (0x26) ---` block uses the old single-arg form. Replace the five existing `buildTimerPacket` calls and the two header tests as follows.

Add `headerSeconds = 0` to these three (behavior otherwise unchanged):

```kotlin
@Test
fun `buildTimerPacket with all-zero durations equals a zero-header raw 0x26 packet`() {
    val packet = OlleeProtocol.buildTimerPacket(List(10) { 0 }, headerSeconds = 0)
    val expected = OlleeProtocol.buildRawPacket(OlleeProtocol.TARGET_TIMERS, ByteArray(44))
    assertContentEquals(expected, packet)
}

@Test
fun `buildTimerPacket encodes each slot as a little-endian uint32 of seconds`() {
    val packet = OlleeProtocol.buildTimerPacket(listOf(83, 0, 0, 0, 0, 0, 0, 0, 0, 0), headerSeconds = 0)
    assertEquals(0x02.toByte(), packet[6])
    assertEquals(0x26.toByte(), packet[7])
    assertEquals(0x00.toByte(), packet[8])  // header byte 0
    assertEquals(0x53.toByte(), packet[12]) // 83 low byte
    assertEquals(0x00.toByte(), packet[13])
    assertEquals(0x00.toByte(), packet[14])
    assertEquals(0x00.toByte(), packet[15])
}
```

**Replace** the `seeds the header with slot 1` test with one proving the header comes from
`headerSeconds`, independent of slot 1:

```kotlin
@Test
fun `buildTimerPacket derives the header minutes and seconds from headerSeconds not slot 1`() {
    // headerSeconds = 100 s (00:01:40); slot 1 = 83 s — deliberately different.
    val packet = OlleeProtocol.buildTimerPacket(listOf(83, 0, 0, 0, 0, 0, 0, 0, 0, 0), headerSeconds = 100)
    assertEquals(0x00.toByte(), packet[8])  // header byte 0
    assertEquals(1.toByte(), packet[9])     // minutes from headerSeconds
    assertEquals(40.toByte(), packet[10])   // seconds from headerSeconds
    assertEquals(0x00.toByte(), packet[11]) // header byte 3 = SAVE
    assertEquals(0x53.toByte(), packet[12]) // slot 1 word still 83, untouched by the header
}
```

**Replace** the `clamps ... slot 1 over 255 minutes` test with the headerSeconds clamp:

```kotlin
@Test
fun `buildTimerPacket clamps the header minutes byte when headerSeconds over 255 minutes`() {
    // headerSeconds = 359_999 s → 5999 minutes overflows one byte; clamp to 0xFF.
    val packet = OlleeProtocol.buildTimerPacket(List(10) { 0 }, headerSeconds = 359_999)
    assertEquals(0xFF.toByte(), packet[9])  // minutes clamped
    assertEquals(59.toByte(), packet[10])   // 359999 % 60
}
```

Add `headerSeconds = 0` to the round-trip and the two rejection tests:

```kotlin
@Test
fun `buildTimerPacket round-trips through parseFrame to target 0x26 with valid CRC`() {
    val packet = OlleeProtocol.buildTimerPacket(
        listOf(83, 100, 100, 100, 100, 100, 0, 100_000, 900, 1800), headerSeconds = 0)
    val f = OlleeProtocol.parseFrame(packet)!!
    assertEquals(0x26, f.target)
    assertTrue(f.crcOk)
    val base = 4 + 7 * 4
    val slot8 = (f.payload[base].toInt() and 0xFF) or
        ((f.payload[base + 1].toInt() and 0xFF) shl 8) or
        ((f.payload[base + 2].toInt() and 0xFF) shl 16) or
        ((f.payload[base + 3].toInt() and 0xFF) shl 24)
    assertEquals(100_000, slot8)
}

@Test
fun `buildTimerPacket rejects a list that is not exactly 10 slots`() {
    assertFailsWith<IllegalArgumentException> {
        OlleeProtocol.buildTimerPacket(listOf(1, 2, 3), headerSeconds = 0)
    }
}

@Test
fun `buildTimerPacket rejects an out-of-range duration`() {
    assertFailsWith<IllegalArgumentException> {
        OlleeProtocol.buildTimerPacket(listOf(360_000, 0, 0, 0, 0, 0, 0, 0, 0, 0), headerSeconds = 0)
    }
}
```

Add new `byte3` and golden-vector tests (the golden hex are the verified captures from the spec):

```kotlin
@Test
fun `buildTimerPacket writes header byte3 per start mode`() {
    val slots = List(10) { 0 }
    assertEquals(0x00.toByte(),
        OlleeProtocol.buildTimerPacket(slots, headerSeconds = 0, startMode = OlleeProtocol.TimerStartMode.SAVE)[11])
    assertEquals(0x01.toByte(),
        OlleeProtocol.buildTimerPacket(slots, headerSeconds = 0, startMode = OlleeProtocol.TimerStartMode.START_INTERVAL)[11])
    assertEquals(0x02.toByte(),
        OlleeProtocol.buildTimerPacket(slots, headerSeconds = 0, startMode = OlleeProtocol.TimerStartMode.START_SINGLE)[11])
}

@Test
fun `buildTimerPacket reproduces the captured save and start-interval frames`() {
    val slots = listOf(180, 30, 180, 30, 0, 60, 120, 600, 900, 1800)
    fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
    // Baseline "Send to watch", picker 03:00, SAVE.
    assertEquals(
        "0032AA5577CA022600030000B40000001E000000B40000001E000000000000003C00000078000000580200008403000008070000",
        hex(OlleeProtocol.buildTimerPacket(slots, headerSeconds = 180, startMode = OlleeProtocol.TimerStartMode.SAVE)))
    // "Start from app" ON, picker 07:07, interval.
    assertEquals(
        "0032AA550558022600070701B40000001E000000B40000001E000000000000003C00000078000000580200008403000008070000",
        hex(OlleeProtocol.buildTimerPacket(slots, headerSeconds = 427, startMode = OlleeProtocol.TimerStartMode.START_INTERVAL)))
}
```

- [ ] **Step 2: Run the timer tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.ble.OlleeProtocolTest"`
Expected: FAIL — `headerSeconds`/`startMode`/`TimerStartMode` unresolved.

- [ ] **Step 3: Implement the enum and new signature**

In `OlleeProtocol.kt`, add the enum inside the `object OlleeProtocol` (near `TARGET_TIMERS`):

```kotlin
/** Header byte 3 of the 0x26 timer write: configure-only, or start now in interval/single mode. */
enum class TimerStartMode(val byte3: Int) { SAVE(0x00), START_INTERVAL(0x01), START_SINGLE(0x02) }
```

Replace the body of `buildTimerPacket` (keep the KDoc, but update it to describe `headerSeconds`
seeding the Quick-timer header and `startMode` driving byte 3):

```kotlin
fun buildTimerPacket(
    durationsSeconds: List<Int>,
    headerSeconds: Int,
    startMode: TimerStartMode = TimerStartMode.SAVE,
): ByteArray {
    require(durationsSeconds.size == 10) {
        "timer table needs exactly 10 slots (got ${durationsSeconds.size})"
    }
    require(durationsSeconds.all { it in 0..359_999 }) {
        "each duration must be 0..359999 s (got $durationsSeconds)"
    }
    require(headerSeconds >= 0) { "headerSeconds must be >= 0 (got $headerSeconds)" }
    val payload = ByteArray(4 + 10 * 4) // 4-byte header + 10 LE-uint32 words
    payload[1] = (headerSeconds / 60).coerceAtMost(0xFF).toByte() // MM (Quick-timer primary)
    payload[2] = (headerSeconds % 60).toByte()                    // SS
    payload[3] = startMode.byte3.toByte()                          // start/mode selector
    durationsSeconds.forEachIndexed { i, s ->
        val off = 4 + i * 4
        payload[off] = (s and 0xFF).toByte()
        payload[off + 1] = ((s shr 8) and 0xFF).toByte()
        payload[off + 2] = ((s shr 16) and 0xFF).toByte()
        payload[off + 3] = ((s shr 24) and 0xFF).toByte()
    }
    return buildRawPacket(TARGET_TIMERS, payload)
}
```

- [ ] **Step 4: Fix the one production caller so the build stays green**

In `AppViewModel.kt`, update the `sendTimerSet` send line (currently
`OlleeProtocol.buildTimerPacket(set.durations())`) to:

```kotlin
val result = ble.sendPacket(
    addr,
    OlleeProtocol.buildTimerPacket(set.durations(), headerSeconds = prefs.quickTimerSeconds),
)
```

(A fuller refactor of `sendTimerSet` happens in Task 3; this is the minimal change to compile.)

- [ ] **Step 5: Run protocol tests + full unit suite to verify green**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.ble.OlleeProtocolTest"`
Expected: PASS.
Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (the existing `AppViewModelTest` send tests still pass — no behavior change).

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocolTest.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt
git commit -m "feat: header byte3 start/mode + explicit quick-timer header in buildTimerPacket"
```

---

## Task 3: ViewModel — Quick-timer state, push helper, start methods

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/fakes/Fakes.kt` (capture sent packets)
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/AppViewModelTest.kt`

- [ ] **Step 1: Add packet capture to FakeBleClient**

In `Fakes.kt`, give `FakeBleClient` a public list and record the bytes in `sendPacket`:

```kotlin
class FakeBleClient(
    private val callLog: MutableList<String> = mutableListOf(),
    var sendResult: Result<Unit> = Result.success(Unit),
    var gate: CompletableDeferred<Unit>? = null,
) : BleClient {
    /** Every packet passed to [sendPacket], in order — lets tests assert on the framed bytes. */
    val sentPackets: MutableList<ByteArray> = mutableListOf()
    // ... existing send(...) overloads unchanged ...
    override suspend fun sendPacket(deviceAddress: String, packet: ByteArray): Result<Unit> {
        gate?.await()
        callLog += "ble.sendPacket($deviceAddress)"
        sentPackets += packet
        return sendResult
    }
}
```

- [ ] **Step 2: Write the failing ViewModel tests**

Add to `AppViewModelTest.kt`, mirroring the existing `sendTimerSet_*` tests (same `vm` construction
— reuse the helper/fakes those tests use; `FakeBleClient(callLog)` exposes `sentPackets`). Use a
blank 10-slot set via `TimerSet.blank(...)`:

```kotlin
@Test
fun startQuickTimer_noWatchAddress_showsSnackbarAndNoBleCall() = runTest(testScheduler) {
    val callLog = mutableListOf<String>()
    val ble = FakeBleClient(callLog)
    val settings = MapSettings()
    val prefs = Prefs(settings)            // no watchAddress set
    val vm = /* build vm with prefs, ble — same as sendTimerSet_noWatchAddress test */ TODO_REUSE_VM_BUILDER()

    vm.startQuickTimer()
    advanceUntilIdle()

    assertTrue(callLog.none { it.startsWith("ble.sendPacket") }, "no BLE call without a watch")
}

@Test
fun startQuickTimer_success_sendsStartSingleFrameWithActiveSetSlots() = runTest(testScheduler) {
    val callLog = mutableListOf<String>()
    val ble = FakeBleClient(callLog)
    val settings = MapSettings()
    val prefs = Prefs(settings)
    prefs.watchAddress = "00:11:22:33:44:55"
    prefs.quickTimerSeconds = 427          // 07:07 → header MM=7 SS=7
    val timerRepo = TimerSetsRepository(MapSettings())
    val set = TimerSet.blank("id1", "Set 1").let {
        it.copy(slots = it.slots.toMutableList().also { s -> s[0] = TimerSlot(durationSeconds = 180) })
    }
    timerRepo.save(set)
    timerRepo.setActive("id1")
    val vm = /* build vm with prefs, ble, timerRepo — same shape as the other timer tests */ TODO_REUSE_VM_BUILDER()

    vm.startQuickTimer()
    advanceUntilIdle()

    val pkt = ble.sentPackets.single()
    assertEquals(0x02.toByte(), pkt[11], "header byte3 = START_SINGLE")
    assertEquals(7.toByte(), pkt[9], "header MM from quickTimerSeconds")
    assertEquals(7.toByte(), pkt[10], "header SS from quickTimerSeconds")
    // slot 1 word (offset 12..15) carries the active set's first slot = 180 s = 0xB4.
    assertEquals(0xB4.toByte(), pkt[12])
}
```

> **Implementation note for the worker:** `TODO_REUSE_VM_BUILDER()` is a placeholder — construct the
> `AppViewModel` exactly as the existing `sendTimerSet_inflight_secondCallIgnored_andSuccessSetsActiveId`
> test does (same constructor args: `prefs`, `ble`, the fake steps/location/notification/scheduler
> providers, and `timerRepo`). Copy that construction; do not invent a new builder. Ensure imports
> for `TimerSet`, `TimerSlot`, `TimerSetsRepository`, `MapSettings`, `advanceUntilIdle` are present.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.AppViewModelTest"`
Expected: FAIL — `startQuickTimer` unresolved.

- [ ] **Step 4: Add Quick-timer state, a shared push helper, and the methods**

In `AppViewModel.kt`, add state near `timerActiveId` (line ~89):

```kotlin
var quickTimerSeconds by mutableStateOf(prefs.quickTimerSeconds)
    private set
```

Add a private DRY helper for the three timer sends (factor out the addr/guard/launch/snackbar that
`sendTimerSet` currently inlines):

```kotlin
/** Shared path for the three 0x26 sends: addr check, in-flight guard, push, snackbar. */
private fun pushTimerFrame(packet: ByteArray, successMsg: String, onSuccess: () -> Unit = {}) {
    val addr = prefs.watchAddress
    if (addr == null) { showSnackbar("No watch selected — open Settings (⚙)"); return }
    if (state.sending) return
    viewModelScope.launch {
        update { it.copy(sending = true) }
        val result = ble.sendPacket(addr, packet)
        update { it.copy(sending = false) }
        result
            .onSuccess { onSuccess(); showSnackbar(successMsg) }
            .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
    }
}
```

Replace the whole body of `sendTimerSet` with the helper call, and add the two new methods plus
`saveQuickTimer`:

```kotlin
fun saveQuickTimer(seconds: Int) {
    prefs.quickTimerSeconds = seconds
    quickTimerSeconds = prefs.quickTimerSeconds   // read back to apply the >=0 coercion
}

fun sendTimerSet(set: TimerSet) {
    val packet = OlleeProtocol.buildTimerPacket(
        set.durations(), headerSeconds = quickTimerSeconds, startMode = OlleeProtocol.TimerStartMode.SAVE)
    pushTimerFrame(packet, "Sent '${set.name}' to watch") {
        timerRepo.setActive(set.id); timerActiveId = set.id
    }
}

fun startTimerSet(set: TimerSet) {
    val packet = OlleeProtocol.buildTimerPacket(
        set.durations(), headerSeconds = quickTimerSeconds, startMode = OlleeProtocol.TimerStartMode.START_INTERVAL)
    pushTimerFrame(packet, "Started '${set.name}' on watch") {
        timerRepo.setActive(set.id); timerActiveId = set.id
    }
}

fun startQuickTimer() {
    val slots = timerActiveId?.let { timerRepo.get(it) }?.durations() ?: List(10) { 0 }
    val packet = OlleeProtocol.buildTimerPacket(
        slots, headerSeconds = quickTimerSeconds, startMode = OlleeProtocol.TimerStartMode.START_SINGLE)
    pushTimerFrame(packet, "Started quick timer on watch")
}
```

Confirm `mutableStateOf` is already imported (it is — `timerSets`/`timerActiveId` use it).

- [ ] **Step 5: Run the unit suite to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.AppViewModelTest"`
Expected: PASS (new tests green; the existing `sendTimerSet_*` tests still pass through `pushTimerFrame`).
Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (whole suite).

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/AppViewModelTest.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/fakes/Fakes.kt
git commit -m "feat: quick-timer state + startTimerSet/startQuickTimer over a shared push helper"
```

---

## Task 4: Extract a shared NumberField widget

`NumberField` is currently `private` in `TimerSetEditScreen.kt`. The Quick Timer card needs the same
H/M/S input, so move it to a shared file (DRY) rather than duplicating.

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerWidgets.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetEditScreen.kt`

- [ ] **Step 1: Create the shared widget file**

Copy the existing `NumberField` body verbatim from `TimerSetEditScreen.kt` (lines ~149-159) into a
new file, changing only its visibility from `private` to `internal`:

```kotlin
package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Small fixed-width integer field labeled H/M/S, shared by the timer editor and the Quick Timer card. */
@Composable
internal fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    // paste the exact body from TimerSetEditScreen's NumberField, unchanged
}
```

> **Worker note:** open `TimerSetEditScreen.kt`, copy the real `NumberField` implementation (its
> `OutlinedTextField` configuration, modifiers, keyboard options) exactly — do not reinvent it. Bring
> over whatever imports that body needs.

- [ ] **Step 2: Remove the private copy from the edit screen**

Delete the `private fun NumberField(...)` definition from `TimerSetEditScreen.kt`. The remaining call
sites (`NumberField("H", h) { ... }`, etc.) now resolve to the shared `internal` one in the same
package — no import needed. Remove any imports that are now unused **only if** they are no longer
referenced elsewhere in the file (e.g. don't remove `OutlinedTextField` if the slot editor still uses it).

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerWidgets.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetEditScreen.kt
git commit -m "refactor: extract shared NumberField widget for reuse"
```

---

## Task 5: Quick Timer card + per-set Start in TimerSetsScreen

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt`

- [ ] **Step 1: Extend the screen's signature with Quick-timer inputs and Start callbacks**

Update `TimerSetsScreen(...)` parameters — add the four new ones alongside the existing list:

```kotlin
@Composable
fun TimerSetsScreen(
    sets: List<TimerSet>,
    activeId: String?,
    sending: Boolean,
    quickTimerSeconds: Int,
    onSaveQuick: (Int) -> Unit,
    onStartQuick: () -> Unit,
    onOpen: (TimerSet) -> Unit,
    onNew: () -> Unit,
    onDuplicate: (TimerSet) -> Unit,
    onDelete: (TimerSet) -> Unit,
    onSend: (TimerSet) -> Unit,
    onStart: (TimerSet) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
```

- [ ] **Step 2: Add the Quick Timer card at the top of the list column**

Inside the outer `Column`, directly under the `HorizontalDivider()` (before the `New set` button),
insert the card. It uses `TimerSetEditing.secondsToHms`/`hmsToSeconds` and the shared `NumberField`:

```kotlin
Card(modifier = Modifier.fillMaxWidth()) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Quick timer", style = MaterialTheme.typography.titleMedium)
        val (h, m, s) = TimerSetEditing.secondsToHms(quickTimerSeconds)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NumberField("H", h) { onSaveQuick(TimerSetEditing.hmsToSeconds(it, m, s)) }
            NumberField("M", m) { onSaveQuick(TimerSetEditing.hmsToSeconds(h, it, s)) }
            NumberField("S", s) { onSaveQuick(TimerSetEditing.hmsToSeconds(h, m, it)) }
        }
        Button(onClick = onStartQuick, enabled = !sending, modifier = Modifier.fillMaxWidth()) {
            Text("▶ Start on watch")
        }
    }
}
```

Add imports as needed: `androidx.compose.material3.Card`. (`Button`, `Row`, `Text`, `Column`,
`Arrangement`, `Alignment`, `MaterialTheme`, `padding`, `fillMaxWidth`, `TimerSetEditing` are already
imported in this file.)

- [ ] **Step 3: Add a Start button to each set row**

In `TimerSetRow`, add an `onStart` parameter and a Start `TextButton` in the action `Row` (next to
Duplicate/Delete):

```kotlin
@Composable
private fun TimerSetRow(
    set: TimerSet,
    active: Boolean,
    sending: Boolean,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSend: () -> Unit,
    onStart: () -> Unit,
) {
    // ... unchanged card/colors/summary ...
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onStart, enabled = !sending) { Text("▶ Start") }
        TextButton(onClick = onDuplicate) { Text("Duplicate") }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}
```

And pass it through at the `TimerSetRow(...)` call site in the list loop:

```kotlin
TimerSetRow(
    set = set,
    active = set.id == activeId,
    sending = sending,
    onOpen = { onOpen(set) },
    onDuplicate = { onDuplicate(set) },
    onDelete = { onDelete(set) },
    onSend = { onSend(set) },
    onStart = { onStart(set) },
)
```

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: FAIL — `MainActivity` still calls `TimerSetsScreen` without the new required params. That is
expected and fixed in Task 6. (If you prefer a green build at every commit, do Task 6 before
committing this; otherwise commit and let Task 6 restore the build.)

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/TimerSetsScreen.kt
git commit -m "feat: quick timer card and per-set start button on the timer sets screen"
```

---

## Task 6: Wire the new callbacks in MainActivity

**Files:**
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt:277-288`

- [ ] **Step 1: Pass the new state and callbacks**

Update the `Screen.TimerSets -> TimerSetsScreen(...)` call to supply the four new arguments:

```kotlin
Screen.TimerSets -> TimerSetsScreen(
    sets = viewModel.timerSets,
    activeId = viewModel.timerActiveId,
    sending = state.sending,
    quickTimerSeconds = viewModel.quickTimerSeconds,
    onSaveQuick = { viewModel.saveQuickTimer(it) },
    onStartQuick = { viewModel.startQuickTimer() },
    onOpen = { viewModel.editTimerSet(it); viewModel.navigateTo(Screen.TimerSetEdit) },
    onNew = { viewModel.newTimerSet() },
    onDuplicate = { src -> viewModel.duplicateTimerSet(src) },
    onDelete = { viewModel.deleteTimerSet(it) },
    onSend = { viewModel.sendTimerSet(it) },
    onStart = { viewModel.startTimerSet(it) },
    onBack = { viewModel.navigateTo(Screen.Home) },
    modifier = modifier,
)
```

- [ ] **Step 2: Build the full debug APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "feat: wire quick timer and start callbacks into the timer sets screen"
```

---

## Task 7: On-device verification

No automated UI tests exist in this project, so verify the three behaviors on hardware.

**Files:** none (manual).

- [ ] **Step 1: Install the debug build**

Run: `./gradlew :app:installDebug` (watch reachable, app's watch configured).
Expected: app installs and launches.

- [ ] **Step 2: Verify each behavior with the watch awake (long-press ALARM first)**

- [ ] Home → **Timers**: the **Quick timer** card shows at the top with H/M/S fields defaulting to 00:03:00.
- [ ] Edit the Quick timer to a distinctive value (e.g. 00:07:07); reopen the screen — the value persisted.
- [ ] Tap the Quick timer **▶ Start on watch** → snackbar "Started quick timer on watch"; the watch's Timer face is counting down the primary value (it starts in the background — switch to the Timer face to see it).
- [ ] On a set, tap **▶ Start** → snackbar "Started '<name>' on watch"; the watch runs that set's interval sequence; the set becomes active (radio fills).
- [ ] Existing **Send to watch** (radio) still configures without starting.

- [ ] **Step 3: (No commit — verification only.)** If anything misbehaves, use
  superpowers:systematic-debugging before changing code.

---

## Task 8: Cross-repo — update the protocol reference

**Files:**
- Modify: `ollee-graphene/docs/reference/ollee-ble-protocol.md` (separate repo `~/github/ollee-graphene`)

- [ ] **Step 1: Update the Timer-slots section**

In the `## Timer slots (0x26 write / 46 ack)` section, the header is currently documented as
`[00, MM, SS, 00]` with byte 3 a constant. Update it to document byte 3 as the **start/mode
selector**, captured 2026-06-10 (HCI snoop → bugreport, watch `panther`/Pixel 7):

- `byte3 = 0x00` — configure/save only (no auto-start)
- `byte3 = 0x01` — start now in interval mode (runs the 10-slot sequence)
- `byte3 = 0x02` — start now in single-timer mode (runs the header MM:SS countdown)

Also note: the header `MM:SS` **is** the official app's "Start timer from app" primary picker (the
big `HH:MM:SS` on the Set Timer screen), independent of the 10 slots — a picker-only edit changes
only `byte1/byte2`. Cite the four CRC-clean golden frames from the FreeOllee-Faces spec
`docs/superpowers/specs/2026-06-10-timer-enhancements-design.md`.

- [ ] **Step 2: Commit in the ollee-graphene repo**

```bash
cd ~/github/ollee-graphene
git add docs/reference/ollee-ble-protocol.md
git commit -m "docs: 0x26 header byte3 is the timer start/mode selector (captured 2026-06-10)"
```

> **Push policy:** per the user's workday-push rule, commit locally; do not push until the user asks
> (and not before 5 PM MT on a workday).

---

## Self-Review (completed during planning)

- **Spec coverage:** Quick timer (Tasks 1,3,5,6) · remote start interval+single (Tasks 2,3,5,6) ·
  header byte3 decode (Task 2) · active-set slots on quick-start (Task 3) · persistence (Task 1) ·
  UI placement top-of-screen (Task 5) · tests incl. golden vectors (Tasks 1–3) · cross-repo doc
  (Task 8). All spec sections map to a task.
- **Type consistency:** `TimerStartMode.{SAVE,START_INTERVAL,START_SINGLE}`, `buildTimerPacket(durations,
  headerSeconds, startMode)`, `quickTimerSeconds`, `saveQuickTimer`, `startTimerSet`, `startQuickTimer`,
  `pushTimerFrame`, and the `TimerSetsScreen` params (`onSaveQuick`, `onStartQuick`, `onStart`) are
  used identically across tasks.
- **Placeholders:** the only `TODO_REUSE_VM_BUILDER()` marker is explicitly annotated with how to
  construct the VM from the existing test — it is an instruction to copy existing code, not an
  unspecified gap.
- **Build-green caveat:** Task 5 intentionally leaves the build red until Task 6 (the screen signature
  changes before its caller). Noted in Task 5 Step 4 with the option to reorder if green-per-commit is
  required.

