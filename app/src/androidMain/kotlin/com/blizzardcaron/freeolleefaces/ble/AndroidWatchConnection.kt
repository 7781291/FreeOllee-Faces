package com.blizzardcaron.freeolleefaces.ble

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** [WatchConnection] backed by the process-wide [WatchLink] singleton. */
class AndroidWatchConnection(private val context: Context) : WatchConnection {
    override val status: StateFlow<ConnectionStatus> = WatchLink.status
    override suspend fun connect(address: String) = WatchLink.connectHeld(context, address)
    override fun disconnect() = WatchLink.disconnectHeld()
}
