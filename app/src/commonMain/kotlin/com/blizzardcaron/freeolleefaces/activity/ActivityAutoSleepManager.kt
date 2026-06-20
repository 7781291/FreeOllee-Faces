package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.ble.AutoSleepApply
import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.prefs.AutoSleepProfile
import com.blizzardcaron.freeolleefaces.prefs.Prefs

/**
 * Owns the watch BLE auto-sleep around an activity: stash the live profile and force auto-sleep
 * OFF at start (so the radio stays connectable for the push loop), restore it at stop. The
 * `activityActive` breadcrumb lets the watchdog (Task 15) restore after a crash.
 */
class ActivityAutoSleepManager(
    private val ble: BleClient,
    private val prefs: Prefs,
) : SessionAutoSleep {
    override suspend fun disableForActivity(address: String): Boolean {
        val current = readConfig(address) ?: return false
        prefs.savedAutoSleepProfile = AutoSleepProfile(current.autoSleepOn, current.periodSec)
        prefs.activityActive = true
        return AutoSleepApply.reconcile(
            ble,
            address,
            AutoSleepProfile(autoSleepOn = false, periodSec = current.periodSec),
        )
    }

    override suspend fun restoreAfterActivity(address: String): Boolean {
        val saved = prefs.savedAutoSleepProfile
        val ok = saved == null || AutoSleepApply.reconcile(ble, address, saved)
        if (ok) {
            prefs.savedAutoSleepProfile = null
            prefs.activityActive = false
        }
        return ok
    }

    private suspend fun readConfig(address: String): OlleeProtocol.WatchConfig? {
        val frame = ble.sendAndAwait(
            address,
            OlleeProtocol.readRequest(OlleeProtocol.TARGET_GET_CONFIG),
            OlleeProtocol.TARGET_GET_CONFIG + OlleeProtocol.RESPONSE_TARGET_OFFSET,
        ).getOrNull() ?: return null
        return OlleeProtocol.parseConfig(frame)
    }
}
