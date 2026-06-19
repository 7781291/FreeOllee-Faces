package com.blizzardcaron.freeolleefaces.ble

/**
 * Confirms a parsed `0x4B` alarm read-back holds the alarm the app intended. Compares only the
 * app-owned semantic fields (enable, hour, minute, chime); snooze/day-mask/hour-mask bytes the
 * watch echoes or normalizes are ignored. A disarm push (`enabled = false`) is confirmed by the
 * enable byte alone — the watch keeps the old hour/minute behind a cleared enable flag.
 */
object AlarmConfirm {
    fun matches(enabled: Boolean, hour: Int, minute: Int, chimeIndex: Int, frame: OlleeProtocol.Frame): Boolean {
        val p = frame.payload
        val isValidFrame = frame.crcOk &&
            frame.target == OlleeProtocol.TARGET_GET_ALARM + OlleeProtocol.RESPONSE_TARGET_OFFSET &&
            p.size >= 7
        if (!isValidFrame) return false
        val enableByte = p[0].toInt() and 0xFF
        return if (!enabled) {
            enableByte == 0
        } else {
            enableByte == 1 &&
                (p[3].toInt() and 0xFF) == hour &&
                (p[4].toInt() and 0xFF) == minute &&
                (p[6].toInt() and 0xFF) == chimeIndex
        }
    }
}
