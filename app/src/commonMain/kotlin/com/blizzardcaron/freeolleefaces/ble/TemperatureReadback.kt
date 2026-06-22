package com.blizzardcaron.freeolleefaces.ble

/**
 * Reads the watch's onboard temperature: request `0x2E`, await the `0x4E` reply, parse its "  54 F"
 * field to °F. Returns null on timeout/link loss (Temperature face not enabled in the Ollee app, or
 * watch unreachable) or a malformed payload. This is a READ — distinct from the experimental write
 * to 0x2E in OlleeProtocol.
 */
object TemperatureReadback {
    suspend fun read(ble: BleClient, address: String): Int? {
        val reply = ble.sendAndAwait(
            address,
            OlleeProtocol.readRequest(OlleeProtocol.TARGET_TEMPERATURE),
            OlleeProtocol.TARGET_TEMPERATURE + OlleeProtocol.RESPONSE_TARGET_OFFSET,
        )
        return reply.getOrNull()?.let { parseTempF(it.payload) }
    }

    fun parseTempF(payload: ByteArray): Int? {
        val text = payload.decodeToString().trim()
        val token = text.takeWhile { it.isDigit() || it == '-' }
        return token.toIntOrNull()
    }
}
