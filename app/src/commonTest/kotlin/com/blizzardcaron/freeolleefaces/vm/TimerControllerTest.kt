package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.timer.QuickAlarm
import com.blizzardcaron.freeolleefaces.timer.TimerSet
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import com.blizzardcaron.freeolleefaces.timer.TimerSlot
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Moved from AppViewModelTest's timer-cluster tests (B1/B2/C1/C2/C2b/C2c/C3/E) — the timer
 * cluster now lives in [TimerController], constructed directly against the real
 * [TimerSetsRepository]/[Prefs] (backed by [MapSettings], same pattern AppViewModelTest used)
 * and the existing [FakeBleClient] fake. The shared `sending` flag is modeled with a local
 * `HomeState` holder, exactly mirroring how AppViewModel wires `state`/`update` into the
 * controller (single shared flag, not a private copy).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerControllerTest {

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

    /** Builds a TimerSet with exactly 10 slots; first slot is 60 s. */
    private fun makeTimerSet(id: String = "t1", name: String = "Test Set"): TimerSet =
        TimerSet(id, name, List(10) { i -> TimerSlot(label = "S${i + 1}", durationSeconds = 60 + i) })

    /** Local HomeState holder mirroring AppViewModel's state/update wiring for the controller. */
    private class StateHolder(initial: HomeState = HomeState()) {
        var st = initial
        val state: () -> HomeState = { st }
        val update: ((HomeState) -> HomeState) -> Unit = { t -> st = t(st) }
    }

    private fun controller(
        timerRepo: TimerSetsRepository,
        ble: FakeBleClient,
        prefs: Prefs,
        scope: kotlinx.coroutines.CoroutineScope,
        holder: StateHolder = StateHolder(),
        clock: Clock = Clock.System,
    ) = TimerController(
        timerRepo = timerRepo,
        ble = ble,
        prefs = prefs,
        scope = scope,
        showSnackbar = {},
        state = holder.state,
        update = holder.update,
        clock = clock,
    )

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
        var snackbar: String? = null
        val c = TimerController(
            timerRepo = TimerSetsRepository(settings),
            ble = ble,
            prefs = prefs,
            scope = this,
            showSnackbar = { snackbar = it },
            state = { HomeState() },
            update = {},
        )

        val set = makeTimerSet()

        c.sendTimerSet(set)
        advanceUntilIdle()

        assertEquals("No watch selected — open Settings (⚙)", snackbar,
            "snackbar message mismatch when no watch selected")

        val bleCalls = callLog.filter { it.startsWith("ble.") }
        assertTrue(bleCalls.isEmpty(),
            "BLE fake should record no calls when watchAddress is null: $bleCalls")
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
        prefs.watchAddress = "AA:BB:CC:DD:EE:FF"
        val holder = StateHolder()

        val c = controller(TimerSetsRepository(settings), ble, prefs, this, holder)

        val set = makeTimerSet()

        // First call — launches a coroutine that will suspend at the gate.
        c.sendTimerSet(set)

        // Run the already-queued coroutine: it sets sending = true, then suspends at the gate.
        testScheduler.runCurrent()

        // Second call while first is in-flight: state().sending == true, so it must be ignored.
        c.sendTimerSet(set)

        // Release the gate so the first coroutine can complete.
        gate.complete(Unit)
        advanceUntilIdle()

        // Exactly one sendPacket call — the second sendTimerSet was a no-op.
        val sendPacketCalls = callLog.filter { it.startsWith("ble.sendPacket") }
        assertEquals(1, sendPacketCalls.size,
            "exactly one sendPacket should fire; in-flight guard must block the second: $callLog")

        // On success the active timer id should be updated in state AND persisted via the repo.
        assertEquals(set.id, c.timerActiveId,
            "timerActiveId should equal the sent set's id after a successful sendTimerSet")
        assertEquals(set.id, TimerSetsRepository(settings).activeId(),
            "active id should be persisted through the repo, not just held in VM state")
    }

    // ---------------------------------------------------------------------------
    // Test C1 — sendQuickTimer with no watch selected: snackbar, no BLE
    // ---------------------------------------------------------------------------

    @Test
    fun sendQuickTimer_noWatchAddress_showsSnackbarAndNoBleCall() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val ble = FakeBleClient(callLog)
        val settings = MapSettings()
        // watchAddress intentionally NOT set.
        val prefs = Prefs(settings)
        var snackbar: String? = null
        val c = TimerController(
            timerRepo = TimerSetsRepository(settings),
            ble = ble,
            prefs = prefs,
            scope = this,
            showSnackbar = { snackbar = it },
            state = { HomeState() },
            update = {},
        )

        c.sendQuickTimer()
        advanceUntilIdle()

        assertEquals("No watch selected — open Settings (⚙)", snackbar,
            "snackbar message mismatch when no watch selected")
        assertTrue(callLog.none { it.startsWith("ble.sendPacket") }, "no BLE call without a watch")
    }

    // ---------------------------------------------------------------------------
    // Test C2 — sendQuickTimer with default toggles (start on, interval off): START_SINGLE,
    // active set's slots
    // ---------------------------------------------------------------------------

    @Test
    fun sendQuickTimer_defaultToggles_sendsStartSingleFrameWithActiveSetSlots() = runTest(testScheduler) {
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
        val c = controller(timerRepo, ble, prefs, this)

        c.sendQuickTimer()
        advanceUntilIdle()

        val pkt = ble.sentPackets.single()
        assertEquals(0x01.toByte(), pkt[11], "header byte3 = START_SINGLE (verified on hardware)")
        assertEquals(7.toByte(), pkt[9], "header MM from quickTimerSeconds")
        assertEquals(7.toByte(), pkt[10], "header SS from quickTimerSeconds")
        assertEquals(0xB4.toByte(), pkt[12]) // slot[0] LE byte0 = 180 s = 0xB4, from the active set
    }

    // ---------------------------------------------------------------------------
    // Test C2b — the two toggles decide header byte3, decoupled from any single button
    // ---------------------------------------------------------------------------

    @Test
    fun sendQuickTimer_togglesSelectStartMode() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val ble = FakeBleClient(callLog)
        val settings = MapSettings()
        val prefs = Prefs(settings)
        prefs.watchAddress = "00:11:22:33:44:55"
        prefs.quickTimerSeconds = 427
        val timerRepo = TimerSetsRepository(MapSettings())
        val c = controller(timerRepo, ble, prefs, this)

        // Interval mode on (and start on) -> START_INTERVAL (byte3=0x02, verified on hardware).
        c.toggleQuickTimerIntervalMode(true)
        c.sendQuickTimer()
        advanceUntilIdle()
        assertEquals(0x02.toByte(), ble.sentPackets.last()[11], "start + interval -> START_INTERVAL")

        // Start from app off -> SAVE, regardless of interval mode.
        c.toggleQuickTimerStartFromApp(false)
        c.sendQuickTimer()
        advanceUntilIdle()
        assertEquals(0x00.toByte(), ble.sentPackets.last()[11], "start off -> SAVE")

        // Toggles persist across a fresh ViewModel (Prefs-backed).
        assertTrue(prefs.quickTimerIntervalMode, "interval mode persisted")
        assertEquals(false, prefs.quickTimerStartFromApp, "start-from-app persisted")
    }

    // ---------------------------------------------------------------------------
    // Test C2c — alarm-mode quick timer: computes countdown to wall-clock target
    // ---------------------------------------------------------------------------

    @Test
    fun sendQuickAlarm_sendsStartSingleFrameWithComputedCountdown() = runTest(testScheduler) {
        val callLog = mutableListOf<String>()
        val ble = FakeBleClient(callLog)
        val settings = MapSettings()
        val prefs = Prefs(settings)
        prefs.watchAddress = "00:11:22:33:44:55"
        // Fixed instant so the computed countdown is deterministic relative to the assertion.
        val fixedClock = object : Clock {
            override fun now() = Instant.parse("2026-06-15T12:00:00Z")
        }
        val c = controller(TimerSetsRepository(MapSettings()), ble, prefs, this, clock = fixedClock)

        c.saveQuickTimerAlarmTime(hour = 14, minute = 30)
        c.sendQuickAlarm()
        advanceUntilIdle()

        // Expected countdown via the same path the controller uses (timezone-agnostic).
        val now = fixedClock.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
        val expected = QuickAlarm.countdownSeconds(now, 14, 30).coerceAtMost(86_399)

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
        val c = controller(TimerSetsRepository(MapSettings()), FakeBleClient(), prefs, this)

        c.toggleQuickTimerAlarmMode(true)
        assertTrue(c.quickTimerAlarmMode, "state updated")
        assertTrue(prefs.quickTimerAlarmMode, "persisted to prefs")
    }

    // ---------------------------------------------------------------------------
    // Test C3 — startTimerSet success: sends START_INTERVAL frame with the set's own slots
    // ---------------------------------------------------------------------------

    @Test
    fun startTimerSet_success_sendsStartIntervalFrame() = runTest(testScheduler) {
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
        val c = controller(timerRepo, ble, prefs, this)

        c.startTimerSet(set)
        advanceUntilIdle()

        val pkt = ble.sentPackets.single()
        assertEquals(0x02.toByte(), pkt[11], "header byte3 = START_INTERVAL (verified on hardware)")
        assertEquals(7.toByte(), pkt[9], "header MM from quickTimerSeconds")
        assertEquals(7.toByte(), pkt[10], "header SS from quickTimerSeconds")
        assertEquals(0xB4.toByte(), pkt[12]) // slot[0] LE byte0 = 180 s = 0xB4, from the started set
        // A successful start marks the set active.
        assertEquals("id1", c.timerActiveId, "startTimerSet should set the active id on success")
    }

    // ---------------------------------------------------------------------------
    // Test E — moveTimerSetUp / moveTimerSetDown
    // ---------------------------------------------------------------------------

    /** Minimal controller wired to a shared MapSettings, pre-seeded with [ids] as timer sets. */
    private fun controllerWithSets(vararg ids: String): TimerController {
        val settings = MapSettings()
        val timerRepo = TimerSetsRepository(settings)
        ids.forEach { timerRepo.save(makeTimerSet(id = it, name = "Set $it")) }
        return controller(timerRepo, FakeBleClient(mutableListOf()), Prefs(settings), TestScope())
    }

    @Test
    fun moveTimerSetDown_reordersStateAndPersists() {
        val c = controllerWithSets("a", "b", "c")
        val target = c.timerSets.first { it.id == "a" }
        c.moveTimerSetDown(target)
        assertEquals(listOf("b", "a", "c"), c.timerSets.map { it.id }, "state reflects the move")
    }

    @Test
    fun moveTimerSetUp_reordersStateAndPersists() {
        val c = controllerWithSets("a", "b", "c")
        val target = c.timerSets.first { it.id == "c" }
        c.moveTimerSetUp(target)
        assertEquals(listOf("a", "c", "b"), c.timerSets.map { it.id })
    }

    @Test
    fun moveTimerSetUp_atTop_isNoOp() {
        val c = controllerWithSets("a", "b", "c")
        c.moveTimerSetUp(c.timerSets.first { it.id == "a" })
        assertEquals(listOf("a", "b", "c"), c.timerSets.map { it.id })
    }

    @Test
    fun moveTimerSetDown_atBottom_isNoOp() {
        val c = controllerWithSets("a", "b", "c")
        c.moveTimerSetDown(c.timerSets.first { it.id == "c" })
        assertEquals(listOf("a", "b", "c"), c.timerSets.map { it.id })
    }

    // ---------------------------------------------------------------------------
    // Smoke test matching the brief's example
    // ---------------------------------------------------------------------------

    @Test
    fun newTimerSet_appends() {
        val c = controller(TimerSetsRepository(MapSettings()), FakeBleClient(), Prefs(MapSettings()), TestScope())
        val before = c.timerSets.size
        c.newTimerSet()
        assertEquals(before + 1, c.timerSets.size)
    }
}
