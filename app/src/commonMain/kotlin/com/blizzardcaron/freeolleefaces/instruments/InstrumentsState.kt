package com.blizzardcaron.freeolleefaces.instruments

/** Where the current pressure reading came from (drives the in-app source note). */
enum class PressureSource { SENSOR, NETWORK, NONE }

/** Immutable live state of the running Instruments session (not persisted). */
data class InstrumentsState(
    val running: Boolean = false,
    val selectedInstrument: Instrument = Instrument.COMPASS,
    val headingDeg: Float? = null,
    val altitudeM: Double? = null,
    val pressureHpa: Double? = null,
    val pressureSource: PressureSource = PressureSource.NONE,
    val onboardTempF: Int? = null,
    val watchReachable: Boolean = true,
    val lastPushText: String? = null,
)
