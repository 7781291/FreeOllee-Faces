package com.blizzardcaron.freeolleefaces

import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.fakes.FakeLocationProvider
import com.blizzardcaron.freeolleefaces.fakes.FakeNotificationAccessChecker
import com.blizzardcaron.freeolleefaces.fakes.FakeScheduler
import com.blizzardcaron.freeolleefaces.fakes.FakeStepsProvider
import com.blizzardcaron.freeolleefaces.fakes.FakeWatchConnection
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

    @Test
    fun exposes_an_activity_controller() {
        val fake = FakeWatchConnection()
        val vm = vmWith(fake, Prefs(MapSettings()))

        assertEquals(false, vm.activity.state.value.running)
    }
}
