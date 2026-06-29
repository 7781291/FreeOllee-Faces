package com.blizzardcaron.freeolleefaces.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Process-wide single connection point to the watch. Holds at most one GATT link (opened on
 * foreground via [connectHeld], released via [disconnectHeld]) and exposes its [status]. Every send
 * goes through [send]: it rides the held link when one is Connected to the same address, otherwise it
 * runs a one-shot connect -> write -> disconnect. A [Mutex] serializes all GATT work — the watch only
 * accepts one client at a time, so a foreground send and a background Worker cooperate here.
 */
// cohesive process-wide BLE connection point; its functions are one responsibility
// (connect/hold/send) and don't split cleanly
@Suppress("TooManyFunctions")
@SuppressLint("MissingPermission")
object WatchLink {

    val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val CHAR_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val WRITE_TIMEOUT_MS = 8_000L
    private const val READ_TIMEOUT_MS = 5_000L

    // The Android GATT stack allows one outstanding op; a read-request write can be transiently
    // rejected while a prior write's ack-notify, the CCCD enable, or a concurrent push is in flight.
    // Retry the write while busy, bounded by READ_TIMEOUT_MS via the surrounding withTimeout.
    private const val READ_WRITE_RETRY_MS = 100L
    private const val READ_WRITE_MAX_ATTEMPTS = 40

    // ATT payload for the watch's default 23-byte MTU (MTU - 3). Longer frames are fragmented across
    // sequential writes and reassembled by the firmware via the LEN field.
    private const val ATT_PAYLOAD = 20

    private val _status = MutableStateFlow(ConnectionStatus.Connecting)
    val status: StateFlow<ConnectionStatus> = _status

    /** Serializes every connect / write — only one client may talk to the watch at a time. */
    private val lock = Mutex()

