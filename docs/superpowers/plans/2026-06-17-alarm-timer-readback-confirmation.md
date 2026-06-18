# Alarm & Timer Read-Back Confirmation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After an alarm re-arm or timer push, read the watch back and confirm it stored the intended value — auto-healing once before falling into the existing retry/notify ladder.

**Architecture:** Add a request→response read capability to the process-wide `WatchLink` (subscribe to the Nordic-UART notify characteristic `6e400003`, reassemble fragmented reply frames, expose `sendAndAwait`). Keep all decision logic pure and testable in `commonMain` (a frame reassembler and per-record comparators); keep the GATT wiring thin in `androidMain`. Alarm confirmation ships on already-captured `4B` data; timer confirmation is a gated second phase that first captures the `4C` reply layout on hardware.

**Tech Stack:** Kotlin Multiplatform (commonMain/androidMain), kotlinx.coroutines (`suspendCancellableCoroutine`, `withTimeout`), Android `BluetoothGatt`, `kotlin.test` + `kotlinx-coroutines-test` (`runTest`) for commonTest.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-06-17-alarm-timer-readback-confirmation-design.md`. Every task's requirements implicitly include it.
- **Read framing:** a read request is `02 <target>` with **no payload** (`OlleeProtocol.buildRawPacket(target, ByteArray(0))`). The reply arrives on notify with the **target byte shifted +0x20** (`0x2B`→`0x4B`, `0x2C`→`0x4C`); `cmd` stays `0x02`. Match replies on `frame.target == requestTarget + 0x20` **and** `frame.crcOk`.
- **Frame length:** a complete frame is `[00, LEN, AA, 55, crcHi, crcLo] + inner`; total bytes = `LEN + 2` (`LEN = inner.size + 4`). Notify fragments split at 20 bytes (`ATT_PAYLOAD`).
- **Nordic UART UUIDs:** service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`; write char `6e400002-…` (`WatchLink.CHAR_UUID`); notify char `6e400003-b5a3-f393-e0a9-e50e24dcca9e`; CCCD descriptor `00002902-0000-1000-8000-00805f9b34fb`.
- **Confirm scope:** confirm-at-write only — **no** periodic polling. Auto-heal is exactly **one** re-send; a still-failed confirm hands off to the *existing* path (`AlarmRearmRecovery.afterPush(false, attempt)` for alarms; the `pushTimerFrame` failure snackbar for timers). No new retry ladder.
- **Comparators compare only app-owned semantic fields**; bytes the watch may echo/normalize are ignored.
- **Commit footer:** end commit messages with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- **No new dependencies.** Reuse `OlleeProtocol.parseFrame`/`crc16` and the existing `WatchLink` connection model.

## File Structure

- `app/src/commonMain/.../ble/NotifyFrameReassembler.kt` — **new**, pure: accumulates notify fragments → complete `OlleeProtocol.Frame`. (Task 1)
- `app/src/commonMain/.../ble/AlarmConfirm.kt` — **new**, pure: intended alarm vs. parsed `4B` frame. (Task 2)
- `app/src/commonMain/.../ble/OlleeProtocol.kt` — **modify**: read-target constants + `readRequest(target)` helper + response-offset. (Task 2)
- `app/src/commonMain/.../ble/BleClient.kt` — **modify**: add `sendAndAwait(...)` to the interface. (Task 3)
- `app/src/androidMain/.../ble/WatchLink.kt` — **modify**: notify subscription, `onCharacteristicChanged`, `sendAndAwait`. (Task 3)
- `app/src/androidMain/.../ble/AndroidBleClient.kt` — **modify**: delegate `sendAndAwait` to `WatchLink`. (Task 3)
- `app/src/commonTest/.../fakes/Fakes.kt` — **modify**: `FakeBleClient.sendAndAwait` support. (Task 3)
- `app/src/androidMain/.../auto/AlarmRearm.kt` — **modify**: read-back confirm + one re-send in the push path. (Task 4)
- `app/src/commonMain/.../ble/TimerConfirm.kt` — **new**, pure: intended timer vs. parsed `4C` frame. (Task 5, gated on capture)
- `app/src/commonMain/.../AppViewModel.kt` — **modify**: timer-push confirmation in `pushTimerFrame`. (Task 5)
- `app/src/commonTest/.../ble/*Test.kt` — **new** unit tests per pure unit.

---

### Task 1: NotifyFrameReassembler (pure, commonMain)

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/NotifyFrameReassembler.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/NotifyFrameReassemblerTest.kt`

**Interfaces:**
- Consumes: `OlleeProtocol.parseFrame(ByteArray): OlleeProtocol.Frame?` (existing).
- Produces: `class NotifyFrameReassembler { fun offer(fragment: ByteArray): OlleeProtocol.Frame?; fun reset() }` — returns a parsed frame once a full frame has accumulated, else null.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blizzardcaron.freeolleefaces.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotifyFrameReassemblerTest {

    // Real captured 0x4B alarm read-back (20 bytes total: LEN=0x12 → 0x12+2). Single notify.
    private val alarm4B = byteArrayOf(
        0x00, 0x12, 0xAA.toByte(), 0x55, 0x36, 0x8A.toByte(),
        0x02, 0x4B, 0x01, 0x00, 0x00, 0x0D, 0x1E, 0xFE.toByte(),
        0x01, 0x05, 0xC0.toByte(), 0xFF.toByte(), 0x0F, 0xFF.toByte(),
    )

    @Test fun single_fragment_frame_emits_immediately() {
        val r = NotifyFrameReassembler()
        val frame = r.offer(alarm4B)
        assertTrue(frame != null && frame.crcOk)
        assertEquals(0x4B, frame.target)
    }

    @Test fun multi_fragment_frame_emits_only_when_complete() {
        // Synthetic 24-byte frame (LEN=0x16 → 0x16+2=24): header+inner, split [20][4].
        val inner = byteArrayOf(0x02, 0x4C) + ByteArray(16) { it.toByte() }
        val crc = OlleeProtocol.crc16(inner)
        val frameBytes = byteArrayOf(
            0x00, (inner.size + 4).toByte(), 0xAA.toByte(), 0x55,
            (crc shr 8).toByte(), (crc and 0xFF).toByte(),
        ) + inner
        val r = NotifyFrameReassembler()
        assertNull(r.offer(frameBytes.copyOfRange(0, 20)))      // first 20 bytes: incomplete
        val frame = r.offer(frameBytes.copyOfRange(20, frameBytes.size))
        assertTrue(frame != null && frame.crcOk)
        assertEquals(0x4C, frame.target)
    }

    @Test fun resets_between_frames() {
        val r = NotifyFrameReassembler()
        r.offer(alarm4B)
        assertEquals(0x4B, r.offer(alarm4B)?.target)           // second frame parses cleanly
    }

    @Test fun garbage_without_magic_is_dropped() {
        val r = NotifyFrameReassembler()
        assertNull(r.offer(byteArrayOf(0x00, 0x06, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*NotifyFrameReassemblerTest*"`
