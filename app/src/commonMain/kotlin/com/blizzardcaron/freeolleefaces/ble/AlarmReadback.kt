package com.blizzardcaron.freeolleefaces.ble

/**
 * Read-back confirmation for the single watch alarm: read `0x2B`→`0x4B`, compare via [AlarmConfirm],
 * and on mismatch (or read failure) re-send [packet] exactly once and re-read. Returns true only if
 * the watch is confirmed holding the intended alarm; a false return is the caller's signal to route
 * into the existing failure path. No looping — at most one heal.
 */
object AlarmReadback {
    suspend fun confirm(
        ble: BleClient, address: String, packet: ByteArray,
        enabled: Boolean, hour: Int, minute: Int, chimeIndex: Int,
    ): Boolean {
        suspend fun reads(): Boolean {
            val reply = ble.sendAndAwait(
                address,
                OlleeProtocol.readRequest(OlleeProtocol.TARGET_GET_ALARM),
                OlleeProtocol.TARGET_GET_ALARM + OlleeProtocol.RESPONSE_TARGET_OFFSET,
            )
            return reply.getOrNull()?.let { AlarmConfirm.matches(enabled, hour, minute, chimeIndex, it) } ?: false
        }
        if (reads()) return true
        ble.sendPacket(address, packet)   // heal once
        return reads()
    }
}
