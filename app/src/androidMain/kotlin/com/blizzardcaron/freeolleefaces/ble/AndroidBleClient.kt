package com.blizzardcaron.freeolleefaces.ble

import android.content.Context

/**
 * Fire-and-forget [BleClient] used by every caller (UI sends, background Workers). All sends funnel
 * through the process-wide [WatchLink], so they ride the foreground-held link when one is open and
 * fall back to one-shot connect/write/disconnect otherwise.
 */
class AndroidBleClient(context: Context) : BleClient {

    // Application context: sends route through the process-wide [WatchLink], never hold an Activity context.
    private val context = context.applicationContext

    override suspend fun send(deviceAddress: String, value: String): Result<Unit> =
        send(deviceAddress, value, OlleeProtocol.TARGET_NAMEPLATE)

    override suspend fun send(deviceAddress: String, value: String, target: Int): Result<Unit> {
        val packet = OlleeProtocol.buildPacket(target, value.padEnd(OlleeProtocol.MAX_VALUE_LENGTH, ' '))
        return WatchLink.send(context, deviceAddress, packet)
    }

    override suspend fun sendPacket(deviceAddress: String, packet: ByteArray): Result<Unit> =
        WatchLink.send(context, deviceAddress, packet)

    override suspend fun sendAndAwait(
        deviceAddress: String, requestPacket: ByteArray, expectedTarget: Int, timeoutMs: Long,
    ): Result<OlleeProtocol.Frame> =
        WatchLink.sendAndAwait(context, deviceAddress, requestPacket, expectedTarget, timeoutMs)
}
