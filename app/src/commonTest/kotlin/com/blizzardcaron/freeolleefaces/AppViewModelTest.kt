package com.blizzardcaron.freeolleefaces

import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.fakes.FakeLocationProvider
import com.blizzardcaron.freeolleefaces.fakes.FakeNotificationAccessChecker
import com.blizzardcaron.freeolleefaces.fakes.FakeScheduler
import com.blizzardcaron.freeolleefaces.fakes.FakeStepsProvider
import com.blizzardcaron.freeolleefaces.fakes.FakeWatchConnection
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import com.blizzardcaron.freeolleefaces.ui.Screen
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import com.blizzardcaron.freeolleefaces.alarm.AlarmsRepository
import com.blizzardcaron.freeolleefaces.fakes.FakeAlarmScheduler
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
            alarmRepo = AlarmsRepository(MapSettings()),
            alarmScheduler = FakeAlarmScheduler(callLog),
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
        val prefs = Prefs(MapSettings()).apply { watchAddress = this@AppViewModelTest.watchAddress }
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
        val prefs = Prefs(MapSettings()).apply { watchAddress = this@AppViewModelTest.watchAddress }
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
        val prefs = Prefs(MapSettings()).apply { watchAddress = this@AppViewModelTest.watchAddress }
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
}
