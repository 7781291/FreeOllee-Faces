package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.location.Coords
import kotlinx.coroutines.flow.Flow

/** A continuous stream of device location fixes for an active session. Platform impl owns the I/O. */
interface LocationStream {
    fun stream(): Flow<Coords>
}