Expected: FAIL — `NotifyFrameReassembler` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.blizzardcaron.freeolleefaces.ble

/**
 * Reassembles fragmented Nordic-UART notify frames into complete [OlleeProtocol.Frame]s. The watch
 * splits a frame across 20-byte notifications; feed each fragment to [offer], which returns the
 * parsed frame once `LEN + 2` bytes have accumulated (else null). Drops a buffer whose header is not
 * a valid `00 .. AA 55` frame start, so a stray fragment can't wedge the stream.
 */
class NotifyFrameReassembler {
    private val buf = mutableListOf<Byte>()

    fun offer(fragment: ByteArray): OlleeProtocol.Frame? {
        buf.addAll(fragment.toList())
        if (buf.size >= 4 && (buf[2] != 0xAA.toByte() || buf[3] != 0x55.toByte())) {
            buf.clear(); return null                       // not a frame start — resync
        }
        if (buf.size < 2) return null
        val total = (buf[1].toInt() and 0xFF) + 2          // frame length = LEN + 2
        if (buf.size < total) return null
        val frameBytes = buf.subList(0, total).toByteArray()
        buf.clear()
        return OlleeProtocol.parseFrame(frameBytes)
    }

    fun reset() = buf.clear()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*NotifyFrameReassemblerTest*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/NotifyFrameReassembler.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/NotifyFrameReassemblerTest.kt
git commit -m "feat(ble): NotifyFrameReassembler — reassemble fragmented notify frames

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 2: Read-request helpers + AlarmConfirm comparator (pure, commonMain)

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt` (add constants + `readRequest`)
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/AlarmConfirm.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/AlarmConfirmTest.kt`

**Interfaces:**
- Consumes: `OlleeProtocol.buildRawPacket(target, payload)`, `OlleeProtocol.Frame(cmd,target,payload,crcOk)` (existing).
- Produces:
  - `OlleeProtocol.TARGET_GET_ALARM = 0x2B`, `OlleeProtocol.TARGET_GET_TIMER = 0x2C`, `OlleeProtocol.RESPONSE_TARGET_OFFSET = 0x20`.
  - `OlleeProtocol.readRequest(target: Int): ByteArray` — the `02 <target>` request frame.
  - `AlarmConfirm.matches(enabled: Boolean, hour: Int, minute: Int, chimeIndex: Int, frame: OlleeProtocol.Frame): Boolean`.

**Read-back `4B` payload layout** (the `0x25` alarm record **minus** the play-now byte), confirmed by the captured `…024B 01 00 00 0D 1E FE 01 05 C0 FF 0F FF`:
`[0]=enable [1]=hourlyChime [2]=snooze [3]=hour [4]=minute [5]=dayMask [6]=chime [7]=snoozeMin [8..10]=hourMask [11]=FF`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.blizzardcaron.freeolleefaces.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmConfirmTest {

    // Parsed 0x4B record: enabled, 13:30, chime 0x01.
    private fun frame(payload: ByteArray, crcOk: Boolean = true) =
        OlleeProtocol.Frame(cmd = 0x02, target = 0x4B, payload = payload, crcOk = crcOk)

    private val armed1330 = byteArrayOf(
        0x01, 0x00, 0x00, 0x0D, 0x1E, 0xFE.toByte(), 0x01, 0x05,
        0xC0.toByte(), 0xFF.toByte(), 0x0F, 0xFF.toByte(),
    )

    @Test fun matches_when_hour_minute_chime_and_enable_agree() {
        assertTrue(AlarmConfirm.matches(enabled = true, hour = 13, minute = 30, chimeIndex = 1, frame = frame(armed1330)))
    }

    @Test fun mismatch_on_minute() {
        assertFalse(AlarmConfirm.matches(enabled = true, hour = 13, minute = 31, chimeIndex = 1, frame = frame(armed1330)))
    }

    @Test fun mismatch_on_chime() {
        assertFalse(AlarmConfirm.matches(enabled = true, hour = 13, minute = 30, chimeIndex = 2, frame = frame(armed1330)))
    }

    @Test fun disarm_checks_only_enable_byte() {
        val disarmed = armed1330.copyOf().also { it[0] = 0x00 }
        // When disarming, hour/minute/chime are don't-cares — only the enable byte must be 0.
        assertTrue(AlarmConfirm.matches(enabled = false, hour = 0, minute = 0, chimeIndex = 0, frame = frame(disarmed)))
        assertFalse(AlarmConfirm.matches(enabled = false, hour = 0, minute = 0, chimeIndex = 0, frame = frame(armed1330)))
    }

    @Test fun bad_crc_never_matches() {
        assertFalse(AlarmConfirm.matches(enabled = true, hour = 13, minute = 30, chimeIndex = 1, frame = frame(armed1330, crcOk = false)))
    }

    @Test fun short_payload_never_matches() {
        assertFalse(AlarmConfirm.matches(enabled = true, hour = 13, minute = 30, chimeIndex = 1, frame = frame(byteArrayOf(0x01, 0x00))))
    }

    @Test fun readRequest_builds_02_2B_with_no_payload() {
        val req = OlleeProtocol.readRequest(OlleeProtocol.TARGET_GET_ALARM)
        // 00 06 AA 55 crcHi crcLo 02 2B
        assertTrue(req.size == 8 && req[6] == 0x02.toByte() && req[7] == 0x2B.toByte())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*AlarmConfirmTest*"`
Expected: FAIL — `AlarmConfirm` / `readRequest` / constants unresolved.

- [ ] **Step 3a: Add read helpers to `OlleeProtocol`**

Add inside `object OlleeProtocol`, next to the existing target constants:

```kotlin
    /** Read-request targets and the reply-target shift (`02 <t>` → reply target `t + 0x20`). */
    const val TARGET_GET_ALARM = 0x2B
    const val TARGET_GET_TIMER = 0x2C
    const val RESPONSE_TARGET_OFFSET = 0x20

    /** The `02 <target>` read-request frame (no payload). Reply arrives with target `+0x20`. */
    fun readRequest(target: Int): ByteArray = buildRawPacket(target, ByteArray(0))
```

- [ ] **Step 3b: Write `AlarmConfirm`**

```kotlin
package com.blizzardcaron.freeolleefaces.ble

/**
 * Confirms a parsed `0x4B` alarm read-back holds the alarm the app intended. Compares only the
 * app-owned semantic fields (enable, hour, minute, chime); snooze/day-mask/hour-mask bytes the
 * watch echoes or normalizes are ignored. A disarm push (`enabled = false`) is confirmed by the
 * enable byte alone — the watch keeps the old hour/minute behind a cleared enable flag.
 */
object AlarmConfirm {
    fun matches(enabled: Boolean, hour: Int, minute: Int, chimeIndex: Int, frame: OlleeProtocol.Frame): Boolean {
        if (!frame.crcOk) return false
        if (frame.target != OlleeProtocol.TARGET_GET_ALARM + OlleeProtocol.RESPONSE_TARGET_OFFSET) return false
        val p = frame.payload
        if (p.size < 7) return false
        val enableByte = p[0].toInt() and 0xFF
        if (!enabled) return enableByte == 0
        return enableByte == 1 &&
            (p[3].toInt() and 0xFF) == hour &&
            (p[4].toInt() and 0xFF) == minute &&
            (p[6].toInt() and 0xFF) == chimeIndex
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*AlarmConfirmTest*"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/OlleeProtocol.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/AlarmConfirm.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/AlarmConfirmTest.kt
git commit -m "feat(ble): read-request helpers + AlarmConfirm comparator

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 3: `sendAndAwait` read primitive (BleClient + WatchLink + fake)

The one foundational GATT change. `WatchLink` rides the held link when it is connected to the
address (foreground timer push), else opens a one-shot connection for the round-trip (background
alarm re-arm). Both subscribe to notify `6e400003`, reassemble the reply with
`NotifyFrameReassembler`, and resume on the frame whose `target == expectedTarget`. The real GATT
path is **device-verified** (Android BLE can't be unit-tested); the pure pieces it composes were
covered in Tasks 1–2, and the fake makes the consumer wiring (Tasks 4–5) unit-testable.

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/BleClient.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ble/WatchLink.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ble/AndroidBleClient.kt`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/fakes/Fakes.kt`

**Interfaces:**
- Consumes: `NotifyFrameReassembler` (Task 1), `OlleeProtocol.Frame`/`readRequest` (Task 2), existing `WatchLink.lock`/`send`/`writeChunk`/`chunksOf`/`openHeld`.
- Produces: `suspend fun BleClient.sendAndAwait(deviceAddress: String, requestPacket: ByteArray, expectedTarget: Int, timeoutMs: Long = 5_000L): Result<OlleeProtocol.Frame>` — implemented by `AndroidBleClient`→`WatchLink.sendAndAwait(...)` and `FakeBleClient`.

- [ ] **Step 1: Add the interface method (`BleClient.kt`)**

Append to `interface BleClient`:

```kotlin
    /**
     * Sends a `02 <target>` read request and awaits the matching notify reply (its target is the
     * request target + [OlleeProtocol.RESPONSE_TARGET_OFFSET]). Returns the parsed reply frame, or
     * [Result.failure] on timeout, link loss, or no matching reply.
     */
    suspend fun sendAndAwait(
        deviceAddress: String,
        requestPacket: ByteArray,
        expectedTarget: Int,
        timeoutMs: Long = 5_000L,
    ): Result<OlleeProtocol.Frame>
```

- [ ] **Step 2: Extend `FakeBleClient` (`Fakes.kt`) and write a wiring test**

Add the import `import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol` and, inside `FakeBleClient`:

```kotlin
    /** Reply returned by [sendAndAwait]; default is a failure so tests must opt into a reply. */
    var awaitResult: Result<OlleeProtocol.Frame> =
        Result.failure(IllegalStateException("no reply configured"))

    override suspend fun sendAndAwait(
        deviceAddress: String,
        requestPacket: ByteArray,
        expectedTarget: Int,
        timeoutMs: Long,
    ): Result<OlleeProtocol.Frame> {
        gate?.await()
        callLog += "ble.sendAndAwait($deviceAddress,target=$expectedTarget)"
        sentPackets += requestPacket
        return awaitResult
    }
```

Test that a configured reply flows through `AlarmConfirm` (`app/src/commonTest/.../ble/SendAndAwaitWiringTest.kt`):

```kotlin
package com.blizzardcaron.freeolleefaces.ble

import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SendAndAwaitWiringTest {
    @Test fun fake_reply_confirms_via_AlarmConfirm() = runTest {
        val fake = FakeBleClient().apply {
            awaitResult = Result.success(
                OlleeProtocol.Frame(
                    cmd = 0x02, target = 0x4B, crcOk = true,
                    payload = byteArrayOf(0x01, 0x00, 0x00, 0x0D, 0x1E, 0xFE.toByte(), 0x01),
                ),
            )
        }
        val reply = fake.sendAndAwait("AA:BB", OlleeProtocol.readRequest(OlleeProtocol.TARGET_GET_ALARM), 0x4B)
        assertTrue(reply.isSuccess)
        assertTrue(AlarmConfirm.matches(true, 13, 30, 1, reply.getOrThrow()))
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "*SendAndAwaitWiringTest*"` — Expected: FAIL (method missing) → after Steps 1–2, PASS.

- [ ] **Step 3: Implement `WatchLink.sendAndAwait` + notify plumbing**

Add imports `import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol` is same package (none needed). Add constants near `CHAR_UUID`:

```kotlin
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val READ_TIMEOUT_MS = 5_000L
```

In `HeldCallback`, add fields:

```kotlin
        @Volatile var notifyChar: BluetoothGattCharacteristic? = null
        @Volatile var awaitTarget: Int? = null
        @Volatile var awaitCont: CancellableContinuation<Result<OlleeProtocol.Frame>>? = null
        val reassembler = NotifyFrameReassembler()
```

In `HeldCallback.onServicesDiscovered`, after resolving the write `char` and before `cc?.resume(true)`, also resolve + subscribe notify (failure to subscribe is non-fatal for writes, so log and continue):

```kotlin
            notifyChar = g.getService(WatchLink.SERVICE_UUID)?.getCharacteristic(WatchLink.NOTIFY_CHAR_UUID)
            notifyChar?.let { nc ->
                g.setCharacteristicNotification(nc, true)
                nc.getDescriptor(WatchLink.CCCD_UUID)?.let { d ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION") run {
                            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(d)
                        }
                    }
                }
            }
```

In `HeldCallback`, add both `onCharacteristicChanged` overloads (Tiramisu split) routing to a shared handler:

```kotlin
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotify(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            handleNotify(characteristic.uuid, value)
        }

        private fun handleNotify(uuid: UUID, value: ByteArray?) {
            if (uuid != WatchLink.NOTIFY_CHAR_UUID || value == null) return
            val frame = reassembler.offer(value) ?: return
            val want = awaitTarget ?: return
            if (frame.target == want) {
                val c = awaitCont
                awaitCont = null; awaitTarget = null
                c?.let { if (it.isActive) it.resume(Result.success(frame)) }
            }
        }
```

Also resume a pending read with failure when the link drops — in `onConnectionStateChange`'s `STATE_DISCONNECTED` branch, alongside the existing `writeCont` cleanup:

```kotlin
                awaitCont?.let {
                    if (it.isActive) it.resume(Result.failure(IllegalStateException("link dropped: status=$status")))
                }
                awaitCont = null; awaitTarget = null
```

Add the `WatchLink.sendAndAwait` entry point (mirrors `send`): ensure a held link to the address, then write the request and await the reply under `withTimeout`. Reuse `openHeld`/`closeHeldLocked`:

```kotlin
    /**
     * Write [requestPacket] and await the notify reply whose target == [expectedTarget]. Ensures a
     * connection (reusing the held link when it is already Connected to [address], else opening one),
     * subscribes to notify during service discovery, and resumes on the first matching frame.
     */
    suspend fun sendAndAwait(
        context: Context, address: String, requestPacket: ByteArray, expectedTarget: Int,
        timeoutMs: Long = READ_TIMEOUT_MS,
    ): Result<OlleeProtocol.Frame> = withContext(Dispatchers.IO) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.i("OLLEE_BLE", "FreeOllee TX(read) $address ${requestPacket.toHex()} expect=$expectedTarget")
        }
        lock.withLock {
            val connected = heldGatt != null && heldAddress == address &&
                _status.value == ConnectionStatus.Connected
            if (!connected) {
                closeHeldLocked()
                _status.value = ConnectionStatus.Connecting
                val ok = runCatching { withTimeout(CONNECT_TIMEOUT_MS) { openHeld(context, address) } }
                    .getOrDefault(false)
                if (!ok) { closeHeldLocked(); _status.value = ConnectionStatus.NotReachable
                    return@withLock Result.failure(IllegalStateException("connect failed")) }
                _status.value = ConnectionStatus.Connected
            }
            val g = heldGatt; val cb = heldCallback; val char = cb?.writeChar
            if (g == null || cb == null || char == null) {
                return@withLock Result.failure(IllegalStateException("no link"))
            }
            runCatching {
                withTimeout(timeoutMs) {
                    suspendCancellableCoroutine<Result<OlleeProtocol.Frame>> { cont ->
                        cb.reassembler.reset()
                        cb.awaitTarget = expectedTarget
                        cb.awaitCont = cont
                        cb.writeChunks = chunksOf(requestPacket)
                        cb.writeIndex = 0
                        cb.writeCont = null   // request write ack is irrelevant; we wait for the notify
                        if (!writeChunk(g, char, cb.writeChunks[0])) {
                            cb.awaitCont = null; cb.awaitTarget = null
                            cont.resume(Result.failure(IllegalStateException("write returned false")))
                        }
                        cont.invokeOnCancellation { cb.awaitCont = null; cb.awaitTarget = null }
                    }
                }
            }.getOrElse { Result.failure(it) }
        }
    }
```

Add imports to `WatchLink.kt`: `import android.bluetooth.BluetoothGattDescriptor`. (`OlleeProtocol`, `NotifyFrameReassembler` are same-package.)

> **Note on multi-chunk requests:** read requests are 8 bytes (single chunk), so the request-side
> `onCharacteristicWrite` chunk-advance is not exercised by reads; leaving `writeCont = null` is
> intentional (we await the notify, not the write ack). Do **not** route read replies through
> `writeCont`.

- [ ] **Step 4: Delegate from `AndroidBleClient`**

```kotlin
    override suspend fun sendAndAwait(
        deviceAddress: String, requestPacket: ByteArray, expectedTarget: Int, timeoutMs: Long,
    ): Result<OlleeProtocol.Frame> =
        WatchLink.sendAndAwait(context, deviceAddress, requestPacket, expectedTarget, timeoutMs)
```

- [ ] **Step 5: Build + unit tests + on-device verification**

```bash
./gradlew :app:testDebugUnitTest --tests "*SendAndAwaitWiringTest*" --tests "*AlarmConfirmTest*" --tests "*NotifyFrameReassemblerTest*"
./gradlew :app:assembleDebug
```
Expected: tests PASS; debug APK builds.

**On-device (watch `panther`, debug build, `adb logcat -s OLLEE_BLE`):** with a watch paired, trigger a read of the alarm record and confirm a `4B` reply is logged and parsed. Use a temporary debug entry point or the Task 4 wiring; confirm `sendAndAwait(TARGET_GET_ALARM…)` returns a success frame with `target == 0x4B`. Record the observed reply bytes in the commit message.

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/BleClient.kt \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ble/WatchLink.kt \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ble/AndroidBleClient.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/fakes/Fakes.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/SendAndAwaitWiringTest.kt
git commit -m "feat(ble): WatchLink.sendAndAwait — notify subscription + read round-trip

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 4: Wire alarm re-arm confirmation

The decision/heal loop lives in a pure `commonMain` orchestrator (`AlarmReadback.confirm`) testable
with `FakeBleClient`; `AlarmRearm` (androidMain `object`) just calls it and feeds the boolean to the
**existing** `AlarmRearmRecovery.afterPush`.

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/AlarmReadback.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/AlarmReadbackTest.kt`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/fakes/Fakes.kt` (per-call reply queue)
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AlarmRearm.kt`

**Interfaces:**
- Consumes: `BleClient.sendAndAwait`/`sendPacket` (Task 3), `OlleeProtocol.readRequest`/constants (Task 2), `AlarmConfirm.matches` (Task 2).
- Produces: `suspend fun AlarmReadback.confirm(ble: BleClient, address: String, packet: ByteArray, enabled: Boolean, hour: Int, minute: Int, chimeIndex: Int): Boolean`.

- [ ] **Step 1: Add a per-call reply queue to `FakeBleClient`**

Inside `FakeBleClient`, add (so a test can vary the reply between the two reads):

```kotlin
    /** Replies consumed in order by successive [sendAndAwait] calls; falls back to [awaitResult]. */
    val awaitResults: ArrayDeque<Result<OlleeProtocol.Frame>> = ArrayDeque()
```

and change `sendAndAwait`'s `return` to:

```kotlin
        return if (awaitResults.isNotEmpty()) awaitResults.removeFirst() else awaitResult
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.blizzardcaron.freeolleefaces.ble

import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlarmReadbackTest {
    private fun reply(enable: Int, h: Int, m: Int, ch: Int) = Result.success(
        OlleeProtocol.Frame(0x02, 0x4B, byteArrayOf(enable.toByte(), 0, 0, h.toByte(), m.toByte(), 0xFE.toByte(), ch.toByte()), true),
    )
    private val packet = byteArrayOf(0x00, 0x01) // opaque to the orchestrator

    @Test fun confirmed_on_first_read_does_not_resend() = runTest {
        val ble = FakeBleClient().apply { awaitResults.addLast(reply(1, 13, 30, 1)) }
        assertTrue(AlarmReadback.confirm(ble, "AA", packet, enabled = true, hour = 13, minute = 30, chimeIndex = 1))
        assertEquals(0, ble.sentPackets.count { it.contentEquals(packet) }) // no heal re-send
    }

    @Test fun heals_once_then_confirms() = runTest {
        val ble = FakeBleClient().apply {
            awaitResults.addLast(reply(1, 9, 0, 1))   // first read: wrong (9:00)
            awaitResults.addLast(reply(1, 13, 30, 1)) // after heal: correct
        }
        assertTrue(AlarmReadback.confirm(ble, "AA", packet, enabled = true, hour = 13, minute = 30, chimeIndex = 1))
        assertEquals(1, ble.sentPackets.count { it.contentEquals(packet) }) // exactly one heal re-send
    }

    @Test fun stays_false_after_one_failed_heal() = runTest {
        val ble = FakeBleClient().apply {
            awaitResults.addLast(reply(1, 9, 0, 1))
            awaitResults.addLast(reply(1, 9, 0, 1))
        }
        assertFalse(AlarmReadback.confirm(ble, "AA", packet, enabled = true, hour = 13, minute = 30, chimeIndex = 1))
        assertEquals(1, ble.sentPackets.count { it.contentEquals(packet) }) // healed exactly once, no loop
    }

    @Test fun read_failure_is_treated_as_mismatch() = runTest {
        val ble = FakeBleClient() // awaitResult defaults to failure
        assertFalse(AlarmReadback.confirm(ble, "AA", packet, enabled = true, hour = 13, minute = 30, chimeIndex = 1))
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "*AlarmReadbackTest*"` — Expected: FAIL (`AlarmReadback` unresolved).

- [ ] **Step 3: Implement `AlarmReadback`**

```kotlin
package com.blizzardcaron.freeolleefaces.ble

/**
 * Read-back confirmation for the single watch alarm: read `0x2B`→`0x4B`, compare via [AlarmConfirm],
 * and on mismatch (or read failure) re-send [packet] exactly once and re-read. Returns true only if
 * the watch is confirmed holding the intended alarm; a false return is the caller's signal to route
 * into the existing failure path. No looping — at most one heal.
 */
object AlarmReadback {
    suspend fun confirm(
        ble: BleClient, address: String, packet: ByteArray,
        enabled: Boolean, hour: Int, minute: Int, chimeIndex: Int,
    ): Boolean {
        suspend fun reads(): Boolean {
            val reply = ble.sendAndAwait(
                address,
                OlleeProtocol.readRequest(OlleeProtocol.TARGET_GET_ALARM),
                OlleeProtocol.TARGET_GET_ALARM + OlleeProtocol.RESPONSE_TARGET_OFFSET,
            )
            return reply.getOrNull()?.let { AlarmConfirm.matches(enabled, hour, minute, chimeIndex, it) } ?: false
        }
        if (reads()) return true
        ble.sendPacket(address, packet)   // heal once
        return reads()
    }
}
```

Run the test again — Expected: PASS (4 tests).

- [ ] **Step 4: Wire into `AlarmRearm`**

Add imports to `AlarmRearm.kt`:

```kotlin
import com.blizzardcaron.freeolleefaces.ble.AlarmReadback
```

Replace the push block (around `AlarmRearm.kt:126-133`) — currently:

```kotlin
                    val result = AndroidBleClient(ctx).sendPacket(address, AlarmSchedule.packetFor(latest))
                    Log.i(
                        TAG,
                        if (result.isSuccess) "push OK (${if (latest != null) "armed ${latest.dateTime}" else "disarm"}) [attempt $attempt]"
                        else "push FAIL ${result.exceptionOrNull()?.message} [attempt $attempt]",
                    )
                    val action = AlarmRearmRecovery.afterPush(result.isSuccess, attempt)
```

with (confirm after a successful write; a failed confirm — after the one heal — counts as a failed push):

```kotlin
                    val ble = AndroidBleClient(ctx)
                    val packet = AlarmSchedule.packetFor(latest)
                    val sent = ble.sendPacket(address, packet)
                    val confirmed = sent.isSuccess && AlarmReadback.confirm(
                        ble, address, packet,
                        enabled = latest != null,
                        hour = latest?.hour ?: 0, minute = latest?.minute ?: 0, chimeIndex = latest?.chimeIndex ?: 0,
                    )
                    Log.i(
                        TAG,
                        if (confirmed) "push+confirm OK (${if (latest != null) "armed ${latest.dateTime}" else "disarm"}) [attempt $attempt]"
                        else "push/confirm FAIL ${sent.exceptionOrNull()?.message ?: "read-back mismatch"} [attempt $attempt]",
                    )
                    val action = AlarmRearmRecovery.afterPush(confirmed, attempt)
```

Then in the `pushResults.tryEmit(when { … })` block immediately below, replace the two `!result.isSuccess` guards with `!confirmed`:

```kotlin
                    pushResults.tryEmit(
                        when {
                            !confirmed && action is AlarmRearmRecovery.Action.ScheduleRetry ->
                                "Alarm send failed — long-press ALARM to wake the watch (retrying automatically)"
                            !confirmed ->
                                "Alarm send failed after several tries — long-press ALARM to wake the watch, then tap Retry in the notification"
                            latest != null -> "Sent to watch — ${AlarmSchedule.formatNext(latest)}"
                            else -> "Sent to watch — alarm off"
                        },
                    )
```

- [ ] **Step 5: Build + test + on-device**

```bash
./gradlew :app:testDebugUnitTest --tests "*AlarmReadbackTest*" --tests "*AlarmConfirmTest*"
./gradlew :app:assembleDebug
```
Expected: tests PASS; APK builds.

**On-device (watch `panther`):** set an alarm, watch it re-arm, and via `adb logcat -s OLLEE_BLE ALARM_REARM` confirm a `push+confirm OK` line and a `4B` read in the OLLEE_BLE trace. Then force a mismatch path if feasible (e.g. deny the watch briefly) and confirm it logs `push/confirm FAIL` and the existing retry chain engages.

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/AlarmReadback.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/AlarmReadbackTest.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/fakes/Fakes.kt \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/auto/AlarmRearm.kt
git commit -m "feat(alarm): confirm re-arm read-back, heal once, else route to existing recovery

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task 5 (Phase 2 — gated on a hardware capture): Timer push confirmation

> **⚠️ SUPERSEDED BY THE HARDWARE CAPTURE (2026-06-17, watch `panther`).** Step A disproved the
> full-table hypothesis below: the `0x2C`→`4C` reply is only `[HH, MM, SS, runFlag]` of the **active
> countdown** (header value for SAVE/START_SINGLE, first slot for START_INTERVAL), **not** the 10-slot
> table. As shipped, `TimerConfirm` is therefore a *partial* confirmation (active value + run flag),
> and `TimerReadback` does **not** auto-heal (re-sending would restart a running timer). The code
> docstrings in `TimerConfirm.kt`/`TimerReadback.kt` are authoritative; the `decodeTimerPayload` /
> `REPLY_SLOT_BASE` / heal-once design described in Steps B–E below was the pre-capture plan and was
> not shipped. See `../reference/...` and `ollee-graphene` `0x2C` doc rows for the decode.

> **Gate:** Step A captures the unobserved `4C` reply layout on hardware **before** any comparator
> code. The comparator/tests are written against the *captured* bytes; the layout below is the
> hypothesis (the `0x26` write payload minus the transient start-mode byte) to be confirmed or
> corrected by Step A. Tasks 1–4 (the alarm half) ship independently of this task.

**Files:**
- Modify: `../ollee-graphene/docs/reference/ollee-ble-protocol.md` + `ollee-app-code.md` (record the captured `4C` layout; resolve the doc's `0x2C` row)
- Modify: `app/src/commonMain/.../ble/OlleeProtocol.kt` (`decodeTimerPayload` helper)
- Create: `app/src/commonMain/.../ble/TimerConfirm.kt` + `TimerReadback.kt`
- Test: `app/src/commonTest/.../ble/TimerConfirmTest.kt`
- Modify: `app/src/commonMain/.../AppViewModel.kt` (`pushTimerFrame` confirmation)
- Modify: `app/src/commonTest/.../AppViewModelTest.kt` (timer-confirm snackbar)

**Interfaces:**
- Produces: `OlleeProtocol.decodeTimerPayload(packet: ByteArray): Pair<IntArray, List<Int>>?` (header `[HH,MM,SS]`, ten durations); `TimerConfirm.matches(writePacket: ByteArray, frame: OlleeProtocol.Frame): Boolean`; `suspend fun TimerReadback.confirm(ble: BleClient, address: String, writePacket: ByteArray): Boolean`.

- [ ] **Step A: Capture `02 2C` → `4C` on watch `panther`** (the one RE step)

With a debug build and `adb logcat -s OLLEE_BLE`, issue a timer read against a watch holding a known
set (e.g. push ten distinct slot durations via the app, then read `0x2C`). Use a temporary debug
button or a unit-test-only harness that calls `WatchLink.sendAndAwait(ctx, addr, OlleeProtocol.readRequest(0x2C), 0x4C)`.
Record the raw `4C` reply bytes and decode the offsets of: the header `HH:MM:SS`, any status/mode
byte, and the ten little-endian uint32 durations. **Write the confirmed layout into
`../ollee-graphene/docs/reference/ollee-ble-protocol.md` (resolve the `0x2C` "❓" row) and the
`0x2C`/`4C` line in `ollee-app-code.md`.** Capture the exact bytes for the test fixture below.

- [ ] **Step B: Write the failing comparator test** (fill `captured4C` from Step A)

```kotlin
package com.blizzardcaron.freeolleefaces.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimerConfirmTest {
    // Build a 0x26 write for header 00:01:40 and ten ascending-minute slots.
    private val durations = (1..10).map { it * 60 }
    private val writePacket = OlleeProtocol.buildTimerPacket(durations, headerSeconds = 100,
        startMode = OlleeProtocol.TimerStartMode.SAVE)

    // From Step A: the real 4C reply for that same state. REPLACE with captured bytes.
    private val captured4C: ByteArray = TODO("paste the 4C reply bytes captured in Step A")

    @Test fun matches_when_header_and_slots_agree() {
        val frame = OlleeProtocol.parseFrame(captured4C)!!
        assertTrue(TimerConfirm.matches(writePacket, frame))
    }

    @Test fun mismatch_when_a_slot_differs() {
        val frame = OlleeProtocol.parseFrame(captured4C)!!
        val otherWrite = OlleeProtocol.buildTimerPacket(durations.toMutableList().also { it[0] = 999 },
            headerSeconds = 100, startMode = OlleeProtocol.TimerStartMode.SAVE)
        assertFalse(TimerConfirm.matches(otherWrite, frame))
    }
}
```

> The `TODO(...)` here is a **capture-dependent fixture**, the single value Step A produces; it is
> filled before this test runs, not left in the committed plan output. Do not commit a `TODO`.

- [ ] **Step C: Implement `decodeTimerPayload` + `TimerConfirm`** (offsets per Step A)

`OlleeProtocol.decodeTimerPayload` (decode the persisted fields from a `0x26` write *or* a `4C`
reply payload — adjust the header/slot base offset to the captured `4C` layout):

```kotlin
    /**
     * Decodes the persisted timer fields — header [HH,MM,SS] and ten little-endian uint32 slot
     * durations — from a framed 0x26 write or 0x4C reply. Returns null if the frame is too short.
     * [slotBase] is the payload offset where the ten 4-byte words begin (4 for the 0x26 write:
     * after the [HH,MM,SS,mode] header; set to the value Step A confirmed for the 0x4C reply).
     */
    fun decodeTimerPayload(packet: ByteArray, slotBase: Int = 4): Pair<IntArray, List<Int>>? {
        val frame = parseFrame(packet) ?: return null
        val p = frame.payload
        if (p.size < slotBase + 40) return null
        val header = intArrayOf(p[0].toInt() and 0xFF, p[1].toInt() and 0xFF, p[2].toInt() and 0xFF)
        val slots = (0 until 10).map { i ->
            val o = slotBase + i * 4
            (p[o].toInt() and 0xFF) or ((p[o + 1].toInt() and 0xFF) shl 8) or
                ((p[o + 2].toInt() and 0xFF) shl 16) or ((p[o + 3].toInt() and 0xFF) shl 24)
        }
        return header to slots
    }
```

`TimerConfirm` (compare the intended write's persisted fields to the reply's; ignore the start-mode
byte, which the reply does not persist — confirm against Step A):

```kotlin
package com.blizzardcaron.freeolleefaces.ble

/**
 * Confirms a parsed `0x4C` timer read-back holds the slots + quick-timer header the app pushed via
 * a `0x26` write. Compares the header HH:MM:SS and the ten slot durations; the transient start-mode
 * selector is not compared. Reply-payload offsets follow the layout captured in Task 5 Step A
 * (pass the confirmed `0x4C` slot base to [OlleeProtocol.decodeTimerPayload]).
 */
object TimerConfirm {
    /** The payload offset where the ten slot words begin in a 0x4C reply — confirmed in Step A. */
    private const val REPLY_SLOT_BASE = 4   // hypothesis: [HH,MM,SS,status]+slots; correct per capture

    fun matches(writePacket: ByteArray, frame: OlleeProtocol.Frame): Boolean {
        if (!frame.crcOk) return false
        if (frame.target != OlleeProtocol.TARGET_GET_TIMER + OlleeProtocol.RESPONSE_TARGET_OFFSET) return false
        val intended = OlleeProtocol.decodeTimerPayload(writePacket, slotBase = 4) ?: return false
        // Wrap the reply payload in a framed buffer is unnecessary — decode directly from the frame:
        val want = intended.second
        val wantHeader = intended.first
        val replyBytes = OlleeProtocol.buildRawPacket(frame.target, frame.payload) // re-frame to reuse decoder
        val got = OlleeProtocol.decodeTimerPayload(replyBytes, slotBase = REPLY_SLOT_BASE) ?: return false
        return got.first.contentEquals(wantHeader) && got.second == want
    }
}
```

`TimerReadback` (orchestrator — analogous to `AlarmReadback`, fake-testable):

```kotlin
package com.blizzardcaron.freeolleefaces.ble

/** Confirm the watch stored the timer set we pushed; re-send once on mismatch. */
object TimerReadback {
    suspend fun confirm(ble: BleClient, address: String, writePacket: ByteArray): Boolean {
        suspend fun reads(): Boolean {
            val reply = ble.sendAndAwait(
                address,
                OlleeProtocol.readRequest(OlleeProtocol.TARGET_GET_TIMER),
                OlleeProtocol.TARGET_GET_TIMER + OlleeProtocol.RESPONSE_TARGET_OFFSET,
            )
            return reply.getOrNull()?.let { TimerConfirm.matches(writePacket, it) } ?: false
        }
        if (reads()) return true
        ble.sendPacket(address, writePacket)
        return reads()
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "*TimerConfirmTest*"` — Expected: PASS once `captured4C` is filled and `REPLY_SLOT_BASE` matches Step A.

- [ ] **Step D: Wire confirmation into `pushTimerFrame`** (`AppViewModel.kt`)

In `pushTimerFrame`'s `onSuccess` branch, after `onSuccess()`, confirm and reflect it in the
snackbar (replace the single `showSnackbar(successMsg)`):

```kotlin
            result
                .onSuccess {
                    onSuccess()
                    val confirmed = TimerReadback.confirm(ble, addr, packet)
                    showSnackbar(if (confirmed) successMsg else "$successMsg — but the watch didn't confirm it")
                }
                .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
```

(`addr` is the resolved address already in scope in `pushTimerFrame`; `ble` and `packet` are in scope.)

- [ ] **Step E: ViewModel test for the confirm snackbar** (`AppViewModelTest.kt`)

Add a test that `sendTimerSet` shows the plain success message when the fake confirms, and the
"didn't confirm" message when it doesn't — set `FakeBleClient.awaitResults` to a matching vs.
mismatching `4C` reply (build the matching reply from the captured layout), then assert on the
emitted snackbar via the existing test harness pattern.

- [ ] **Step F: Build, test, on-device, commit**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
git add -A && git commit -m "feat(timer): confirm timer push via 0x2C read-back (4C layout captured on panther)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage** — every spec section maps to a task:
- Read capability (notify subscription + reassembly + `sendAndAwait`) → Tasks 1 & 3.
- `AlarmConfirm` comparator + fixtures → Task 2.
- Auto-heal once → existing path → Tasks 4 (`AlarmReadback`) & 5 (`TimerReadback`).
- Alarm wiring (`AlarmRearm`→`afterPush`) → Task 4. Timer wiring (`pushTimerFrame`) → Task 5.
- `4C` capture isolated as a gate → Task 5 Step A. Doc cross-reference → Task 5 Step A.
- Acceptance criteria 1–6 → Tasks 3, 3, 2, 4, 5, 5 respectively.

**Non-placeholder note:** the only `TODO(...)` is Task 5's `captured4C` fixture — explicitly a
value produced by Step A's hardware capture and filled before the test is committed. Everything
else is complete code.

**Type consistency:** `sendAndAwait(deviceAddress, requestPacket, expectedTarget, timeoutMs): Result<OlleeProtocol.Frame>`
is identical across `BleClient`, `AndroidBleClient`, `FakeBleClient`, and `WatchLink` (the latter
prefixed with `context, address`). `AlarmConfirm.matches(enabled, hour, minute, chimeIndex, frame)`
and `AlarmReadback.confirm(ble, address, packet, enabled, hour, minute, chimeIndex)` match their
call sites in Task 4. Response target is consistently `requestTarget + RESPONSE_TARGET_OFFSET`.

**Known seam to watch during execution:** `TimerConfirm.matches` re-frames the reply payload via
`buildRawPacket` to reuse `decodeTimerPayload`; if Step A shows the `4C` slot base ≠ 4, set
`REPLY_SLOT_BASE` accordingly (the write-side base stays 4). Confirm on hardware before trusting
the timer comparator.

