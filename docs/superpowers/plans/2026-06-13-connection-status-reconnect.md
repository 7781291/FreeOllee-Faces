# Live Connection Status + Reconnect Button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a live watch connection-status indicator and a reconnect button to FreeOllee-Faces, backed by a foreground-only persistent GATT link that all sends share.

**Architecture:** A process-wide `WatchLink` singleton (androidMain) owns one optional held `BluetoothGatt`; every send funnels through it and rides the held link when it's up, else falls back to one-shot connect/write/disconnect — serialized by a write `Mutex`. `AndroidBleClient` becomes a thin delegate, so existing background Workers are unchanged. The `AppViewModel` drives connect on foreground / disconnect on background through a `WatchConnection` view of the singleton, mirrors its `StateFlow<ConnectionStatus>` into `HomeState`, and a header chip-button renders the status and triggers reconnect. A `NotReachable` state shows wake instructions (the phone cannot wake a sleeping watch radio).

**Tech Stack:** Kotlin Multiplatform (Android target), Compose Multiplatform, Material3, kotlinx.coroutines (`StateFlow`, `Mutex`), kotlinx-coroutines-test, JUnit (`kotlin.test`).

**Spec:** [`docs/superpowers/specs/2026-06-13-connection-status-reconnect-design.md`](../specs/2026-06-13-connection-status-reconnect-design.md)

**How tests run:** `./gradlew :app:testDebugUnitTest` (single class: append `--tests "FQN"`). UI-only / androidMain tasks compile-check with `./gradlew :app:assembleDebug`.

---

## File Structure

**Created:**

- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/ConnectionStatus.kt` — the 4-state status enum + pure presentation helpers (`ConnectionChip`, `connectionChip(status)`, `wakeHint(status)`).
- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/WatchConnection.kt` — the `WatchConnection` interface + `NoopWatchConnection` default.
- `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ble/WatchLink.kt` — process-wide singleton: held GATT, write `Mutex`, `StateFlow<ConnectionStatus>`, `connectHeld`/`disconnectHeld`/`send`, plus the shared chunked-write + one-shot logic moved out of `AndroidBleClient`.
- `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ble/AndroidWatchConnection.kt` — `WatchConnection` impl delegating to the `WatchLink` singleton.
- `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/ConnectionStatusTest.kt` — tests for the pure presentation helpers.

**Modified:**

- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeState.kt` — add `connectionStatus` field.
- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt` — add `onReconnect` to `HomeCallbacks` (default `{}` so intermediate builds compile).
- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt` — add `watchConnection` param (default `NoopWatchConnection`), `onForeground`/`onBackground`/`onReconnect`, status mirroring, connect-on-watch-pick, `connectionStatus` in `initialState`.
- `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ble/AndroidBleClient.kt` — delegate `deliver` to `WatchLink.send`; remove the now-shared connect/write internals.
- `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt` — header chip-button + wake-hint row.
- `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt` — construct `AndroidWatchConnection`, pass to `AppViewModel`, wire `ON_START`/`ON_STOP` → `onForeground`/`onBackground`, provide `onReconnect`.
- `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/fakes/Fakes.kt` — add `FakeWatchConnection`.
- `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/AppViewModelTest.kt` — add connection-status / lifecycle / reconnect tests.

**Task dependency note:** Defaults (`HomeState.connectionStatus`, `HomeCallbacks.onReconnect = {}`, `AppViewModel.watchConnection = NoopWatchConnection`) keep every intermediate task compiling. The androidMain BLE layer (Task 4) lands before the UI/MainActivity wiring (Tasks 5–6) so `AndroidWatchConnection` exists when referenced.

---

## Task 1: `ConnectionStatus` + pure presentation helpers

The status enum and the pure functions that map a status to a chip label / clickability /
spinner and to the optional wake-instruction hint. Keeping presentation logic pure makes it
unit-testable; the Compose composable (Task 5) just consumes these.

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/ConnectionStatus.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/ConnectionStatusTest.kt`

- [ ] **Step 1: Write the failing test**

Create `ConnectionStatusTest.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionStatusTest {

    @Test
    fun chip_connected_isNotClickable_noSpinner() {
        val chip = connectionChip(ConnectionStatus.Connected)
        assertEquals("Connected", chip.label)
        assertFalse(chip.clickable, "Connected chip should not invite a reconnect tap")
        assertFalse(chip.showSpinner)
    }

    @Test
    fun chip_connecting_showsSpinner_andIsNotClickable() {
        val chip = connectionChip(ConnectionStatus.Connecting)
        assertEquals("Connecting…", chip.label)
        assertTrue(chip.showSpinner, "Connecting chip should show a spinner")
        assertFalse(chip.clickable, "no reconnect while a connect is already in flight")
    }

    @Test
    fun chip_notReachable_isClickableReconnect() {
        val chip = connectionChip(ConnectionStatus.NotReachable)
        assertEquals("⟳ Reconnect", chip.label)
        assertTrue(chip.clickable, "NotReachable chip is the reconnect action")
        assertFalse(chip.showSpinner)
    }

    @Test
    fun chip_noWatch_isClickableReconnect() {
        val chip = connectionChip(ConnectionStatus.NoWatch)
        assertEquals("⟳ Reconnect", chip.label)
        assertTrue(chip.clickable)
    }

    @Test
    fun wakeHint_onlyForNotReachable() {
        assertEquals(
            "Wake the watch: long-press ALARM or triple-tap the Clock face, then tap Reconnect.",
            wakeHint(ConnectionStatus.NotReachable),
        )
        assertNull(wakeHint(ConnectionStatus.Connected))
        assertNull(wakeHint(ConnectionStatus.Connecting))
        assertNull(wakeHint(ConnectionStatus.NoWatch))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.ble.ConnectionStatusTest"`
