package com.blizzardcaron.freeolleefaces

import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.fakes.FakeLocationProvider
import com.blizzardcaron.freeolleefaces.fakes.FakeNotificationAccessChecker
import com.blizzardcaron.freeolleefaces.fakes.FakeScheduler
import com.blizzardcaron.freeolleefaces.fakes.FakeStepsProvider
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.timer.TimerSet
import com.blizzardcaron.freeolleefaces.timer.TimerSlot
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import com.blizzardcaron.freeolleefaces.ui.Screen
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(testScheduler)

    @BeforeTest
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private val watchAddress = "AA:BB:CC:DD:EE:FF"

    /** Builds a TimerSet with exactly 10 slots; first slot is 60 s. */
    private fun makeTimerSet(id: String = "t1", name: String = "Test Set"): TimerSet =
        TimerSet(id, name, List(10) { i -> TimerSlot(label = "S${i + 1}", durationSeconds = 60 + i) })

    // ---------------------------------------------------------------------------
    // Test A — activate(TEMPERATURE) ordering
    // ---------------------------------------------------------------------------

    /**
     * Verifies that activate(TEMPERATURE) with a fresh temp cache:
     *   1. Persists activeComplication to prefs (verified via a second Prefs backed by same MapSettings)
     *   2. Updates vm.state.activeComplication
     *   3. Does NOT navigate — the screen STAYS where it was (Settings). Selecting a
     *      complication updates the radio in place; the old silent bounce to Home is gone (Phase 8.4).
     *   4. Calls scheduler.reschedule() BEFORE any BLE send (ordering in shared callLog)
     *   5. Fires the BLE send after coroutines drain
     *
     * Also verifies the synchronous / async split:
     *   - scheduler.reschedule IS already in the log before advanceUntilIdle()
     *   - BLE send is NOT in the log before advanceUntilIdle()
     */
    @Test
    fun activate_temperature_orderingAndPersistence() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val ble = FakeBleClient(callLog)
        val scheduler = FakeScheduler(callLog)

        val settings = MapSettings()
        val prefs = Prefs(settings)

        // Set valid coords so validCoords() returns non-null and initialState() populates lat/lng.
        prefs.lastLat = 40.7128
        prefs.lastLng = -74.0060

        // Seed a fresh temp cache: fetchedMs = now, so (now - fetchedMs) = 0 < 15*60000.
        val nowMs = Clock.System.now().toEpochMilliseconds()
        prefs.tempFetchedMs = nowMs
        prefs.tempValue = 72.0
        prefs.tempCacheUnit = TempUnit.FAHRENHEIT

        // Set watch address so pushIfWatch fires and a BLE send is recorded.
        prefs.watchAddress = watchAddress

        val vm = AppViewModel(
            prefs = prefs,
            ble = ble,
            steps = FakeStepsProvider(),
            location = FakeLocationProvider(),
            notificationAccess = FakeNotificationAccessChecker(),
            timerRepo = TimerSetsRepository(settings),
            scheduler = scheduler,
        )

        // Pre-condition: log is empty.
        assertTrue(callLog.isEmpty(), "callLog should be empty before activate()")

        // Navigate away from Home first; activate() must NOT move us. (screen defaults to Home,
        // so without this the "stays put" assertion below would be vacuous.)
        vm.navigateTo(Screen.Settings)

        vm.activate(ActiveComplication.TEMPERATURE)

        // --- Synchronous assertions (before draining coroutines) ---

        // 1. prefs.activeComplication persisted synchronously (read back via same settings).
        val prefsReader = Prefs(settings)
        assertEquals(ActiveComplication.TEMPERATURE, prefsReader.activeComplication,
            "prefs.activeComplication should be persisted synchronously by activate()")

        // 2. state.activeComplication updated synchronously.
        assertEquals(ActiveComplication.TEMPERATURE, vm.state.activeComplication,
            "state.activeComplication should be set synchronously by activate()")

        // 3. screen UNCHANGED — activate() no longer navigates; the selection reflects in place.
        assertEquals(Screen.Settings, vm.screen,
            "screen should STAY on Settings after activate() (no silent bounce to Home)")

        // scheduler.reschedule() is synchronous — already in the log.
        assertTrue(callLog.contains("scheduler.reschedule"),
            "scheduler.reschedule should be in callLog before advanceUntilIdle: $callLog")

        // BLE send has NOT yet fired (it's inside a coroutine launched by pushIfWatch).
        val bleBeforeIdle = callLog.any { it.startsWith("ble.") }
        assertTrue(!bleBeforeIdle,
            "BLE send should NOT appear in callLog before advanceUntilIdle: $callLog")

        advanceUntilIdle()

        // --- Post-drain assertions ---

        // 4. scheduler.reschedule precedes the BLE send in the ordered log.
        val rescheduleIdx = callLog.indexOfFirst { it == "scheduler.reschedule" }
        val bleIdx = callLog.indexOfFirst { it.startsWith("ble.") }
        assertTrue(rescheduleIdx >= 0, "scheduler.reschedule should be present after idle")
        assertTrue(bleIdx >= 0,
            "a BLE send should be recorded after advanceUntilIdle: $callLog")
        assertTrue(rescheduleIdx < bleIdx,
            "scheduler.reschedule (index $rescheduleIdx) must precede BLE send " +
                "(index $bleIdx) in callLog: $callLog")
    }

    // ---------------------------------------------------------------------------
    // Test B1 — sendTimerSet with no watch selected: snackbar, no BLE
    // ---------------------------------------------------------------------------

    @Test
    fun sendTimerSet_noWatchAddress_showsSnackbarAndNoBleCall() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val ble = FakeBleClient(callLog)
        val settings = MapSettings()
        // watchAddress intentionally NOT set.
        val prefs = Prefs(settings)
        val vm = AppViewModel(
            prefs = prefs,
            ble = ble,
            steps = FakeStepsProvider(),
            location = FakeLocationProvider(),
            notificationAccess = FakeNotificationAccessChecker(),
            timerRepo = TimerSetsRepository(settings),
            scheduler = FakeScheduler(callLog),
        )

        val set = makeTimerSet()

        // Collect the first snackbar event.
        val eventDeferred = CompletableDeferred<String>()
        val collectJob = launch {
            val msg = vm.events.first()
            eventDeferred.complete(msg)
        }

        vm.sendTimerSet(set)
        advanceUntilIdle()

        // The snackbar message must match the production string.
        val event = eventDeferred.await()
        assertEquals("No watch selected — open Settings (⚙)", event,
            "snackbar message mismatch when no watch selected")

        // BLE fake must have received zero calls.
        val bleCalls = callLog.filter { it.startsWith("ble.") }
        assertTrue(bleCalls.isEmpty(),
            "BLE fake should record no calls when watchAddress is null: $bleCalls")

        collectJob.cancel()
    }

    // ---------------------------------------------------------------------------
    // Test B2 — sendTimerSet in-flight guard: second call ignored; success sets activeId
    // ---------------------------------------------------------------------------

    @Test
    fun sendTimerSet_inflight_secondCallIgnored_andSuccessSetsActiveId() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val ble = FakeBleClient(callLog, gate = gate)
        val settings = MapSettings()
        val prefs = Prefs(settings)
        prefs.watchAddress = watchAddress

        val vm = AppViewModel(
            prefs = prefs,
            ble = ble,
            steps = FakeStepsProvider(),
            location = FakeLocationProvider(),
            notificationAccess = FakeNotificationAccessChecker(),
            timerRepo = TimerSetsRepository(settings),
            scheduler = FakeScheduler(callLog),
        )

        val set = makeTimerSet()

        // First call — launches a coroutine that will suspend at the gate.
        vm.sendTimerSet(set)

        // Run the already-queued coroutine: it sets sending = true, then suspends at the gate.
        testScheduler.runCurrent()

        // Second call while first is in-flight: state.sending == true, so it must be ignored.
        vm.sendTimerSet(set)

        // Release the gate so the first coroutine can complete.
        gate.complete(Unit)
        advanceUntilIdle()

        // Exactly one sendPacket call — the second sendTimerSet was a no-op.
        val sendPacketCalls = callLog.filter { it.startsWith("ble.sendPacket") }
        assertEquals(1, sendPacketCalls.size,
            "exactly one sendPacket should fire; in-flight guard must block the second: $callLog")

        // On success the active timer id should be updated in state AND persisted via the repo.
        assertEquals(set.id, vm.timerActiveId,
            "timerActiveId should equal the sent set's id after a successful sendTimerSet")
        assertEquals(set.id, TimerSetsRepository(settings).activeId(),
            "active id should be persisted through the repo, not just held in VM state")
    }

    // ---------------------------------------------------------------------------
    // Test C1 — startQuickTimer with no watch selected: snackbar, no BLE
    // ---------------------------------------------------------------------------

    @Test
    fun startQuickTimer_noWatchAddress_showsSnackbarAndNoBleCall() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val ble = FakeBleClient(callLog)
        val settings = MapSettings()
        // watchAddress intentionally NOT set.
        val prefs = Prefs(settings)
        val vm = AppViewModel(
            prefs = prefs,
            ble = ble,
            steps = FakeStepsProvider(),
            location = FakeLocationProvider(),
            notificationAccess = FakeNotificationAccessChecker(),
            timerRepo = TimerSetsRepository(settings),
            scheduler = FakeScheduler(callLog),
        )

        // Collect the first snackbar event.
        val eventDeferred = CompletableDeferred<String>()
        val collectJob = launch {
            val msg = vm.events.first()
            eventDeferred.complete(msg)
        }

        vm.startQuickTimer()
        advanceUntilIdle()

        // The snackbar message must match the production string.
        assertEquals("No watch selected — open Settings (⚙)", eventDeferred.await(),
            "snackbar message mismatch when no watch selected")
        assertTrue(callLog.none { it.startsWith("ble.sendPacket") }, "no BLE call without a watch")

        collectJob.cancel()
    }

    // ---------------------------------------------------------------------------
    // Test C2 — startQuickTimer success: sends START_SINGLE frame with active set slots
    // ---------------------------------------------------------------------------

    @Test
    fun startQuickTimer_success_sendsStartSingleFrameWithActiveSetSlots() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val ble = FakeBleClient(callLog)
        val settings = MapSettings()
        val prefs = Prefs(settings)
        prefs.watchAddress = "00:11:22:33:44:55"
        prefs.quickTimerSeconds = 427          // 07:07 -> header MM=7 SS=7
        val timerRepo = TimerSetsRepository(MapSettings())
        val baseSet = TimerSet.blank("id1", "Set 1")
        val set = baseSet.copy(slots = baseSet.slots.mapIndexed { i, s ->
            if (i == 0) TimerSlot(durationSeconds = 180) else s
        })
        timerRepo.save(set)
        timerRepo.setActive("id1")
        val vm = AppViewModel(
            prefs = prefs,
            ble = ble,
            steps = FakeStepsProvider(),
            location = FakeLocationProvider(),
            notificationAccess = FakeNotificationAccessChecker(),
            timerRepo = timerRepo,
            scheduler = FakeScheduler(callLog),
        )

        vm.startQuickTimer()
        advanceUntilIdle()

        val pkt = ble.sentPackets.single()
        assertEquals(0x02.toByte(), pkt[11], "header byte3 = START_SINGLE")
        assertEquals(7.toByte(), pkt[9], "header MM from quickTimerSeconds")
        assertEquals(7.toByte(), pkt[10], "header SS from quickTimerSeconds")
        assertEquals(0xB4.toByte(), pkt[12]) // slot[0] LE byte0 = 180 s = 0xB4, from the active set
    }
}
