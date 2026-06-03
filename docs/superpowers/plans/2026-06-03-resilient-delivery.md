# Resilient Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make FreeOllee-Faces reliably deliver a value to the watch — fragment BLE writes longer than the ATT payload, retry a sleeping/slow radio in-client, back off across worker runs, and tell the user how to physically wake the watch.

**Architecture:** Three cooperating layers over one delivery path. (#3) `OlleeProtocol` gains general framing builders and `OlleeBleClient.writePacket` fragments any frame into ≤20-byte sequential ATT writes. (#4) Layer 1 is an in-client connect/write retry governed by a pure `BleRetryPolicy`; Layer 2 is a face-agnostic WorkManager backstop with a pure backoff schedule in `AutoUpdateSchedule`; Layer 3 rewords the failure notification to be actionable. Pure logic (framing, retry policy, backoff/budget) is unit-tested; the GATT calls are verified manually on-device.

**Tech Stack:** Kotlin, Android (BluetoothGatt), AndroidX WorkManager `CoroutineWorker`, kotlinx.coroutines, JUnit4.

---

## File Structure

**Modified — protocol & transport (#3):**
- `app/src/main/java/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt` — add `TARGET_WEEKDAYS`, `WEEKDAY_PREFIX`, `buildRawPacket`, `buildWeekdayPacket`; refactor `buildPacket(target,value)` to delegate to `buildRawPacket`.
- `app/src/test/java/com/blizzardcaron/freeolleefaces/ble/OlleeProtocolTest.kt` — port raw/weekday builder + validation tests.
- `app/src/main/java/com/blizzardcaron/freeolleefaces/ble/OlleeBleClient.kt` — fragmented `writePacket`, `sendPacket`, and a Layer-1 retry wrapper (`deliver`).

**Created — reliability (#4):**
- `app/src/main/java/com/blizzardcaron/freeolleefaces/ble/BleRetryPolicy.kt` — pure Layer-1 policy (max attempts, backoff, retryable predicate).
- `app/src/test/java/com/blizzardcaron/freeolleefaces/ble/BleRetryPolicyTest.kt` — policy tests.

**Modified — reliability (#4):**
- `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateSchedule.kt` — add `MAX_SEND_RETRIES`, `hasBackstopBudget`, `backstopDelayMs` (pure backoff: 2→5→15 min).
- `app/src/test/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduleTest.kt` — backoff/budget tests.
- `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduler.kt` — rename `sunAttempt`→`sendAttempt`, `KEY_SUN_ATTEMPT`→`KEY_SEND_ATTEMPT`.
- `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt` — `KEY_SEND_ATTEMPT`, `handleSendFailure`, restructure `runTemperature`/`runSteps`/`runSun` for the unified backstop (no double-chaining).
- `app/src/main/java/com/blizzardcaron/freeolleefaces/notify/RetryReceiver.kt` — `sendAttempt` rename at the call site.
- `app/src/main/java/com/blizzardcaron/freeolleefaces/notify/ErrorNotifier.kt` — Layer-3 actionable "wake the watch" copy for `WATCH_UNREACHABLE` / `SUN_UNREACHABLE`.

**Not ported:** `WeekdayPocActivity` (experiment-branch throwaway; never existed on `main`).

---

## Task 1: Protocol framing builders (#3 foundation)

Port the general framing primitives. Pure code, strict TDD. The captured reference frame is the
official app's weekday-table write: inner `02 34 | 00 00 7E 90 | "MOTUWETHFRSASU"`, CRC `0x7EAB`,
full frame `0018AA557EAB023400007E904D4F545557455448465253415355`.

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/ble/OlleeProtocolTest.kt`

- [ ] **Step 1: Write the failing tests**

Append these to `OlleeProtocolTest.kt`, before the final closing `}` (after the existing
`parseFrame returns null…` test). They reuse the file's existing `hex(...)` helper:

```kotlin
    // --- raw / weekday-table packets (upper-left panel foundation) ---
    //
    // The watch's upper-left letter pair renders the current day's 2-char slot from a 7-entry
    // weekday table written at target 0x34, preceded by a 4-byte 00 00 7E 90 prefix. Captured
    // from the official app: inner = 02 34 | 00 00 7E 90 | "MOTUWETHFRSASU", CRC 0x7EAB.

    @Test
    fun `buildRawPacket reproduces the captured 0x34 weekday write byte-for-byte`() {
        val payload = hex("00007E90") + "MOTUWETHFRSASU".toByteArray(Charsets.US_ASCII)
        val packet = OlleeProtocol.buildRawPacket(0x34, payload)
        assertArrayEquals(hex("0018AA557EAB023400007E904D4F545557455448465253415355"), packet)
    }

    @Test
    fun `buildWeekdayPacket from the standard abbreviations matches the captured frame`() {
        val packet = OlleeProtocol.buildWeekdayPacket(
            listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
        )
        assertArrayEquals(hex("0018AA557EAB023400007E904D4F545557455448465253415355"), packet)
    }

    @Test
    fun `buildWeekdayPacket with all TE slots produces a valid frame the watch will accept`() {
        val packet = OlleeProtocol.buildWeekdayPacket(List(7) { "TE" })
        val f = OlleeProtocol.parseFrame(packet)!!
        assertEquals(0x34, f.target)
        assertTrue(f.crcOk)
        assertArrayEquals(hex("00007E90") + "TETETETETETETE".toByteArray(Charsets.US_ASCII), f.payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildWeekdayPacket rejects a table that is not 7 slots`() {
        OlleeProtocol.buildWeekdayPacket(listOf("MO", "TU"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildWeekdayPacket rejects a slot that is not exactly 2 chars`() {
        OlleeProtocol.buildWeekdayPacket(List(7) { "X" })
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.ble.OlleeProtocolTest"`
Expected: FAIL — compile error / unresolved reference `buildRawPacket` and `buildWeekdayPacket`.

- [ ] **Step 3: Add the builders to `OlleeProtocol.kt`**

Add the new constants after the existing `TARGET_TEMPERATURE` declaration:

```kotlin
    // The weekday table. The watch's upper-left letter pair (the only BLE-writable text in the
    // upper panel) renders the current day's 2-char slot from this 7-entry table. The official
    // app writes it at 0x34 behind a 4-byte 00 00 7E 90 prefix. Foundation for a future custom
    // 2-char always-on label; no UI/face uses it yet.
    const val TARGET_WEEKDAYS = 0x34
    private val WEEKDAY_PREFIX = byteArrayOf(0x00, 0x00, 0x7E, 0x90.toByte())
```

Replace the body of `buildPacket(target, value)` so it delegates to `buildRawPacket` (its
`require` validations stay; only the framing tail changes):

```kotlin
    fun buildPacket(target: Int, value: String): ByteArray {
        require(target in 0..0xFF) { "target must be a single byte (got $target)" }
        require(value.length <= MAX_VALUE_LENGTH) {
            "value must be <= $MAX_VALUE_LENGTH chars (got ${value.length})"
        }
        require(value.all { it.code in 0..127 }) {
            "value must be ASCII (got '$value')"
        }

        return buildRawPacket(target, value.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Builds a framed packet writing arbitrary raw [payload] bytes to [target]. Unlike
     * [buildPacket] this imposes no ASCII or 6-char limit, so it can carry binary-prefixed
     * fields like the weekday table (0x34). Framing/CRC are identical.
     */
    fun buildRawPacket(target: Int, payload: ByteArray): ByteArray {
        require(target in 0..0xFF) { "target must be a single byte (got $target)" }

        val inner = byteArrayOf(0x02, target.toByte()) + payload
        val crc = crc16(inner)

        return byteArrayOf(
            0x00,
            (inner.size + 4).toByte(),
            0xaa.toByte(),
            0x55,
            (crc shr 8).toByte(),
            (crc and 0xFF).toByte()
        ) + inner
    }

    /**
     * Builds the weekday-table write (0x34). [slots] must be 7 entries of exactly 2 ASCII chars,
     * in Mon..Sun order (captured default: `MO TU WE TH FR SA SU`). The firmware shows the slot
     * matching the current date in the upper-left letter pair. Pass all-identical slots (e.g.
     * `List(7){"TE"}`) to make the panel show a fixed 2-char label regardless of weekday.
     */
    fun buildWeekdayPacket(slots: List<String>): ByteArray {
        require(slots.size == 7) { "weekday table needs 7 slots (got ${slots.size})" }
        require(slots.all { it.length == 2 && it.all { c -> c.code in 0..127 } }) {
            "each slot must be exactly 2 ASCII chars (got $slots)"
        }
        val payload = WEEKDAY_PREFIX + slots.joinToString("").toByteArray(Charsets.US_ASCII)
        return buildRawPacket(TARGET_WEEKDAYS, payload)
    }
```

Delete the old inline framing tail that previously lived in `buildPacket(target, value)` (the
`val inner = byteArrayOf(0x02, target.toByte()) + value.toByteArray(...)` block and its
`return byteArrayOf(...) + inner`) — it now lives in `buildRawPacket`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.ble.OlleeProtocolTest"`
Expected: PASS — all new tests plus the existing `buildPacket`/`parseFrame` tests (unchanged behavior).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/ble/OlleeProtocolTest.kt
git commit -m "feat(ble): general framing builders — buildRawPacket + buildWeekdayPacket"
```

---

## Task 2: Fragment long BLE writes + sendPacket (#3 foundation)

Frames longer than the 20-byte ATT payload (e.g. the 26-byte `0x34` weekday write) are rejected
as `GATT_INVALID_ATTRIBUTE_LENGTH` by the current single-write path. Split into sequential ≤20-byte
chunks; the firmware reassembles by the frame `LEN`. A ≤20-byte frame (the 14-byte nameplate) stays
a single chunk, so existing sends are byte-for-byte unchanged. The GATT path is not unit-testable;
verify on-device.

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/ble/OlleeBleClient.kt`

- [ ] **Step 1: Add the chunk-size constant**

In the `companion object`, after `CONNECT_TIMEOUT_MS`:

```kotlin
        // ATT payload for the watch's default 23-byte MTU (MTU - 3). Frames longer than this are
        // fragmented across sequential writes and reassembled by the firmware via the LEN field.
        private const val ATT_PAYLOAD = 20
```

- [ ] **Step 2: Replace `writePacket` with the fragmenting version**

Replace the entire `private suspend fun writePacket(...)` body with this. It chunks the packet and
only resumes the coroutine after the **last** chunk's `onCharacteristicWrite(GATT_SUCCESS)`:

```kotlin
    @SuppressLint("MissingPermission")
    private suspend fun writePacket(device: BluetoothDevice, packet: ByteArray) =
        suspendCancellableCoroutine<Unit> { cont ->
            var gatt: BluetoothGatt? = null

            // The watch's ATT MTU stays at the 23-byte default, so messages longer than the 20-byte
            // ATT payload must be fragmented across sequential writes; the firmware reassembles them
            // by the LEN field. Small frames (e.g. the 14-byte nameplate) are a single chunk.
            val chunks = packet.toList().chunked(ATT_PAYLOAD).map { it.toByteArray() }
            var next = 0

            fun writeChunk(g: BluetoothGatt, char: BluetoothGattCharacteristic): Boolean =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(
                        char, chunks[next], BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION") run {
                        char.value = chunks[next]
                        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        g.writeCharacteristic(char)
                    }
                }

            val callback = object : BluetoothGattCallback() {

                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        g.close()
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        cont.resumeWithException(IllegalStateException("service discovery failed: $status"))
                        g.disconnect()
                        return
                    }
                    val service = g.getService(SERVICE_UUID)
                        ?: run {
                            cont.resumeWithException(IllegalStateException("Nordic UART service not found"))
                            g.disconnect()
                            return
                        }
                    val char = service.getCharacteristic(CHAR_UUID)
                        ?: run {
                            cont.resumeWithException(IllegalStateException("RX characteristic not found"))
                            g.disconnect()
                            return
                        }
                    if (!writeChunk(g, char)) {
                        cont.resumeWithException(IllegalStateException("writeCharacteristic returned false"))
                        g.disconnect()
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (!cont.isActive) { g.disconnect(); return }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        cont.resumeWithException(IllegalStateException("write failed: $status"))
                        g.disconnect()
                        return
                    }
                    next++
                    if (next >= chunks.size) {
                        cont.resume(Unit)
                        g.disconnect()
                    } else if (!writeChunk(g, characteristic)) {
                        cont.resumeWithException(IllegalStateException("writeCharacteristic returned false"))
                        g.disconnect()
                    }
                }
            }

            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

            cont.invokeOnCancellation {
                runCatching { gatt?.disconnect() }
                runCatching { gatt?.close() }
            }
        }
```

- [ ] **Step 3: Add `sendPacket` for prebuilt frames**

After the existing `send(deviceAddress, value, target)` function, add:

```kotlin
    /**
     * Sends a fully-built [packet] (e.g. from [OlleeProtocol.buildWeekdayPacket] /
     * [OlleeProtocol.buildRawPacket]) without the ASCII/6-char nameplate framing path.
     */
    @SuppressLint("MissingPermission")
    suspend fun sendPacket(deviceAddress: String, packet: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val manager = context.getSystemService(BluetoothManager::class.java)
                ?: error("BluetoothManager unavailable")
            val device: BluetoothDevice = manager.adapter.getRemoteDevice(deviceAddress)

            withTimeout(CONNECT_TIMEOUT_MS) {
                writePacket(device, packet)
            }
        }
    }
```

- [ ] **Step 4: Verify it compiles and existing unit tests still pass**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all existing tests pass (no test exercises the GATT path).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ble/OlleeBleClient.kt
git commit -m "feat(ble): fragment long writes into sequential ATT chunks + sendPacket"
```

> **On-device verification (manual, not a unit test):** the nameplate path (single chunk) and a
> chunked `0x34` write were both already proven on-device during the experiment phase; re-confirm
> a normal Temperature/Steps send still lands after this change.

---

## Task 3: BleRetryPolicy — pure Layer-1 policy (#4)

The connect/write retry's *policy* (how many attempts, the backoff between them, what's retryable)
is a small pure object so it can be unit-tested even though the GATT calls cannot. Mirrors the
existing `weather/Retry.kt` `RetryPolicy` shape, kept self-contained in the `ble` package.

**Files:**
- Create: `app/src/main/java/com/blizzardcaron/freeolleefaces/ble/BleRetryPolicy.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/ble/BleRetryPolicyTest.kt`

- [ ] **Step 1: Write the failing test**

Create `BleRetryPolicyTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleRetryPolicyTest {

    @Test
    fun `allows three attempts`() {
        assertEquals(3, BleRetryPolicy.MAX_ATTEMPTS)
    }

    @Test
    fun `backoff is 2s before the first retry and 4s before the second`() {
        assertEquals(2_000L, BleRetryPolicy.backoffForAttempt(0))
        assertEquals(4_000L, BleRetryPolicy.backoffForAttempt(1))
    }

    @Test
    fun `backoff reuses the last value past the defined schedule`() {
        assertEquals(4_000L, BleRetryPolicy.backoffForAttempt(2))
    }

    @Test
    fun `every BLE failure is treated as retryable`() {
        assertTrue(BleRetryPolicy.isRetryable(IllegalStateException("write failed: 13")))
        assertTrue(BleRetryPolicy.isRetryable(RuntimeException("connect timed out")))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.ble.BleRetryPolicyTest"`
Expected: FAIL — unresolved reference `BleRetryPolicy`.

- [ ] **Step 3: Create `BleRetryPolicy.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.ble

/**
 * Pure Layer-1 retry policy for a single watch send. A cold connect to a sleeping/slow-advertising
 * watch often times out on the first try but succeeds seconds later, so [OlleeBleClient] reconnects
 * up to [MAX_ATTEMPTS] times with [backoffForAttempt] waits between tries. The GATT calls are not
 * unit-testable, so this policy is split out and tested on its own.
 */
object BleRetryPolicy {

    /** Total connect+write attempts before surfacing failure. */
    const val MAX_ATTEMPTS = 3

    /** Wait before the retry that follows the (0-based) failed [attempt]: 2s, then 4s. */
    private val BACKOFF_MS = listOf(2_000L, 4_000L)

    fun backoffForAttempt(attempt: Int): Long =
        BACKOFF_MS.getOrElse(attempt) { BACKOFF_MS.lastOrNull() ?: 0L }

    /**
     * Every connect/discover/write/timeout failure is retryable: a couple of extra cheap attempts
     * is preferable to a false "watch unreachable". (Kept as a predicate for symmetry with
     * `weather/Retry.kt` and to localize any future non-retryable cases.)
     */
    @Suppress("UNUSED_PARAMETER")
    fun isRetryable(error: Throwable): Boolean = true
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.ble.BleRetryPolicyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ble/BleRetryPolicy.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/ble/BleRetryPolicyTest.kt
git commit -m "feat(ble): pure BleRetryPolicy for in-client reconnect"
```

---

## Task 4: Wire Layer-1 retry into OlleeBleClient (#4)

Route both `send(...)` and `sendPacket(...)` through one private `deliver(...)` that resolves the
device once and loops connect+chunked-write per `BleRetryPolicy`. Each attempt keeps the existing
8s `withTimeout`; first success returns immediately; failure surfaces only after all attempts fail.
GATT path — verify on-device.

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/ble/OlleeBleClient.kt`

- [ ] **Step 1: Add the coroutine `delay` import**

In the import block, alongside the other `kotlinx.coroutines` imports:

```kotlin
import kotlinx.coroutines.delay
```

- [ ] **Step 2: Replace the bodies of `send(...)` and `sendPacket(...)` to delegate to `deliver`**

Change `send(deviceAddress, value, target)` so it builds the packet and calls `deliver`:

```kotlin
    @SuppressLint("MissingPermission")
    suspend fun send(deviceAddress: String, value: String, target: Int): Result<Unit> {
        val packet = OlleeProtocol.buildPacket(target, value.padEnd(OlleeProtocol.MAX_VALUE_LENGTH, ' '))
        return deliver(deviceAddress, packet)
    }
```

Change `sendPacket(deviceAddress, packet)` to call `deliver`:

```kotlin
    /**
     * Sends a fully-built [packet] (e.g. from [OlleeProtocol.buildWeekdayPacket] /
     * [OlleeProtocol.buildRawPacket]) without the ASCII/6-char nameplate framing path.
     */
    suspend fun sendPacket(deviceAddress: String, packet: ByteArray): Result<Unit> =
        deliver(deviceAddress, packet)
```

- [ ] **Step 3: Add the private `deliver` retry wrapper**

Add this private function (e.g. directly above `writePacket`). It owns the `Dispatchers.IO`
context, device resolution, and the retry loop:

```kotlin
    /**
     * Resolves the device once, then attempts connect + chunked write up to
     * [BleRetryPolicy.MAX_ATTEMPTS] times with backoff between tries (each attempt bounded by the
     * 8s [CONNECT_TIMEOUT_MS]). Returns on first success; surfaces the last error only after the
     * budget is spent.
     */
    @SuppressLint("MissingPermission")
    private suspend fun deliver(deviceAddress: String, packet: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val manager = context.getSystemService(BluetoothManager::class.java)
                    ?: error("BluetoothManager unavailable")
                val device: BluetoothDevice = manager.adapter.getRemoteDevice(deviceAddress)

                var lastError: Throwable? = null
                for (attempt in 0 until BleRetryPolicy.MAX_ATTEMPTS) {
                    val result = runCatching {
                        withTimeout(CONNECT_TIMEOUT_MS) { writePacket(device, packet) }
                    }
                    if (result.isSuccess) return@runCatching
                    val error = result.exceptionOrNull()!!
                    lastError = error
                    val isLastAttempt = attempt == BleRetryPolicy.MAX_ATTEMPTS - 1
                    if (isLastAttempt || !BleRetryPolicy.isRetryable(error)) {
                        throw error
                    }
                    delay(BleRetryPolicy.backoffForAttempt(attempt))
                }
                throw lastError ?: IllegalStateException("send failed with no attempts")
            }
        }
```

- [ ] **Step 4: Verify it compiles and existing unit tests still pass**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all existing tests pass. The two-arg `send(deviceAddress, value)`
overload (which delegates to the three-arg form) is unchanged.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/ble/OlleeBleClient.kt
git commit -m "feat(ble): in-client reconnect retry around connect + chunked write"
```

> **On-device verification (manual):** with the watch radio asleep, trigger a send and confirm a
> later attempt connects (rather than an immediate "unreachable"); a normal in-range send still
> succeeds on the first attempt.

---

## Task 5: Backstop backoff + budget — pure (#4 Layer 2)

The worker backstop's schedule (2 → 5 → 15 min) and budget (3 retries) are pure functions in
`AutoUpdateSchedule`, so the attempt-carry math is unit-tested. `attempt` is the 0-based count of
prior failed sends carried in the worker's input data.

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateSchedule.kt`
- Test: `app/src/test/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduleTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to `AutoUpdateScheduleTest.kt`, before its final closing `}`:

```kotlin
    // ----- backstop backoff + budget (Layer 2) -----

    @Test
    fun `backstop backoff is 2 then 5 then 15 minutes`() {
        assertEquals(2L * 60_000L, AutoUpdateSchedule.backstopDelayMs(0))
        assertEquals(5L * 60_000L, AutoUpdateSchedule.backstopDelayMs(1))
        assertEquals(15L * 60_000L, AutoUpdateSchedule.backstopDelayMs(2))
    }

    @Test
    fun `backstop backoff stays at 15 minutes past the schedule`() {
        assertEquals(15L * 60_000L, AutoUpdateSchedule.backstopDelayMs(3))
    }

    @Test
    fun `budget remains for the first three attempts and is then exhausted`() {
        assertTrue(AutoUpdateSchedule.hasBackstopBudget(0))
        assertTrue(AutoUpdateSchedule.hasBackstopBudget(1))
        assertTrue(AutoUpdateSchedule.hasBackstopBudget(2))
        assertFalse(AutoUpdateSchedule.hasBackstopBudget(3))
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.auto.AutoUpdateScheduleTest"`
Expected: FAIL — unresolved references `backstopDelayMs`, `hasBackstopBudget`.

- [ ] **Step 3: Add the pure helpers to `AutoUpdateSchedule.kt`**

Add inside `object AutoUpdateSchedule` (e.g. after `nextSunWake`):

```kotlin
    /** Backstop retry budget: up to this many re-tries after the first failed send. */
    const val MAX_SEND_RETRIES = 3

    /** Whether a send that failed at the (0-based) [attempt] still has backstop budget left. */
    fun hasBackstopBudget(attempt: Int): Boolean = attempt < MAX_SEND_RETRIES

    /**
     * Backoff before the backstop retry that follows the (0-based) failed [attempt]:
     * 2 min → 5 min → 15 min, then held at 15 min. Replaces SUN's old flat 15/15/15.
     */
    fun backstopDelayMs(attempt: Int): Long {
        val minutes = when (attempt) {
            0 -> 2L
            1 -> 5L
            else -> 15L
        }
        return minutes * 60_000L
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.auto.AutoUpdateScheduleTest"`
Expected: PASS — new tests plus the existing sleep-window / next-fire tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateSchedule.kt \
        app/src/test/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduleTest.kt
git commit -m "feat(auto): pure backstop backoff (2/5/15) + budget for failed sends"
```

---

## Task 6: Unify the worker backstop across all faces (#4 Layer 2)

Generalize SUN's bespoke `KEY_SUN_ATTEMPT` / `MAX_SUN_RETRIES` / flat-15min retry into one
face-agnostic mechanism that TEMPERATURE, STEPS and SUN all use: on a failed send (with budget,
while awake), re-enqueue a backstop run with backoff and **no** notification; on the same failed
send with the budget exhausted, notify and resume the normal chain. Preserve the "no double-chaining"
invariant — a failed run enqueues *either* a backstop retry *or* the normal next run, never both.

This is a mechanical rename plus a small restructure. No new unit test (the pure pieces are covered
by Task 5; the worker orchestration is integration-level and verified on-device); the gate is a
clean build with all existing tests green.

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduler.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt`
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/notify/RetryReceiver.kt`

- [ ] **Step 1: Rename the attempt key + param in `AutoUpdateScheduler.kt`**

In `enqueueNext`, change the signature and the `Data` key:

```kotlin
    /** Enqueue the single next chain run (REPLACE keeps exactly one pending). */
    fun enqueueNext(context: Context, delayMs: Long, sendAttempt: Int) {
        val data = Data.Builder()
            .putInt(AutoUpdateWorker.KEY_SEND_ATTEMPT, sendAttempt)
            .build()
        val req = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }
```

Update the two other call sites in this file: in `scheduleIntervalFace`,
`enqueueNext(ctx, delayMs, sendAttempt = 0)`; in the `reschedule` SUN branch,
`enqueueNext(ctx, 0L, sendAttempt = 0)`.

- [ ] **Step 2: Rename the call site in `RetryReceiver.kt`**

```kotlin
        AutoUpdateScheduler.enqueueNext(ctx, 0L, sendAttempt = 0)
```

- [ ] **Step 3: Rename the companion constants in `AutoUpdateWorker.kt`**

Replace the `companion object`:

```kotlin
    companion object {
        const val KEY_SEND_ATTEMPT = "send_attempt"
    }
```

(`MAX_SUN_RETRIES` is removed — the budget now lives in `AutoUpdateSchedule.MAX_SEND_RETRIES`.)

Add the import for the new schedule helpers' host if not already present — `AutoUpdateSchedule`
is in the same package, so no import is needed.

- [ ] **Step 4: Add the shared `handleSendFailure` helper to `AutoUpdateWorker.kt`**

Add this private method (e.g. above `applyHealth`). It returns `true` when a backstop retry was
scheduled, so callers know to skip the normal next-run enqueue:

```kotlin
    /**
     * A watch send failed. While budget remains, re-enqueue a backstop run with backoff and post
     * no notification (returns true — caller must NOT also enqueue the normal next run). Once the
     * budget is exhausted, surface [kind] via the notification path and return false so the caller
     * resumes the normal chain. The attempt count is carried in the worker's input data.
     */
    private fun handleSendFailure(
        ctx: Context,
        prefs: Prefs,
        kind: FailureKind,
        inSleep: Boolean,
    ): Boolean {
        val attempt = inputData.getInt(KEY_SEND_ATTEMPT, 0)
        return if (AutoUpdateSchedule.hasBackstopBudget(attempt)) {
            prefs.recordAutoSend("Retry ${attempt + 1}: watch unreachable")
            AutoUpdateScheduler.enqueueNext(
                ctx, AutoUpdateSchedule.backstopDelayMs(attempt), sendAttempt = attempt + 1,
            )
            true
        } else {
            prefs.recordAutoSend("Skipped: watch unreachable")
            applyHealth(ctx, prefs, kind, inSleep)
            false
        }
    }
```

- [ ] **Step 5: Restructure `runTemperature` for the backstop (no double-chaining)**

Track whether a backstop retry was scheduled; only enqueue the normal next interval when it was
not. Replace the body of `runTemperature` with:

```kotlin
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
        var backstopped = false
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
                            backstopped = handleSendFailure(
                                ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep,
                            )
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

        if (!backstopped) {
            val fire = AutoUpdateSchedule.nextTemperatureFire(now, prefs.updateIntervalMinutes, sleep)
            val delayMs = Duration.between(now, fire).toMillis().coerceAtLeast(0)
            AutoUpdateScheduler.enqueueNext(ctx, delayMs, sendAttempt = 0)
        }
        return Result.success()
    }
```

- [ ] **Step 6: Restructure `runSteps` the same way**

Replace the body of `runSteps` with (only the send-failure branch and the final enqueue change;
the steps-read failure branch is unchanged):

```kotlin
    private suspend fun runSteps(
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
            StepsRepository(ctx).todaySteps()
                .onSuccess { count ->
                    prefs.recordStepsFetch(count)
                    val payload = DisplayFormatter.steps(count)
                    OlleeBleClient(ctx).send(address, payload)
                        .onSuccess {
                            prefs.recordAutoSend("Sent '$payload'")
                            applyHealth(ctx, prefs, null, inSleep)
                        }
                        .onFailure {
                            backstopped = handleSendFailure(
                                ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep,
                            )
                        }
                }
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

- [ ] **Step 7: Replace SUN's bespoke retry with the shared helper**

In `runSun`, replace the `else` (send-failed) branch and the event-null enqueue. The full updated
`runSun`:

```kotlin
    private suspend fun runSun(
        ctx: Context,
        prefs: Prefs,
        lat: Double,
        lng: Double,
        address: String,
        now: ZonedDateTime,
    ): Result {
        val inSleep = inSleepNow(prefs)
        val event = SunCalc.nextEvent(now.toInstant(), lat, lng, ZoneId.systemDefault())
        if (event == null) {
            prefs.recordAutoSend("Skipped: no sun event (polar)")
            AutoUpdateScheduler.enqueueNext(ctx, Duration.ofHours(12).toMillis(), sendAttempt = 0)
            return Result.success()
        }

        val payload = DisplayFormatter.sunTime(event.kind, event.time.toLocalTime())
        val sendResult = OlleeBleClient(ctx).send(address, payload)

        if (sendResult.isSuccess) {
            prefs.recordAutoSend("Sent '$payload'")
            applyHealth(ctx, prefs, null, inSleep)
            scheduleAfterEvent(ctx, now, event.time)
        } else if (!handleSendFailure(ctx, prefs, FailureKind.SUN_UNREACHABLE, inSleep)) {
            // Budget exhausted — resume the normal sun chain after the (missed) event.
            scheduleAfterEvent(ctx, now, event.time)
        }
        return Result.success()
    }
```

- [ ] **Step 8: Fix the remaining `sunAttempt = 0` call site in `scheduleAfterEvent`**

```kotlin
    private fun scheduleAfterEvent(ctx: Context, now: ZonedDateTime, eventTime: ZonedDateTime) {
        val wake = AutoUpdateSchedule.nextSunWake(eventTime)
        val delayMs = Duration.between(now, wake).toMillis().coerceAtLeast(0)
        AutoUpdateScheduler.enqueueNext(ctx, delayMs, sendAttempt = 0)
    }
```

- [ ] **Step 9: Verify the whole module compiles and all tests pass**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; no remaining references to `KEY_SUN_ATTEMPT`, `MAX_SUN_RETRIES`, or
`sunAttempt`. Confirm with:

```bash
git grep -nE "sunAttempt|KEY_SUN_ATTEMPT|MAX_SUN_RETRIES" app/src && echo "FOUND — fix before commit" || echo "clean"
```
Expected: `clean`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateScheduler.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/auto/AutoUpdateWorker.kt \
        app/src/main/java/com/blizzardcaron/freeolleefaces/notify/RetryReceiver.kt
git commit -m "feat(auto): unified watch-send backstop with backoff across all faces"
```

> **Sleep handling:** TEMPERATURE/STEPS never reach `handleSendFailure` while asleep (the send is
> skipped under `!inSleep`), so no backstop retries fire during sleep. SUN keeps its
> today-identical behavior of retrying regardless of the window; the notification itself is still
> sleep-suppressed by `NotifyDecision`.

---

## Task 7: Actionable "wake the watch" notification (#4 Layer 3)

The only way the phone influences a sleeping radio is a physical wake on the watch (long-press
ALARM), so the failure copy — which now only appears after the Layer-2 budget is exhausted — should
say exactly that. The existing **Retry** action (`RetryReceiver`) is unchanged. `textFor` is a
private mapping (no unit test exercises the copy); the gate is a clean build.

**Files:**
- Modify: `app/src/main/java/com/blizzardcaron/freeolleefaces/notify/ErrorNotifier.kt`

- [ ] **Step 1: Reword the `WATCH_UNREACHABLE` and `SUN_UNREACHABLE` text**

In `private fun textFor(kind: FailureKind)`, replace the two unreachable lines:

```kotlin
        FailureKind.WATCH_UNREACHABLE -> "Couldn't reach your watch after several tries. Long-press the ALARM button to wake its Bluetooth, then tap Retry."
        FailureKind.SUN_UNREACHABLE -> "Couldn't deliver the next sunrise/sunset after several tries. Long-press the ALARM button to wake your watch, then tap Retry."
```

Leave `WEATHER_FETCH_FAILED`, `SETUP_INCOMPLETE`, and `HEALTH_UNAVAILABLE` text unchanged, and
leave all `titleFor(...)` titles unchanged.

- [ ] **Step 2: Verify it compiles and all tests pass**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass (no test asserts on this copy).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/blizzardcaron/freeolleefaces/notify/ErrorNotifier.kt
git commit -m "feat(notify): actionable wake-the-watch copy for unreachable failures"
```

---

## Final verification

- [ ] **Step 1: Full unit-test run**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all suites green (`OlleeProtocolTest`, `BleRetryPolicyTest`,
`AutoUpdateScheduleTest`, plus all pre-existing suites).

- [ ] **Step 2: Release-variant compile (catches variant-only breakage)**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: On-device smoke (manual)**

Install the debug build and confirm, in order:
1. A normal in-range Temperature/Steps send still lands on the watch (single-chunk path intact).
2. With the watch asleep, a send retries in-client and either connects or, after the backstop
   budget, posts the reworded "Long-press the ALARM button…" notification with a working **Retry**.
3. Tapping **Retry** after physically waking the watch delivers the value and clears the notification.

---

## Notes for the executor

- **Scope guard:** `buildWeekdayPacket` / `sendPacket` land as tested-but-dormant plumbing — do
  **not** add any UI, face, or setting that writes the weekday panel in this plan (deferred
  non-goal). No MTU negotiation and no scan-assisted connect either.
- **Do not reference the `ollee-graphene` repo from app code or tests.** The protocol facts needed
  (framing, CRC, the `0x34` prefix, the captured frame bytes) are reproduced inline in this plan and
  in code comments; keep it that way.
- **Branch:** all work lands on `feat/resilient-delivery` (already created off `main`, holding the
  design doc). Finish via the `superpowers:finishing-a-development-branch` skill.
