package com.blizzardcaron.freeolleefaces.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OlleeBleClient(private val context: Context) {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private const val CONNECT_TIMEOUT_MS = 8_000L
        // ATT payload for the watch's default 23-byte MTU (MTU - 3). Frames longer than this are
        // fragmented across sequential writes and reassembled by the firmware via the LEN field.
        private const val ATT_PAYLOAD = 20
    }

    suspend fun send(deviceAddress: String, value: String): Result<Unit> =
        send(deviceAddress, value, OlleeProtocol.TARGET_NAMEPLATE)

    /**
     * Sends [value] to an arbitrary BLE [target] field. Defaults route through the
     * nameplate (0x2F); pass [OlleeProtocol.TARGET_TEMPERATURE] (0x2E) to test writing
     * the Temperature face's field directly.
     */
    @SuppressLint("MissingPermission")
    suspend fun send(deviceAddress: String, value: String, target: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val packet = OlleeProtocol.buildPacket(target, value.padEnd(OlleeProtocol.MAX_VALUE_LENGTH, ' '))

            val manager = context.getSystemService(BluetoothManager::class.java)
                ?: error("BluetoothManager unavailable")
            val device: BluetoothDevice = manager.adapter.getRemoteDevice(deviceAddress)

            withTimeout(CONNECT_TIMEOUT_MS) {
                writePacket(device, packet)
            }
        }
    }

    /**
     * Sends a fully-built [packet] (e.g. from [OlleeProtocol.buildWeekdayPacket] /
     * [OlleeProtocol.buildRawPacket]) without the ASCII/6-char nameplate framing path.
     */
    @SuppressLint("MissingPermission")
    suspend fun sendPacket(deviceAddress: String, packet: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val manager = context.getSystemService(BluetoothManager::class.java)
                ?: error("BluetoothManager unavailable")
            val device: BluetoothDevice = manager.adapter.getRemoteDevice(deviceAddress)

            withTimeout(CONNECT_TIMEOUT_MS) {
                writePacket(device, packet)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writePacket(device: BluetoothDevice, packet: ByteArray) =
        suspendCancellableCoroutine<Unit> { cont ->
            var gatt: BluetoothGatt? = null

            // The watch's ATT MTU stays at the 23-byte default, so messages longer than the 20-byte
            // ATT payload must be fragmented across sequential writes; the firmware reassembles them
            // by the LEN field. Small frames (e.g. the 14-byte nameplate) are a single chunk.
            val chunks = packet.toList().chunked(ATT_PAYLOAD).map { it.toByteArray() }
            var next = 0

            fun writeChunk(g: BluetoothGatt, char: BluetoothGattCharacteristic): Boolean =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(
                        char, chunks[next], BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION") run {
                        char.value = chunks[next]
                        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        g.writeCharacteristic(char)
                    }
                }

            val callback = object : BluetoothGattCallback() {

                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        g.close()
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        cont.resumeWithException(IllegalStateException("service discovery failed: $status"))
                        g.disconnect()
                        return
                    }
                    val service = g.getService(SERVICE_UUID)
                        ?: run {
                            cont.resumeWithException(IllegalStateException("Nordic UART service not found"))
                            g.disconnect()
                            return
                        }
                    val char = service.getCharacteristic(CHAR_UUID)
                        ?: run {
                            cont.resumeWithException(IllegalStateException("RX characteristic not found"))
                            g.disconnect()
                            return
                        }
                    if (!writeChunk(g, char)) {
                        cont.resumeWithException(IllegalStateException("writeCharacteristic returned false"))
                        g.disconnect()
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (!cont.isActive) { g.disconnect(); return }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        cont.resumeWithException(IllegalStateException("write failed: $status"))
                        g.disconnect()
                        return
                    }
                    next++
                    if (next >= chunks.size) {
                        cont.resume(Unit)
                        g.disconnect()
                    } else if (!writeChunk(g, characteristic)) {
                        cont.resumeWithException(IllegalStateException("writeCharacteristic returned false"))
                        g.disconnect()
                    }
                }
            }

            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

            cont.invokeOnCancellation {
                runCatching { gatt?.disconnect() }
                runCatching { gatt?.close() }
            }
        }
}
