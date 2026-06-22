package com.blizzardcaron.freeolleefaces.instruments

import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.activity.NameplatePusher
import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.location.Coords
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Orchestrates a live Instruments session: ingests GPS (heading/altitude), pressure, and onboard
 * temperature, renders the selected [Instrument], and pushes it to the watch nameplate via the
 * shared [NameplatePusher]. Records no track. Owns no coroutines — the Android host drives
 * ingest/[tick].
 */
class InstrumentsSessionEngine(
    ble: BleClient,
    private val unitProvider: () -> ActivityUnit,
    private val tempUnitProvider: () -> TempUnit,
    private val watchAddress: () -> String?,
) {
    private val pusher = NameplatePusher(ble)
    private val _state = MutableStateFlow(InstrumentsState())
    val state: StateFlow<InstrumentsState> = _state.asStateFlow()
    private var selected = Instrument.COMPASS

    fun start() {
        selected = Instrument.COMPASS
        _state.value = InstrumentsState(running = true, selectedInstrument = selected)
    }

    fun ingestLocation(coords: Coords) {
        val cur = _state.value
        val heading =
            if (coords.bearingDeg != null && (coords.speedMps ?: 0f) >= SPEED_GATE_MPS) {
                coords.bearingDeg
            } else {
                cur.headingDeg
            }
        _state.value = cur.copy(headingDeg = heading, altitudeM = coords.altM ?: cur.altitudeM)
    }

    fun ingestPressure(hpa: Double?, source: PressureSource) {
        _state.value = _state.value.copy(pressureHpa = hpa, pressureSource = source)
    }

    fun ingestTemp(tempF: Int?) {
        _state.value = _state.value.copy(onboardTempF = tempF)
    }

    suspend fun tick(nowMs: Long) {
        val cur = _state.value
        if (!cur.running) return
        val raw = selected.render(cur, unitProvider(), tempUnitProvider())
        val reachable = pusher.maybePush(watchAddress(), raw, nowMs, cur.watchReachable)
        _state.value = _state.value.copy(watchReachable = reachable, lastPushText = pusher.lastPushText)
    }

    fun cycleInstrument() {
        selected = selected.next()
        pusher.forceNext()
        _state.value = _state.value.copy(selectedInstrument = selected)
    }

    fun onUnitChanged() = pusher.forceNext()

    fun stop() {
        _state.value = InstrumentsState()
    }

    companion object {
        const val SPEED_GATE_MPS = 0.5f
    }
}