Expected: COMPILE FAILURE — `ConnectionStatus`, `connectionChip`, `wakeHint` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `ConnectionStatus.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ble

/**
 * Live status of the (foreground-only) watch link, surfaced to the UI. The phone cannot wake a
 * sleeping watch radio, so [NotReachable] is an expected resting state, not an error — it carries
 * the wake instructions via [wakeHint].
 */
enum class ConnectionStatus { NoWatch, Connecting, Connected, NotReachable }

/** Pure presentation model for the header chip-button. */
data class ConnectionChip(val label: String, val clickable: Boolean, val showSpinner: Boolean)

/** Maps a [status] to its chip label, whether tapping (re)connects, and whether to spin. */
fun connectionChip(status: ConnectionStatus): ConnectionChip = when (status) {
    ConnectionStatus.Connected -> ConnectionChip("Connected", clickable = false, showSpinner = false)
    ConnectionStatus.Connecting -> ConnectionChip("Connecting…", clickable = false, showSpinner = true)
    ConnectionStatus.NotReachable -> ConnectionChip("⟳ Reconnect", clickable = true, showSpinner = false)
    ConnectionStatus.NoWatch -> ConnectionChip("⟳ Reconnect", clickable = true, showSpinner = false)
}

/** The wake-instruction hint, shown only when a reconnect attempt has failed. */
fun wakeHint(status: ConnectionStatus): String? =
    if (status == ConnectionStatus.NotReachable)
        "Wake the watch: long-press ALARM or triple-tap the Clock face, then tap Reconnect."
    else null
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.ble.ConnectionStatusTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/ConnectionStatus.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/ble/ConnectionStatusTest.kt
git commit -m "feat: ConnectionStatus enum + pure chip/wake-hint presentation helpers"
```

---

## Task 2: `WatchConnection` interface + `NoopWatchConnection` + `FakeWatchConnection`

The ViewModel's view of the held link: observe status, connect, disconnect. `NoopWatchConnection`
is the default constructor arg so existing `AppViewModel(...)` call sites (MainActivity, all current
tests) keep compiling untouched until they're wired up. `FakeWatchConnection` is the test double the
Task 3 ViewModel tests drive.

No production behavior is testable here on its own (an interface + a no-op); the fake and the interface
are exercised by Task 3's tests. This task only adds compiling scaffolding, so it ends with a
compile-check rather than a unit run.

**Files:**
- Create: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/WatchConnection.kt`
- Modify: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/fakes/Fakes.kt`

- [ ] **Step 1: Create the interface + no-op default**

Create `WatchConnection.kt`:

```kotlin
package com.blizzardcaron.freeolleefaces.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The foreground-only held watch link, as the ViewModel sees it. Backed in production by the
 * process-wide `WatchLink` singleton (androidMain); all sends ride the same link via [BleClient].
 */
interface WatchConnection {
    /** Live link status; emits on every transition (Connecting → Connected/NotReachable, etc.). */
    val status: StateFlow<ConnectionStatus>

    /** Open and hold the link to [address], driving [status]. Suspends until the attempt resolves. */
    suspend fun connect(address: String)

    /** Release the held link. */
    fun disconnect()
}

/** Default no-op used when no real connection is wired (keeps non-Android callers/tests simple). */
object NoopWatchConnection : WatchConnection {
    private val _status = MutableStateFlow(ConnectionStatus.NoWatch)
    override val status: StateFlow<ConnectionStatus> = _status
    override suspend fun connect(address: String) {}
    override fun disconnect() {}
}
```

- [ ] **Step 2: Add `FakeWatchConnection` to the test fakes**

In `Fakes.kt`, add the import and the fake. Add to the existing import block at the top:

```kotlin
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.ble.WatchConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
```

Append this fake at the end of the file:

```kotlin
// ---------------------------------------------------------------------------
// FakeWatchConnection
// ---------------------------------------------------------------------------

/**
 * Scriptable WatchConnection. [connect] records into the shared [callLog], drives [status] through
 * Connecting then [connectResult], and bumps [connectCount]. [disconnect] records and resets status.
 * Status starts at Connecting (the production link assumes a connect is imminent on foreground).
 */
class FakeWatchConnection(
    var connectResult: ConnectionStatus = ConnectionStatus.Connected,
    private val callLog: MutableList<String> = mutableListOf(),
) : WatchConnection {

    private val _status = MutableStateFlow(ConnectionStatus.Connecting)
    override val status: StateFlow<ConnectionStatus> = _status

    var connectCount = 0
        private set
    var disconnectCount = 0
        private set
    val connectedAddresses: MutableList<String> = mutableListOf()

    override suspend fun connect(address: String) {
        connectCount++
        connectedAddresses += address
        callLog += "watch.connect($address)"
        _status.value = ConnectionStatus.Connecting
        _status.value = connectResult
    }

    override fun disconnect() {
        disconnectCount++
        callLog += "watch.disconnect"
        _status.value = ConnectionStatus.NotReachable
    }
}
```

- [ ] **Step 3: Compile-check**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — the whole existing suite still compiles and passes (no behavior changed; `NoopWatchConnection` is unused so far, `FakeWatchConnection` is referenced by no test yet).

