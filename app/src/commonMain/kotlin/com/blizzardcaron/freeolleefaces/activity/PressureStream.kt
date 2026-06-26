package com.blizzardcaron.freeolleefaces.activity

import kotlinx.coroutines.flow.Flow

/** Emits barometric pressure (hPa) from the device sensor; empty stream if there's no barometer. */
interface PressureStream {
    fun stream(): Flow<Double?>
}
