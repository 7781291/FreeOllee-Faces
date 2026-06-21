package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.fakes.FakeScheduler
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.ui.HomeState
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moved from AppViewModel's settings-cluster methods (interval/sleep/auto-sleep setters +
 * onCoordEdit) — the cluster now lives in [SettingsController], constructed directly against
 * fakes + a local [StateHolder] (mirroring [ComplicationControllerTest]/[TimerControllerTest]).
 * The cross-cluster calls into [com.blizzardcaron.freeolleefaces.vm.ComplicationController]
 * (`tempNextText`/`refreshActive`) are injected as plain lambdas so this controller doesn't carry
 * a hard dependency on that class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsControllerTest {

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

    /** Local HomeState holder mirroring AppViewModel's state/update wiring for the controller. */
    private class StateHolder(initial: HomeState = HomeState()) {
        var st = initial
        val state: () -> HomeState = { st }
        val update: ((HomeState) -> HomeState) -> Unit = { t -> st = t(st) }
    }

    private fun controller(
        prefs: Prefs,
        scheduler: FakeScheduler,
        scope: kotlinx.coroutines.CoroutineScope,
        holder: StateHolder = StateHolder(),
        tempNextText: () -> String = { "Next update 12:00 AM" },
        refreshActive: (force: Boolean, push: Boolean) -> Unit = { _, _ -> },
    ) = SettingsController(
        prefs = prefs,
        scheduler = scheduler,
        scope = scope,
        update = holder.update,
        tempNextText = tempNextText,
        refreshActive = refreshActive,
    )

    // ---------------------------------------------------------------------------
    // setInterval — persists, updates state (incl. injected tempNextText), reschedules
    // ---------------------------------------------------------------------------

    @Test
    fun setInterval_persistsUpdatesStateAndReschedules() {
        val callLog = mutableListOf<String>()
        val prefs = Prefs(MapSettings())
        val scheduler = FakeScheduler(callLog)
        val holder = StateHolder()
        val c = controller(prefs, scheduler, TestScope(), holder = holder, tempNextText = { "Next update 3:15 PM" })

        c.setInterval(30)

        assertEquals(30, prefs.updateIntervalMinutes, "prefs.updateIntervalMinutes should be persisted")
        assertEquals(30, holder.st.updateIntervalMinutes, "state.updateIntervalMinutes should be updated")
        assertEquals("Next update 3:15 PM", holder.st.tempNext, "state.tempNext should reflect injected tempNextText")
        assertEquals(listOf("scheduler.reschedule"), callLog, "scheduler.reschedule should be called")
    }

    // ---------------------------------------------------------------------------
    // power-saving setters
    // ---------------------------------------------------------------------------

    @Test
    fun setQuietHoursStart_persists_updatesState_andReschedules() {
        val callLog = mutableListOf<String>()
        val prefs = Prefs(MapSettings())
        val scheduler = FakeScheduler(callLog)
        val holder = StateHolder()
        val c = controller(prefs, scheduler, TestScope(), holder = holder, tempNextText = { "Next 3:00 AM" })

        c.setQuietHoursStart(120)

        assertEquals(120, prefs.quietHoursStartMin, "prefs.quietHoursStartMin persisted")
        assertEquals(120, holder.st.quietHoursStartMin, "state.quietHoursStartMin updated")
        assertEquals("Next 3:00 AM", holder.st.tempNext, "tempNext reflects injected text")
        assertEquals(listOf("scheduler.reschedule"), callLog, "window change reschedules")
    }

    @Test
    fun setScreenSleepTimeout_persists_updatesState_noReschedule() {
        val callLog = mutableListOf<String>()
        val prefs = Prefs(MapSettings())
        val scheduler = FakeScheduler(callLog)
        val holder = StateHolder()
        val c = controller(prefs, scheduler, TestScope(), holder = holder)

        c.setScreenSleepTimeout(30)

        assertEquals(30, prefs.screenSleepTimeoutSec, "prefs.screenSleepTimeoutSec persisted")
        assertEquals(30, holder.st.screenSleepTimeoutSec, "state.screenSleepTimeoutSec updated")
        assertEquals(emptyList(), callLog, "timeout change does not reschedule")
    }

    @Test
    fun setPowerSavingEnabled_persists_updatesState_andReschedules() {
        val callLog = mutableListOf<String>()
        val prefs = Prefs(MapSettings())
        val scheduler = FakeScheduler(callLog)
        val holder = StateHolder()
        val c = controller(prefs, scheduler, TestScope(), holder = holder, tempNextText = { "Next 9:00 PM" })

        c.setPowerSavingEnabled(false)

        assertEquals(false, prefs.powerSavingEnabled, "prefs.powerSavingEnabled persisted")
        assertEquals(false, holder.st.powerSavingEnabled, "state.powerSavingEnabled updated")
        assertEquals("Next 9:00 PM", holder.st.tempNext, "tempNext reflects injected text")
        assertEquals(listOf("scheduler.reschedule"), callLog, "power-saving toggle reschedules")
    }

    // ---------------------------------------------------------------------------
    // onCoordEdit — valid coords persist + update state; invalid coords don't persist
    // ---------------------------------------------------------------------------

    @Test
    fun onCoordEdit_validCoords_persistsAndUpdatesState() = runTest(testScheduler) {
        val prefs = Prefs(MapSettings())
        val holder = StateHolder()
        var refreshActiveCalls = 0
        val c = controller(
            prefs, FakeScheduler(), this, holder = holder,
            refreshActive = { _, _ -> refreshActiveCalls++ },
        )

        c.onCoordEdit("40.7128", "-74.0060")

        // Synchronous: lat/lng text + persisted prefs + location label updated immediately.
        assertEquals("40.7128", holder.st.lat)
        assertEquals("-74.0060", holder.st.lng)
        // Prefs stores lat/lng as Float, so compare with a tolerance for the Double->Float->Double round-trip.
        assertTrue(abs(40.7128 - (prefs.lastLat ?: 0.0)) < 0.001, "prefs.lastLat should be persisted: ${prefs.lastLat}")
        assertTrue(abs(-74.0060 - (prefs.lastLng ?: 0.0)) < 0.001, "prefs.lastLng should be persisted: ${prefs.lastLng}")
        assertEquals("just now", holder.st.locationFreshness)

        advanceUntilIdle()   // drain the 500ms debounce

        assertEquals(1, refreshActiveCalls, "debounced refreshActive(force=false, push=false) should fire once")
    }

    @Test
    fun onCoordEdit_invalidCoords_doesNotPersist() = runTest(testScheduler) {
        val prefs = Prefs(MapSettings())
        val holder = StateHolder()
        val c = controller(prefs, FakeScheduler(), this, holder = holder)

        c.onCoordEdit("not-a-number", "-74.0060")
        advanceUntilIdle()

        assertEquals("not-a-number", holder.st.lat, "raw text is still reflected in state")
        assertNull(prefs.lastLat, "invalid coords should not be persisted to prefs")
        assertNull(prefs.lastLng, "invalid coords should not be persisted to prefs")
    }
}