- [ ] **Step 4: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ble/WatchConnection.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/fakes/Fakes.kt
git commit -m "feat: WatchConnection interface, NoopWatchConnection, FakeWatchConnection"
```

---

## Task 3: `AppViewModel` connection lifecycle + status mirroring

Add `HomeState.connectionStatus`, the `watchConnection` dependency (defaulted to `NoopWatchConnection`),
and the three lifecycle entry points the Activity will call: `onForeground` (start observing status +
connect), `onBackground` (stop observing + disconnect), `onReconnect` (the chip-button). Status is
mirrored from `watchConnection.status` into `HomeState` by a collector scoped to the foreground window,
so it never runs in the existing test suite (which never calls `onForeground`) and is cancelled on
background. Picking a watch also connects.

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeState.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt`
- Test: `app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/AppViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

Add these imports to the top of `AppViewModelTest.kt` (alongside the existing imports):

```kotlin
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.fakes.FakeWatchConnection
```

Add these test methods inside `class AppViewModelTest` (before the closing brace). They reuse the
existing `watchAddress` constant and the same constructor shape as the other tests, plus the new
`watchConnection = fake` argument:

```kotlin
    // ---------------------------------------------------------------------------
    // Test F — connection status lifecycle + reconnect
    // ---------------------------------------------------------------------------

    private fun vmWith(
        fake: FakeWatchConnection,
        prefs: Prefs,
        callLog: MutableList<String> = mutableListOf(),
    ): AppViewModel = AppViewModel(
        prefs = prefs,
        ble = FakeBleClient(callLog),
        steps = FakeStepsProvider(),
        location = FakeLocationProvider(),
        notificationAccess = FakeNotificationAccessChecker(),
        timerRepo = TimerSetsRepository(MapSettings()),
        scheduler = FakeScheduler(callLog),
        alarmRepo = AlarmsRepository(MapSettings()),
        alarmScheduler = FakeAlarmScheduler(callLog),
        watchConnection = fake,
    )

    @Test
    fun onForeground_withWatch_connectsAndReflectsConnected() = runTest(testScheduler) {
        val fake = FakeWatchConnection(connectResult = ConnectionStatus.Connected)
        val prefs = Prefs(MapSettings()).apply { watchAddress = watchAddress }
        val vm = vmWith(fake, prefs)

        vm.onForeground()
        advanceUntilIdle()

        assertEquals(1, fake.connectCount, "onForeground should connect once when a watch is selected")
        assertEquals(ConnectionStatus.Connected, vm.state.connectionStatus,
            "state should reflect the link going Connected")

        vm.onBackground()   // cancel the status collector so runTest sees no lingering work
    }

    @Test
    fun onForeground_noWatch_doesNotConnect_andShowsNoWatch() = runTest(testScheduler) {
        val fake = FakeWatchConnection()
        val prefs = Prefs(MapSettings())   // no watch address
        val vm = vmWith(fake, prefs)

        vm.onForeground()
        advanceUntilIdle()

        assertEquals(0, fake.connectCount, "no connect attempt without a selected watch")
        assertEquals(ConnectionStatus.NoWatch, vm.state.connectionStatus,
            "with no watch the chip is NoWatch regardless of the link's own status")

        vm.onBackground()
    }

    @Test
    fun onReconnect_failure_reflectsNotReachable_andRetriesOnTap() = runTest(testScheduler) {
        val fake = FakeWatchConnection(connectResult = ConnectionStatus.NotReachable)
        val prefs = Prefs(MapSettings()).apply { watchAddress = watchAddress }
        val vm = vmWith(fake, prefs)

        vm.onForeground()        // first connect attempt -> NotReachable
        advanceUntilIdle()
        assertEquals(1, fake.connectCount)
        assertEquals(ConnectionStatus.NotReachable, vm.state.connectionStatus)

        vm.onReconnect()         // chip-button tap -> another attempt
        advanceUntilIdle()
        assertEquals(2, fake.connectCount, "onReconnect should trigger a fresh connect attempt")
        assertEquals(ConnectionStatus.NotReachable, vm.state.connectionStatus)

        vm.onBackground()
    }

    @Test
    fun onReconnect_noWatch_isNoOp() = runTest(testScheduler) {
        val fake = FakeWatchConnection()
        val vm = vmWith(fake, Prefs(MapSettings()))   // no watch address

        vm.onReconnect()
        advanceUntilIdle()

        assertEquals(0, fake.connectCount, "reconnect is a no-op with no watch selected")
    }

    @Test
    fun onBackground_disconnects_andStopsObservingStatus() = runTest(testScheduler) {
        val fake = FakeWatchConnection(connectResult = ConnectionStatus.Connected)
        val prefs = Prefs(MapSettings()).apply { watchAddress = watchAddress }
        val vm = vmWith(fake, prefs)

        vm.onForeground()
        advanceUntilIdle()
        assertEquals(ConnectionStatus.Connected, vm.state.connectionStatus)

        vm.onBackground()
        advanceUntilIdle()
        assertEquals(1, fake.disconnectCount, "onBackground should release the held link")

        // disconnect() flipped the fake's status to NotReachable, but the collector is cancelled, so
        // state must NOT have followed it — proving observation stopped on background.
        assertEquals(ConnectionStatus.Connected, vm.state.connectionStatus,
            "state should freeze once observation stops on background")
    }

    @Test
    fun onWatchPicked_connects() = runTest(testScheduler) {
        val fake = FakeWatchConnection()
        val vm = vmWith(fake, Prefs(MapSettings()))   // no watch yet

        vm.onWatchPicked(watchAddress, "Watch: $watchAddress")
        advanceUntilIdle()

        assertEquals(1, fake.connectCount, "selecting a watch should establish the link")
        assertEquals(watchAddress, fake.connectedAddresses.last())
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blizzardcaron.freeolleefaces.AppViewModelTest"`
Expected: COMPILE FAILURE — `watchConnection` is not a parameter of `AppViewModel`, and
`vm.onForeground` / `onBackground` / `onReconnect` / `state.connectionStatus` are unresolved.

- [ ] **Step 3a: Add the `connectionStatus` field to `HomeState`**

In `HomeState.kt`, add the import and the field. Add to the imports:

```kotlin
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
```

Add the field to the `HomeState` data class (e.g. right after `watchSelected`):

```kotlin
    val connectionStatus: ConnectionStatus = ConnectionStatus.NoWatch,
```

- [ ] **Step 3b: Wire `watchConnection` and the lifecycle methods into `AppViewModel`**

In `AppViewModel.kt`, add these imports:

```kotlin
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.ble.NoopWatchConnection
import com.blizzardcaron.freeolleefaces.ble.WatchConnection
import kotlinx.coroutines.flow.collect
```

(The `Job`, `viewModelScope`, and `launch` symbols are already imported for the existing
`refreshJob`/`debounceJob`; only `collect` — the lambda terminal operator on `status` — is new.)

Add the constructor parameter (after `ble: BleClient`, with a default so existing call sites compile):

```kotlin
    private val watchConnection: WatchConnection = NoopWatchConnection,
```

In `initialState()`, set the initial status from whether a watch is configured. Change the
`watchSelected = ...` line's neighbourhood to also pass `connectionStatus`:

```kotlin
        watchSelected = prefs.watchAddress != null,
        connectionStatus = if (prefs.watchAddress != null) ConnectionStatus.Connecting
                           else ConnectionStatus.NoWatch,
```

Add the status-collector job field next to the other private jobs (near `refreshJob`/`debounceJob`):

```kotlin
    private var statusJob: Job? = null
```

Add the three lifecycle methods (place them near `onStart()`):

```kotlin
    /**
     * UI entered the foreground: start mirroring link status into state and connect if a watch is
     * selected. The collector is scoped to the foreground window (cancelled in [onBackground]); while
     * no watch is selected the chip reads NoWatch regardless of the link's own status.
     */
    fun onForeground() {
        if (statusJob == null) {
            statusJob = viewModelScope.launch {
                watchConnection.status.collect { s ->
                    val effective = if (prefs.watchAddress == null) ConnectionStatus.NoWatch else s
                    update { it.copy(connectionStatus = effective) }
                }
            }
        }
        prefs.watchAddress?.let { addr -> viewModelScope.launch { watchConnection.connect(addr) } }
    }

    /** UI left the foreground: stop mirroring status and release the held link. */
    fun onBackground() {
        statusJob?.cancel()
        statusJob = null
        watchConnection.disconnect()
    }

    /** Reconnect button: re-establish the held link to the selected watch (no-op without one). */
    fun onReconnect() {
        val addr = prefs.watchAddress ?: return
        viewModelScope.launch { watchConnection.connect(addr) }
    }
```

In `onWatchPicked`, establish the link as soon as a watch is chosen. Add this line right after the
`update { it.copy(watchLabel = label, watchSelected = true) }` call:

```kotlin
        viewModelScope.launch { watchConnection.connect(address) }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — the 6 new `AppViewModelTest` cases pass and the entire existing suite still passes
(no existing test references `watchConnection`, so they use `NoopWatchConnection` and never start the
collector).

- [ ] **Step 5: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeState.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/AppViewModel.kt \
        app/src/commonTest/kotlin/com/blizzardcaron/freeolleefaces/AppViewModelTest.kt
git commit -m "feat: ViewModel connection lifecycle (foreground/background/reconnect) + status mirroring"
```

---

## Task 4: `WatchLink` singleton + `AndroidBleClient` delegation + `AndroidWatchConnection`

The process-wide connection point. `WatchLink` owns the optional held GATT, a write `Mutex` (so a
foreground send and a background Worker can't collide on the single-client link), and the
`StateFlow<ConnectionStatus>`. Its `send` rides the held link when one is up and Connected, else runs
the one-shot connect/write/disconnect (the logic moved verbatim out of `AndroidBleClient`, retry policy
intact). `AndroidBleClient` becomes a thin delegate, so every existing caller (`AutoUpdateWorker`,
`AlarmRearm`, `NotificationCountService`) automatically shares the link with zero changes.

**GATT code is not unit-testable** (same precedent as `BleRetryPolicy`'s doc note) — this task ends
with a compile-check plus an explicit on-device verification checklist.

**Files:**
- Create: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ble/WatchLink.kt`
- Create: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ble/AndroidWatchConnection.kt`
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ble/AndroidBleClient.kt`

- [ ] **Step 1: Create `WatchLink.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Process-wide single connection point to the watch. Holds at most one GATT link (opened on
 * foreground via [connectHeld], released via [disconnectHeld]) and exposes its [status]. Every send
 * goes through [send]: it rides the held link when one is Connected to the same address, otherwise it
 * runs a one-shot connect → write → disconnect. A [Mutex] serializes all GATT work — the watch only
 * accepts one client at a time, so a foreground send and a background Worker cooperate here.
 */
@SuppressLint("MissingPermission")
object WatchLink {

    val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val WRITE_TIMEOUT_MS = 8_000L
    // ATT payload for the watch's default 23-byte MTU (MTU - 3). Longer frames are fragmented across
    // sequential writes and reassembled by the firmware via the LEN field.
    private const val ATT_PAYLOAD = 20

    private val _status = MutableStateFlow(ConnectionStatus.Connecting)
    val status: StateFlow<ConnectionStatus> = _status

    /** Serializes every connect / write — only one client may talk to the watch at a time. */
    private val lock = Mutex()

    // Held-connection state. Mutated under [lock]; the callback only reads/clears via WatchLink helpers.
    private var heldGatt: BluetoothGatt? = null
    private var heldCallback: HeldCallback? = null
    private var heldAddress: String? = null

    private fun ByteArray.toHex(): String =
        joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun chunksOf(packet: ByteArray): List<ByteArray> =
        packet.toList().chunked(ATT_PAYLOAD).map { it.toByteArray() }

    /** One chunk write, across the SDK's pre-/post-Tiramisu API split. Returns false on reject. */
    private fun writeChunk(g: BluetoothGatt, char: BluetoothGattCharacteristic, bytes: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") run {
                char.value = bytes
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(char)
            }
        }

    // --- Foreground held connection -------------------------------------------------

    /** Open and hold a link to [address] (idempotent if already Connected to it). Drives [status]. */
    suspend fun connectHeld(context: Context, address: String): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            if (heldGatt != null && heldAddress == address && _status.value == ConnectionStatus.Connected) {
                return@withLock
            }
            closeHeldLocked()
            _status.value = ConnectionStatus.Connecting
            val ok = runCatching {
                withTimeout(CONNECT_TIMEOUT_MS) { openHeld(context, address) }
            }.getOrDefault(false)
            if (ok) {
                _status.value = ConnectionStatus.Connected
            } else {
                closeHeldLocked()
                _status.value = ConnectionStatus.NotReachable
            }
        }
    }

    /** Release the held link (best-effort, non-suspending — called from the UI lifecycle). */
    fun disconnectHeld() {
        closeHeldLocked()
        _status.value = ConnectionStatus.NotReachable
    }

    private fun closeHeldLocked() {
        runCatching { heldGatt?.disconnect() }
        runCatching { heldGatt?.close() }
        heldGatt = null
        heldCallback = null
        heldAddress = null
    }

    private suspend fun openHeld(context: Context, address: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val manager = context.getSystemService(BluetoothManager::class.java)
            if (manager == null) { cont.resume(false); return@suspendCancellableCoroutine }
            val device: BluetoothDevice = manager.adapter.getRemoteDevice(address)
            val cb = HeldCallback().apply { connectCont = cont }
            heldCallback = cb
            heldAddress = address
            heldGatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            // On timeout/cancel the connectHeld runCatching path calls closeHeldLocked() to tear down.
            cont.invokeOnCancellation { }
        }

    /** Called by the held callback when the link drops unexpectedly. */
    private fun onHeldDropped() {
        if (_status.value == ConnectionStatus.Connected) _status.value = ConnectionStatus.NotReachable
    }

    // --- Unified send ---------------------------------------------------------------

    /** Send [packet] to [address], riding the held link if Connected, else one-shot. */
    suspend fun send(context: Context, address: String, packet: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            // Debug builds: log every outgoing frame on the OLLEE_BLE tag the capture rig filters.
            if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                Log.i("OLLEE_BLE", "FreeOllee TX $address ${packet.toHex()}")
            }
            lock.withLock {
                val g = heldGatt
                val cb = heldCallback
                val char = cb?.writeChar
                if (g != null && cb != null && char != null && heldAddress == address &&
                    _status.value == ConnectionStatus.Connected
                ) {
                    val held = runCatching {
                        withTimeout(WRITE_TIMEOUT_MS) { writeHeld(g, cb, char, packet) }
                    }.getOrElse { Result.failure(it) }
                    if (held.isSuccess) return@withLock held
                    // Held write failed — drop the link and fall back to a fresh one-shot.
                    closeHeldLocked()
                    _status.value = ConnectionStatus.NotReachable
                }
                oneShotSend(context, address, packet)
            }
        }

    private suspend fun writeHeld(
        g: BluetoothGatt,
        cb: HeldCallback,
        char: BluetoothGattCharacteristic,
        packet: ByteArray,
    ): Result<Unit> = suspendCancellableCoroutine { cont ->
        cb.writeChunks = chunksOf(packet)
        cb.writeIndex = 0
        cb.writeCont = cont
        if (!writeChunk(g, char, cb.writeChunks[0])) {
            cb.writeCont = null
            cont.resume(Result.failure(IllegalStateException("writeCharacteristic returned false")))
        }
    }

    // --- One-shot fallback: connect → write → disconnect, with retry ----------------

    private suspend fun oneShotSend(context: Context, address: String, packet: ByteArray): Result<Unit> =
        runCatching {
            val manager = context.getSystemService(BluetoothManager::class.java)
                ?: error("BluetoothManager unavailable")
            val device: BluetoothDevice = manager.adapter.getRemoteDevice(address)
            for (attempt in 0 until BleRetryPolicy.MAX_ATTEMPTS) {
                val isLastAttempt = attempt == BleRetryPolicy.MAX_ATTEMPTS - 1
                try {
                    withTimeout(CONNECT_TIMEOUT_MS) { oneShotWrite(context, device, packet) }
                    return@runCatching
                } catch (timeout: TimeoutCancellationException) {
                    if (isLastAttempt) throw timeout
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    if (isLastAttempt || !BleRetryPolicy.isRetryable(error)) throw error
                }
                delay(BleRetryPolicy.backoffForAttempt(attempt))
            }
        }

    private suspend fun oneShotWrite(context: Context, device: BluetoothDevice, packet: ByteArray) =
        suspendCancellableCoroutine<Unit> { cont ->
            var gatt: BluetoothGatt? = null
            val chunks = chunksOf(packet)
            var next = 0

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("connection lost: status=$status"))
                        }
                        g.close()
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (!cont.isActive) { g.disconnect(); return }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        cont.resumeWithException(IllegalStateException("service discovery failed: $status"))
                        g.disconnect(); return
                    }
                    val service = g.getService(SERVICE_UUID) ?: run {
                        cont.resumeWithException(IllegalStateException("Nordic UART service not found"))
                        g.disconnect(); return
                    }
                    val char = service.getCharacteristic(CHAR_UUID) ?: run {
                        cont.resumeWithException(IllegalStateException("RX characteristic not found"))
                        g.disconnect(); return
                    }
                    if (!writeChunk(g, char, chunks[next])) {
                        cont.resumeWithException(IllegalStateException("writeCharacteristic returned false"))
                        g.disconnect()
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicWrite(
                    g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int,
                ) {
                    if (!cont.isActive) { g.disconnect(); return }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        cont.resumeWithException(IllegalStateException("write failed: $status"))
                        g.disconnect(); return
                    }
                    next++
                    if (next >= chunks.size) {
                        cont.resume(Unit); g.disconnect()
                    } else if (!writeChunk(g, characteristic, chunks[next])) {
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

    // --- Held-connection callback (long-lived: spans connect + every write) ---------

    private class HeldCallback : BluetoothGattCallback() {
        @Volatile var connectCont: CancellableContinuation<Boolean>? = null
        @Volatile var writeCont: CancellableContinuation<Result<Unit>>? = null
        @Volatile var writeChar: BluetoothGattCharacteristic? = null
        var writeChunks: List<ByteArray> = emptyList()
        var writeIndex: Int = 0

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectCont?.let { if (it.isActive) it.resume(false) }
                connectCont = null
                writeCont?.let {
                    if (it.isActive) it.resume(Result.failure(IllegalStateException("link dropped: status=$status")))
                }
                writeCont = null
                WatchLink.onHeldDropped()
                g.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val cc = connectCont
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectCont = null; cc?.resume(false); g.disconnect(); return
            }
            val char = g.getService(WatchLink.SERVICE_UUID)?.getCharacteristic(WatchLink.CHAR_UUID)
            if (char == null) {
                connectCont = null; cc?.resume(false); g.disconnect(); return
            }
            writeChar = char
            connectCont = null
            cc?.resume(true)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int,
        ) {
            val wc = writeCont ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                writeCont = null
                wc.resume(Result.failure(IllegalStateException("write failed: $status")))
                return
            }
            writeIndex++
            if (writeIndex >= writeChunks.size) {
                writeCont = null
                wc.resume(Result.success(Unit))
            } else if (!WatchLink.writeChunk(g, characteristic, writeChunks[writeIndex])) {
                writeCont = null
                wc.resume(Result.failure(IllegalStateException("writeCharacteristic returned false")))
            }
        }
    }
}
```

> **Kotlin note:** `HeldCallback` is a *nested* (not `inner`) class, so it has no implicit receiver
> for the enclosing `object WatchLink`. Its references to the object's members
> (`WatchLink.SERVICE_UUID`, `WatchLink.CHAR_UUID`, `WatchLink.writeChunk`, `WatchLink.onHeldDropped`)
> are therefore qualified with `WatchLink.` — qualified access always compiles here because a nested
> class can see the enclosing object's `private` members. (The one-shot `oneShotWrite` path uses an
> anonymous `object : BluetoothGattCallback()` expression instead, which is a closure over its enclosing
> function and so reads `SERVICE_UUID`/`writeChunk` unqualified.)

- [ ] **Step 2: Replace `AndroidBleClient.kt` with a thin delegate**

Overwrite the whole file:

```kotlin
package com.blizzardcaron.freeolleefaces.ble

