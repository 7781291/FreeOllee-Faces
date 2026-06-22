package com.blizzardcaron.freeolleefaces.instruments

import kotlinx.coroutines.flow.Flow

interface PressureStream {
    fun stream(): Flow<Double?>
}
