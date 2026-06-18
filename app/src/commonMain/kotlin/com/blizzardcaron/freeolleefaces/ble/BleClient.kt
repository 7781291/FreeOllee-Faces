package com.blizzardcaron.freeolleefaces.ble

/**
 * Sends Ollee protocol frames to the watch over BLE. Platform implementations own connection
 * management and retries; callers just hand over an address and a value/packet.
 */
interface BleClient {

    /** Sends [value] to the nameplate field (`0x2F`). */
    suspend fun send(deviceAddress: String, value: String): Result<Unit>

    /**
     * Sends [value] to an arbitrary BLE [target] field. Defaults route through the
     * nameplate (0x2F); pass [OlleeProtocol.TARGET_TEMPERATURE] (0x2E) to test writing
     * the Temperature face's field directly.
     */
    suspend fun send(deviceAddress: String, value: String, target: Int): Result<Unit>

    /**
     * Sends a fully-built [packet] (e.g. from [OlleeProtocol.buildWeekdayPacket] /
     * [OlleeProtocol.buildRawPacket]) without the ASCII/6-char nameplate framing path.
     */
    suspend fun sendPacket(deviceAddress: String, packet: ByteArray): Result<Unit>

    /**
     * Sends a `02 <target>` read request and awaits the matching notify reply (its target is the
     * request target + [OlleeProtocol.RESPONSE_TARGET_OFFSET]). Returns the parsed reply frame, or
     * [Result.failure] on timeout, link loss, or no matching reply.
     */
    suspend fun sendAndAwait(
        deviceAddress: String,
        requestPacket: ByteArray,
        expectedTarget: Int,
        timeoutMs: Long = 5_000L,
    ): Result<OlleeProtocol.Frame>
}
