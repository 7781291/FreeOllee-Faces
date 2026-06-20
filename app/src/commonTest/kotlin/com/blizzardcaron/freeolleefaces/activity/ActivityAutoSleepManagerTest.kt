package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.fakes.FakeBleClient
import com.blizzardcaron.freeolleefaces.prefs.AutoSleepProfile
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityAutoSleepManagerTest {

    private fun configReply(on: Boolean, period: Int): Result<OlleeProtocol.Frame> {
        val payload = ByteArray(8)
        if (on) payload[3] = 0x40.toByte() // bit 6 of the big-endian mask word
        payload[4] = ((period ushr 24) and 0xFF).toByte()
        payload[5] = ((period ushr 16) and 0xFF).toByte()
        payload[6] = ((period ushr 8) and 0xFF).toByte()
        payload[7] = (period and 0xFF).toByte()
        return Result.success(
            OlleeProtocol.Frame(
                cmd = 0x02,
                target = OlleeProtocol.TARGET_GET_CONFIG + OlleeProtocol.RESPONSE_TARGET_OFFSET,
                payload = payload,
                crcOk = true,
            ),
        )
    }

    @Test fun disable_stashes_prior_profile_sets_flag_and_writes_off() = runTest {
        val ble = FakeBleClient()
        ble.awaitResults.addLast(configReply(on = true, period = 30)) // readConfig: ON/30
        ble.awaitResults.addLast(configReply(on = true, period = 30)) // reconcile's inner read
        val prefs = Prefs(MapSettings())
        val mgr = ActivityAutoSleepManager(ble, prefs)

        val ok = mgr.disableForActivity("AA:BB")

        assertTrue(ok)
        assertEquals(AutoSleepProfile(true, 30), prefs.savedAutoSleepProfile)
        assertTrue(prefs.activityActive)
        assertEquals(1, ble.sentPackets.size) // one config-write turning auto-sleep off
    }

    @Test fun disable_aborts_cleanly_when_config_read_fails() = runTest {
        val ble = FakeBleClient() // default awaitResult is a failure
        val prefs = Prefs(MapSettings())
        val mgr = ActivityAutoSleepManager(ble, prefs)

        val ok = mgr.disableForActivity("AA:BB")

        assertFalse(ok)
        assertNull(prefs.savedAutoSleepProfile)
        assertFalse(prefs.activityActive)
    }

    @Test fun restore_reconciles_to_saved_and_clears_flag() = runTest {
        val ble = FakeBleClient()
        val prefs = Prefs(MapSettings())
        prefs.savedAutoSleepProfile = AutoSleepProfile(true, 30)
        prefs.activityActive = true
        ble.awaitResults.addLast(configReply(on = false, period = 30)) // reconcile read: currently OFF
        val mgr = ActivityAutoSleepManager(ble, prefs)

        val ok = mgr.restoreAfterActivity("AA:BB")

        assertTrue(ok)
        assertNull(prefs.savedAutoSleepProfile)
        assertFalse(prefs.activityActive)
        assertEquals(1, ble.sentPackets.size) // wrote auto-sleep back ON
    }
}
