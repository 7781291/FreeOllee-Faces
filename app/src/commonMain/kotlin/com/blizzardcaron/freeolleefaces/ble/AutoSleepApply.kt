package com.blizzardcaron.freeolleefaces.ble

import com.blizzardcaron.freeolleefaces.prefs.AutoSleepProfile

/**
 * Reconciles the watch's auto-sleep register to a desired [AutoSleepProfile] via read-modify-write:
 * read 0x32, and only if the auto-sleep bit/period differ, write 0x33 with every other setting
 * preserved. Never blind-writes — a failed/malformed read aborts. Returns true when the watch is
 * confirmed already-matching or the corrective write succeeded.
 */
object AutoSleepApply {
    suspend fun reconcile(ble: BleClient, address: String, desired: AutoSleepProfile): Boolean {
        val frame = ble.sendAndAwait(
            address,
            OlleeProtocol.readRequest(OlleeProtocol.TARGET_GET_CONFIG),
            OlleeProtocol.TARGET_GET_CONFIG + OlleeProtocol.RESPONSE_TARGET_OFFSET,
        ).getOrNull() ?: return false
        val current = OlleeProtocol.parseConfig(frame) ?: return false

        val matches = current.autoSleepOn == desired.autoSleepOn &&
            (!desired.autoSleepOn || current.periodSec == desired.periodSec)
        if (matches) return true

        val packet = OlleeProtocol.buildConfigPacket(
            current.withAutoSleep(desired.autoSleepOn, desired.periodSec),
        )
        return ble.sendPacket(address, packet).isSuccess
    }
}