import android.content.Context

/**
 * Fire-and-forget [BleClient] used by every caller (UI sends, background Workers). All sends funnel
 * through the process-wide [WatchLink], so they ride the foreground-held link when one is open and
 * fall back to one-shot connect/write/disconnect otherwise.
 */
class AndroidBleClient(private val context: Context) : BleClient {

    override suspend fun send(deviceAddress: String, value: String): Result<Unit> =
        send(deviceAddress, value, OlleeProtocol.TARGET_NAMEPLATE)

    override suspend fun send(deviceAddress: String, value: String, target: Int): Result<Unit> {
        val packet = OlleeProtocol.buildPacket(target, value.padEnd(OlleeProtocol.MAX_VALUE_LENGTH, ' '))
        return WatchLink.send(context, deviceAddress, packet)
    }

    override suspend fun sendPacket(deviceAddress: String, packet: ByteArray): Result<Unit> =
        WatchLink.send(context, deviceAddress, packet)
}
```

- [ ] **Step 3: Create `AndroidWatchConnection.kt`**

```kotlin
package com.blizzardcaron.freeolleefaces.ble

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** [WatchConnection] backed by the process-wide [WatchLink] singleton. */
class AndroidWatchConnection(private val context: Context) : WatchConnection {
    override val status: StateFlow<ConnectionStatus> = WatchLink.status
    override suspend fun connect(address: String) = WatchLink.connectHeld(context, address)
    override fun disconnect() = WatchLink.disconnectHeld()
}
```

- [ ] **Step 4: Compile-check + run the unit suite**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (the new androidMain files compile; no caller of `AndroidBleClient` changed).

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — full suite still green (commonMain/commonTest untouched by this task).

- [ ] **Step 5: On-device verification (hardware — GATT is not unit-testable)**

Install: `./gradlew :app:installDebug`. Then, watching `adb logcat -s OLLEE_BLE`:

1. Wake the watch (long-press ALARM), open the app → a complication send should log `FreeOllee TX …`.
2. With the app foregrounded and a watch selected, trigger two sends in quick succession (e.g. Update
   active now, then Send custom) → both should land; the second should be near-instant (held link).
3. Let the watch radio sleep (leave it idle), then trigger a send → it still completes via the one-shot
   fallback (after the held write fails and the link is dropped) or logs the failure snackbar.
4. Background the app, let a WorkManager refresh fire (or `adb shell cmd jobscheduler run`), confirm the
   write still lands (one-shot path, link released).

- [ ] **Step 6: Commit**

```bash
git add app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ble/WatchLink.kt \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ble/AndroidWatchConnection.kt \
        app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/ble/AndroidBleClient.kt