    /** Process-wide scope for fire-and-forget teardown that must still run under [lock]. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Held-connection state. Mutated under [lock]; the callback only reads/clears via WatchLink helpers.
    private var heldGatt: BluetoothGatt? = null
    private var heldCallback: HeldCallback? = null
    private var heldAddress: String? = null

    private fun ByteArray.toHex(): String =
        joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun chunksOf(packet: ByteArray): List<ByteArray> =
        packet.toList().chunked(ATT_PAYLOAD).map { it.toByteArray() }

    /** One chunk write, across the SDK's pre-/post-Tiramisu API split. Returns false on reject. */
    private fun writeChunk(g: BluetoothGatt, char: BluetoothGattCharacteristic, bytes: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                char.value = bytes
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(char)
            }
        }

    // --- Foreground held connection -------------------------------------------------

    /** Open and hold a link to [address] (idempotent if already Connected to it). Drives [status]. */
    suspend fun connectHeld(context: Context, address: String): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            if (heldGatt != null && heldAddress == address && _status.value == ConnectionStatus.Connected) {
                return@withLock
            }
            closeHeldLocked()
            _status.value = ConnectionStatus.Connecting
            val ok = runCatching {
                withTimeout(CONNECT_TIMEOUT_MS) { openHeld(context, address) }
            }.onFailure { Log.w("OLLEE_BLE", "connectHeld failed for $address", it) }
                .getOrDefault(false)
            if (ok) {
                _status.value = ConnectionStatus.Connected
            } else {
                Log.w("OLLEE_BLE", "connectHeld: unable to open held link to $address — marking NotReachable")
                closeHeldLocked()
                _status.value = ConnectionStatus.NotReachable
            }
        }
    }

    /**
     * Release the held link (non-suspending — called from the UI lifecycle). Teardown is serialized
     * under [lock] so it can't race a concurrent [send]/[connectHeld] mutating the held-link state;
     * the [_status] flip is published immediately so the UI reflects the disconnect without waiting.
     */
    fun disconnectHeld() {
        _status.value = ConnectionStatus.NotReachable
        scope.launch { lock.withLock { closeHeldLocked() } }
    }

    /** Tear down the held link + clear its state. MUST be called with [lock] held (the name's suffix). */
    private fun closeHeldLocked() {
        runCatching { heldGatt?.disconnect() }
        runCatching { heldGatt?.close() }
        heldGatt = null
        heldCallback = null
        heldAddress = null
    }

    private suspend fun openHeld(context: Context, address: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val manager = context.getSystemService(BluetoothManager::class.java)
            if (manager == null) {
                cont.resume(false)
                return@suspendCancellableCoroutine
            }
            val device: BluetoothDevice = manager.adapter.getRemoteDevice(address)
            val cb = HeldCallback().apply { connectCont = cont }
            heldCallback = cb
            heldAddress = address
            heldGatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            // On timeout/cancel the connectHeld runCatching path calls closeHeldLocked() to tear down.
            cont.invokeOnCancellation { }
        }

    /** Called by the held callback when the link drops unexpectedly. */
    private fun onHeldDropped() {
        if (_status.value == ConnectionStatus.Connected) _status.value = ConnectionStatus.NotReachable
    }

    // --- Unified send ---------------------------------------------------------------

    /** Send [packet] to [address], riding the held link if Connected, else one-shot. */
    suspend fun send(context: Context, address: String, packet: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            // Debug builds: log every outgoing frame on the OLLEE_BLE tag the capture rig filters.
            if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                Log.i("OLLEE_BLE", "FreeOllee TX $address ${packet.toHex()}")
            }
            lock.withLock {
                val sameConnectedLink = heldAddress == address && _status.value == ConnectionStatus.Connected
                if (sameConnectedLink) {
                    val g = heldGatt
                    val cb = heldCallback
                    val char = cb?.writeChar
                    if (g != null && cb != null && char != null) {
                        val held = runCatching {
                            withTimeout(WRITE_TIMEOUT_MS) { writeHeld(g, cb, char, packet) }
                        }.getOrElse { Result.failure(it) }
                        if (held.isSuccess) return@withLock held
                        // Held write failed — drop the link and fall back to a fresh one-shot.
                        closeHeldLocked()
                        _status.value = ConnectionStatus.NotReachable
                    }
                }
                oneShotSend(context, address, packet)
            }
        }

    private suspend fun writeHeld(
        g: BluetoothGatt,
        cb: HeldCallback,
        char: BluetoothGattCharacteristic,
        packet: ByteArray,
    ): Result<Unit> = suspendCancellableCoroutine { cont ->
        cb.writeChunks = chunksOf(packet)
        cb.writeIndex = 0
        cb.writeCont = cont
        if (!writeChunk(g, char, cb.writeChunks[0])) {
            cb.writeCont = null
            cont.resume(Result.failure(IllegalStateException("writeCharacteristic returned false")))
        }
    }

    /**
     * Write [requestPacket] and await the notify reply whose target == [expectedTarget]. Ensures a
     * connection (reusing the held link when it is already Connected to [address], else opening one),
     * subscribes to notify during service discovery, and resumes on the first matching frame.
     */
    suspend fun sendAndAwait(
        context: Context,
        address: String,
        requestPacket: ByteArray,
        expectedTarget: Int,
        timeoutMs: Long = READ_TIMEOUT_MS,
    ): Result<OlleeProtocol.Frame> = withContext(Dispatchers.IO) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.i("OLLEE_BLE", "FreeOllee TX(read) $address ${requestPacket.toHex()} expect=$expectedTarget")
        }
        lock.withLock {
            val connected = heldGatt != null && heldAddress == address &&
                _status.value == ConnectionStatus.Connected
            if (!connected) {
                closeHeldLocked()
                _status.value = ConnectionStatus.Connecting
                val ok = runCatching { withTimeout(CONNECT_TIMEOUT_MS) { openHeld(context, address) } }
                    .getOrDefault(false)
                if (!ok) {
                    closeHeldLocked()
                    _status.value = ConnectionStatus.NotReachable
                    return@withLock Result.failure(IllegalStateException("connect failed"))
                }
                _status.value = ConnectionStatus.Connected
            }
            val g = heldGatt
            val cb = heldCallback
            val char = cb?.writeChar
            if (g == null || cb == null || char == null) {
                return@withLock Result.failure(IllegalStateException("no link"))
            }
            runCatching {
                withTimeout(timeoutMs) {
                    cb.reassembler.reset()
                    cb.writeCont = null // request write ack is irrelevant; we wait for the notify
                    val chunk = chunksOf(requestPacket)[0] // a read request is a single 8-byte chunk
                    // Issue the read request, retrying while the GATT stack is transiently busy. The
                    // watch cannot reply before it receives the request, so awaitTarget/awaitCont are
                    // set only after the write is accepted — no reply can be lost in between.
                    var attempt = 0
                    while (!writeChunk(g, char, chunk)) {
                        if (++attempt >= READ_WRITE_MAX_ATTEMPTS) {
                            error("read request write stayed busy")
                        }
                        delay(READ_WRITE_RETRY_MS)
                    }
                    suspendCancellableCoroutine<Result<OlleeProtocol.Frame>> { cont ->
                        cb.awaitTarget = expectedTarget
                        cb.awaitCont = cont
                        cont.invokeOnCancellation {
                            cb.awaitCont = null
                            cb.awaitTarget = null
                        }
                    }
                }
            }.getOrElse { Result.failure(it) }.also { r ->
                // Debug builds: mirror the TX log — record the notify reply on the OLLEE_BLE tag.
                if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    r.onSuccess { f ->
                        Log.i(
                            "OLLEE_BLE",
                            "FreeOllee RX $address target=0x${f.target.toString(16)} " +
                                "payload=${f.payload.toHex()} crcOk=${f.crcOk}"
                        )
                    }
                }
            }
        }
    }

    // --- One-shot fallback: connect -> write -> disconnect, with retry ----------------

    // NOTE: callers run oneShotSend inside [lock], so a background-Worker retry storm (worst case
    // ~30s across MAX_ATTEMPTS + backoffs) will block a concurrent connectHeld (e.g. a Reconnect tap)
    // until it finishes. Acceptable for now — revisit the lock scope if on-device use shows the
    // Reconnect button stalling behind background sends.
    // retry wrapper: catches any transient BLE failure, then delegates the retry/abort decision to
    // BleRetryPolicy.isRetryable
    @Suppress("TooGenericExceptionCaught")
    private suspend fun oneShotSend(context: Context, address: String, packet: ByteArray): Result<Unit> =
        runCatching {
            val manager = context.getSystemService(BluetoothManager::class.java)
                ?: error("BluetoothManager unavailable")
            val device: BluetoothDevice = manager.adapter.getRemoteDevice(address)
            for (attempt in 0 until BleRetryPolicy.MAX_ATTEMPTS) {
                val isLastAttempt = attempt == BleRetryPolicy.MAX_ATTEMPTS - 1
                try {
                    withTimeout(CONNECT_TIMEOUT_MS) { oneShotWrite(context, device, packet) }
                    return@runCatching
                } catch (timeout: TimeoutCancellationException) {
                    if (isLastAttempt) throw timeout
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    if (isLastAttempt || !BleRetryPolicy.isRetryable(error)) throw error
                }
                delay(BleRetryPolicy.backoffForAttempt(attempt))
            }
        }

    /**
     * Look up the notify characteristic, store it on [cb], and — if present — subscribe via CCCD
     * write across the SDK's pre-/post-Tiramisu API split. Called from [HeldCallback.onServicesDiscovered]
     * once the write characteristic has been resolved.
     */
    private fun subscribeNotify(g: BluetoothGatt, cb: HeldCallback) {
        val nc = g.getService(SERVICE_UUID)?.getCharacteristic(NOTIFY_CHAR_UUID)
        cb.notifyChar = nc
        if (nc == null) return
        g.setCharacteristicNotification(nc, true)
        val d = nc.getDescriptor(CCCD_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
        }
    }

    private suspend fun oneShotWrite(context: Context, device: BluetoothDevice, packet: ByteArray) =
        suspendCancellableCoroutine<Unit> { cont ->
            var gatt: BluetoothGatt? = null
            val chunks = chunksOf(packet)
            var next = 0

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("connection lost: status=$status"))
                        }
                        g.close()
                    }
                }

                // async GATT discovery: each validation stage must resume-with-exception + disconnect +
                // bail; early returns are the clearest, safest form
                @Suppress("ReturnCount")
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (!cont.isActive) {
                        g.disconnect()
                        return
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        cont.resumeWithException(IllegalStateException("service discovery failed: $status"))
                        g.disconnect()
                        return
                    }
                    val service = g.getService(SERVICE_UUID) ?: run {
                        cont.resumeWithException(IllegalStateException("Nordic UART service not found"))
                        g.disconnect()
                        return
                    }
                    val char = service.getCharacteristic(CHAR_UUID) ?: run {
                        cont.resumeWithException(IllegalStateException("RX characteristic not found"))
                        g.disconnect()
                        return
                    }
                    if (!writeChunk(g, char, chunks[next])) {
                        cont.resumeWithException(IllegalStateException("writeCharacteristic returned false"))
                        g.disconnect()
                    }
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (!cont.isActive) {
                        g.disconnect()
                        return
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        cont.resumeWithException(IllegalStateException("write failed: $status"))
                        g.disconnect()
                        return
                    }
                    next++
                    if (next >= chunks.size) {
                        cont.resume(Unit)
                        g.disconnect()
                    } else if (!writeChunk(g, characteristic, chunks[next])) {
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

    // --- Held-connection callback (long-lived: spans connect + every write) ---------

    private class HeldCallback : BluetoothGattCallback() {
        @Volatile var connectCont: CancellableContinuation<Boolean>? = null

        @Volatile var writeCont: CancellableContinuation<Result<Unit>>? = null

        @Volatile var writeChar: BluetoothGattCharacteristic? = null

        // Written under [lock] in writeHeld, read/incremented from the (serialized) GATT callback
        // thread in onCharacteristicWrite — @Volatile makes those cross-thread reads well-defined.
        @Volatile var writeChunks: List<ByteArray> = emptyList()

        @Volatile var writeIndex: Int = 0

        @Volatile var notifyChar: BluetoothGattCharacteristic? = null

        @Volatile var awaitTarget: Int? = null

        @Volatile var awaitCont: CancellableContinuation<Result<OlleeProtocol.Frame>>? = null
        val reassembler = NotifyFrameReassembler()

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectCont?.let { if (it.isActive) it.resume(false) }
                connectCont = null
                writeCont?.let {
                    if (it.isActive) it.resume(Result.failure(IllegalStateException("link dropped: status=$status")))
                }
                writeCont = null
                awaitCont?.let {
                    if (it.isActive) it.resume(Result.failure(IllegalStateException("link dropped: status=$status")))
                }
                awaitCont = null
                awaitTarget = null
                WatchLink.onHeldDropped()
                g.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val cc = connectCont
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectCont = null
                cc?.resume(false)
                g.disconnect()
                return
            }
            val char = g.getService(WatchLink.SERVICE_UUID)?.getCharacteristic(WatchLink.CHAR_UUID)
            if (char == null) {
                connectCont = null
                cc?.resume(false)
                g.disconnect()
                return
            }
            writeChar = char
            WatchLink.subscribeNotify(g, this)
            connectCont = null
            cc?.resume(true)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val wc = writeCont ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                writeCont = null
                wc.resume(Result.failure(IllegalStateException("write failed: $status")))
                return
            }
            writeIndex++
            if (writeIndex >= writeChunks.size) {
                writeCont = null
                wc.resume(Result.success(Unit))
            } else if (!WatchLink.writeChunk(g, characteristic, writeChunks[writeIndex])) {
                writeCont = null
                wc.resume(Result.failure(IllegalStateException("writeCharacteristic returned false")))
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotify(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotify(characteristic.uuid, value)
        }

        private fun handleNotify(uuid: UUID, value: ByteArray?) {
            if (uuid != WatchLink.NOTIFY_CHAR_UUID || value == null) return
            val frame = reassembler.offer(value)
            if (frame != null && isAwaitedMatch(frame)) {
                val c = awaitCont
                awaitCont = null
                awaitTarget = null
                c?.let { if (it.isActive) it.resume(Result.success(frame)) }
            }
        }

        /** True when [frame] is the one currently awaited by [sendAndAwait][WatchLink.sendAndAwait]. */
        private fun isAwaitedMatch(frame: OlleeProtocol.Frame): Boolean {
            val want = awaitTarget ?: return false
            return frame.target == want && frame.crcOk
        }
    }
}
