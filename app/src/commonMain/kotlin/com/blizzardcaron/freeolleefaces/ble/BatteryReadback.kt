package com.blizzardcaron.freeolleefaces.ble

/**
 * Reads the watch battery voltage: request `0x2A`, await the `0x4A` firmware/version reply, slice
 * the millivolt field out of its payload. Returns null on timeout / link loss (watch unreachable)
 * or a malformed payload. Mirrors [TemperatureReadback]. Byte layout confirmed on-device against
 * watch 00:80:E1:26:DC:86 (fw 00.01.07): the millivolt field is the trailing big-endian uint16 of
 * the 36-byte 0x4A payload (offset 34) — 0x0B1B == 2843 mV matched the official app's "2.843 V".
 */
object BatteryReadback {
    /** Byte offset of the big-endian uint16 millivolt field (trailing u16) within the 0x4A payload. */
    const val VOLTAGE_OFFSET = 34
    private const val U16_WIDTH = 2
    private const val BYTE_MASK = 0xFF
    private const val HIGH_BYTE_SHIFT = 8

    suspend fun read(ble: BleClient, address: String): Int? {
        val reply = ble.sendAndAwait(
            address,
            OlleeProtocol.readRequest(OlleeProtocol.TARGET_VERSION),
            OlleeProtocol.TARGET_VERSION + OlleeProtocol.RESPONSE_TARGET_OFFSET,
        )
        return reply.getOrNull()?.let { parseMilliVolts(it.payload) }
    }

    fun parseMilliVolts(payload: ByteArray): Int? {
        if (payload.size < VOLTAGE_OFFSET + U16_WIDTH) return null
        val hi = payload[VOLTAGE_OFFSET].toInt() and BYTE_MASK
        val lo = payload[VOLTAGE_OFFSET + 1].toInt() and BYTE_MASK
        return (hi shl HIGH_BYTE_SHIFT) or lo
    }
}