git commit -m "feat: WatchLink held-connection singleton; AndroidBleClient delegates all sends through it"
```

---

## Task 5: Header chip-button + wake hint in `HomeScreen`

Render the live status. A non-interactive label for Connected/Connecting (with a spinner) and a tappable
"⟳ Reconnect" for NotReachable/NoWatch, plus the wake-instruction hint below it when NotReachable. All
the decision logic lives in the pure helpers from Task 1; this is presentation only, so it ends with a
compile-check (the suite has no Compose UI tests).

**Files:**
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt`
- Modify: `app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt`

- [ ] **Step 1: Add the `onReconnect` callback to `HomeCallbacks`**

In `Callbacks.kt`, add this field to `HomeCallbacks` (a default keeps MainActivity compiling until Task 6):

```kotlin
    val onReconnect: () -> Unit = {},
```

- [ ] **Step 2: Add the imports to `HomeScreen.kt`**

Add to the import block:

```kotlin
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.ble.connectionChip
import com.blizzardcaron.freeolleefaces.ble.wakeHint
```

- [ ] **Step 3: Add the `ConnectionRow` composable**

Add this private composable to `HomeScreen.kt` (e.g. just above `private fun SettingsHint(...)`):

```kotlin
@Composable
private fun ConnectionRow(status: ConnectionStatus, onReconnect: () -> Unit) {
    val chip = connectionChip(status)
    val color = when (status) {
        ConnectionStatus.Connected -> MaterialTheme.colorScheme.primary
        ConnectionStatus.Connecting -> MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionStatus.NotReachable, ConnectionStatus.NoWatch -> MaterialTheme.colorScheme.error
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (chip.clickable) {
            TextButton(onClick = onReconnect) { Text(chip.label, color = color) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chip.showSpinner) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(chip.label, color = color, style = MaterialTheme.typography.labelLarge)
            }
        }
        wakeHint(status)?.let { hint ->
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
```

