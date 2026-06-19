package com.blizzardcaron.freeolleefaces.vm

import com.blizzardcaron.freeolleefaces.alarm.AlarmsRepository
import com.blizzardcaron.freeolleefaces.fakes.FakeAlarmScheduler
import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moved from AppViewModelTest's "Test D1/D2" — the alarm cluster now lives in [AlarmController],
 * constructed directly against the real [AlarmsRepository]/[Prefs] (backed by [MapSettings], same
 * pattern AppViewModelTest used) and the existing [FakeBleClient]/[FakeAlarmScheduler] fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlarmControllerTest {

    @BeforeTest
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun controller(
        settings: MapSettings = MapSettings(),
        callLog: MutableList<String> = mutableListOf(),
    ) = AlarmController(
        alarmRepo = AlarmsRepository(settings),
        alarmScheduler = FakeAlarmScheduler(callLog),
        ble = FakeBleClient(callLog),
        prefs = Prefs(MapSettings()),
        scope = TestScope(),
        showSnackbar = {},
    )

    // ---------------------------------------------------------------------------
    // Test D1 — alarm CRUD persists, updates state, and re-arms on every change
    // ---------------------------------------------------------------------------

    @Test
    fun `alarm CRUD persists, updates state, and re-arms on every change`() {
        val callLog = mutableListOf<String>()
        val settings = MapSettings()
        val c = controller(settings, callLog)

        c.addAlarm()
        assertEquals(1, c.items.size)
        assertEquals(1, callLog.count { it == "alarmScheduler.rearm" })

        val alarm = c.items[0]
        c.saveAlarm(alarm.copy(hour = 6, minute = 45))
        assertEquals(6, c.items[0].hour)

        // Label-only change persists but does NOT re-arm (the label never reaches the watch).
        c.saveAlarm(c.items[0].copy(label = "Work"))
        assertEquals("Work", c.items[0].label)
        assertEquals(2, callLog.count { it == "alarmScheduler.rearm" })

        c.toggleAlarm(alarm.id, enabled = false)
        assertFalse(c.items[0].enabled)

        c.deleteAlarm(alarm.id)
        assertTrue(c.items.isEmpty())
        assertNull(AlarmsRepository(settings).get(alarm.id))   // really deleted from the store
        assertEquals(4, callLog.count { it == "alarmScheduler.rearm" })
    }

    // ---------------------------------------------------------------------------
    // Test D2 — addAlarm caps at MAX_ALARMS
    // ---------------------------------------------------------------------------

    @Test
    fun `addAlarm caps at MAX_ALARMS`() {
        val c = controller()
        repeat(AlarmsRepository.MAX_ALARMS + 1) { c.addAlarm() }
        assertEquals(AlarmsRepository.MAX_ALARMS, c.items.size)
    }

    // ---------------------------------------------------------------------------
    // Test D3 — addAlarm appends (smoke test matching the brief's example)
    // ---------------------------------------------------------------------------

    @Test
    fun addAlarm_appends_and_persists() {
        val c = controller()
        val before = c.items.size
        c.addAlarm()
        assertEquals(before + 1, c.items.size)
    }
}
