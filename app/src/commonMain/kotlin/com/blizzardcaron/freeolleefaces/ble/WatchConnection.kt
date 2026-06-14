package com.blizzardcaron.freeolleefaces.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The foreground-only held watch link, as the ViewModel sees it. Backed in production by the
 * process-wide `WatchLink` singleton (androidMain); all sends ride the same link via [BleClient].
 */
interface WatchConnection {
    /** Live link status; emits on every transition (Connecting → Connected/NotReachable, etc.). */
    val status: StateFlow<ConnectionStatus>

    /** Open and hold the link to [address], driving [status]. Suspends until the attempt resolves. */
    suspend fun connect(address: String)

    /** Release the held link. */
    fun disconnect()
}

/** Default no-op used when no real connection is wired (keeps non-Android callers/tests simple). */
object NoopWatchConnection : WatchConnection {
    private val _status = MutableStateFlow(ConnectionStatus.NoWatch)
    override val status: StateFlow<ConnectionStatus> = _status
    override suspend fun connect(address: String) {}
    override fun disconnect() {}
}