- [ ] **Step 4: Render `ConnectionRow` under the header divider**

In `HomeScreen`, immediately after the `HorizontalDivider()` that follows the header `Row` (the title +
Timers/Alarms/⚙ row), add:

```kotlin
        ConnectionRow(status = state.connectionStatus, onReconnect = callbacks.onReconnect)
```

- [ ] **Step 5: Compile-check**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (unchanged suite).

- [ ] **Step 6: Commit**

```bash
git add app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/Callbacks.kt \
        app/src/commonMain/kotlin/com/blizzardcaron/freeolleefaces/ui/HomeScreen.kt
git commit -m "feat: header connection chip-button + wake-instruction hint"
```

---

## Task 6: Wire it all together in `MainActivity`

Construct the real `AndroidWatchConnection`, hand it to the ViewModel, provide the `onReconnect`
callback, and drive `onForeground`/`onBackground` from the activity lifecycle so the held link opens on
foreground and releases on background.

**Files:**
- Modify: `app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt`

- [ ] **Step 1: Import `AndroidWatchConnection`**

Add to the imports:

```kotlin
import com.blizzardcaron.freeolleefaces.ble.AndroidWatchConnection
```

- [ ] **Step 2: Pass the connection into the ViewModel**

In the `AppViewModel(...)` constructor call inside `remember { ... }`, add the argument (after
`ble = AndroidBleClient(context),`):

```kotlin
            watchConnection = AndroidWatchConnection(context),
```

- [ ] **Step 3: Provide the `onReconnect` callback**

In the `HomeCallbacks(...)` construction, add:

```kotlin
        onReconnect = { viewModel.onReconnect() },
```

- [ ] **Step 4: Drive connect/disconnect from the lifecycle**

Replace the existing lifecycle observer in the `DisposableEffect(lifecycleOwner)` block:

```kotlin
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResumeNotifications()
        }
```

with:

```kotlin
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                Lifecycle.Event.ON_RESUME -> viewModel.onResumeNotifications()
                else -> {}
            }
        }
```

- [ ] **Step 5: Compile-check + run the suite**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Full on-device verification**

Install (`./gradlew :app:installDebug`) and confirm the end-to-end UX:

1. With a watch selected, open the app while the watch is **asleep** → chip shows "Connecting…" briefly,
   then "⟳ Reconnect" with the wake hint underneath.
2. Wake the watch (long-press ALARM) and tap **⟳ Reconnect** → chip flips to "Connected" (no hint).
3. With "Connected" showing, send a complication → near-instant (held link); watch `adb logcat -s OLLEE_BLE`.
4. Press Home (background) then return → chip re-runs Connecting → Connected/NotReachable.
5. With no watch selected → chip shows "⟳ Reconnect" and tapping it is a no-op (plus the existing
   "No watch selected" hint in the body).

- [ ] **Step 7: Commit**

```bash
git add app/src/androidMain/kotlin/com/blizzardcaron/freeolleefaces/MainActivity.kt
git commit -m "feat: wire AndroidWatchConnection + lifecycle connect/disconnect + reconnect callback"
```

---

## Final verification

- [ ] Run the full unit suite once more: `./gradlew :app:testDebugUnitTest` → all green.
- [ ] Full build: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] Confirm the on-device checklists in Task 4 Step 5 and Task 6 Step 6 all pass.
- [ ] `git log --oneline` shows the six feature commits on `feature/connection-status-reconnect`.
