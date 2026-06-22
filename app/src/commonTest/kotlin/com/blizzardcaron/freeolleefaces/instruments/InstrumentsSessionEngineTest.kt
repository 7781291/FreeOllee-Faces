package com.blizzardcaron.freeolleefaces.instruments

import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.location.Coords
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class RecordingBle : BleClient {
    val sent = mutableListOf<String>()
    override suspend fun send(deviceAddress: String, value: String) = send(deviceAddress, value, OlleeProtocol.TARGET_NAMEPLATE)
    override suspend fun send(deviceAddress: String, value: String, target: Int): Result<Unit> { sent += value; return Result.success(Unit) }
    override suspend fun sendPacket(deviceAddress: String, packet: ByteArray) = Result.success(Unit)
    override suspend fun sendAndAwait(deviceAddress: String, requestPacket: ByteArray, expectedTarget: Int, timeoutMs: Long) =
        Result.failure<OlleeProtocol.Frame>(RuntimeException("n/a"))
}

class InstrumentsSessionEngineTest {
    private fun engine(ble: BleClient, unit: ActivityUnit = ActivityUnit.METRIC) =
        InstrumentsSessionEngine(ble, { unit }, { TempUnit.FAHRENHEIT }, { "AA:BB" })

    @Test fun startSelectsCompass() {
        val e = engine(RecordingBle())
        e.start()
        assertEquals(Instrument.COMPASS, e.state.value.selectedInstrument)
        assertEquals(true, e.state.value.running)
    }

    @Test fun movingBearingIsAcceptedStationaryHeld() {
        val e = engine(RecordingBle()); e.start()
        e.ingestLocation(Coords(1.0, 2.0, 5f, "gps", bearingDeg = 270f, speedMps = 1.5f))
        assertEquals(270f, e.state.value.headingDeg)
        // a stationary fix (speed below gate) must not overwrite the held heading
        e.ingestLocation(Coords(1.0, 2.0, 5f, "gps", bearingDeg = 10f, speedMps = 0.1f))
        assertEquals(270f, e.state.value.headingDeg)
    }

    @Test fun tickPushesSelectedInstrumentText() = runTest {
        val ble = RecordingBle(); val e = engine(ble); e.start()
        e.ingestLocation(Coords(1.0, 2.0, 5f, "gps", bearingDeg = 270f, speedMps = 1.5f))
        e.tick(0)
        assertEquals("270#W ", ble.sent.last())
    }

    @Test fun cycleForcesRepushOfNewInstrument() = runTest {
        val ble = RecordingBle(); val e = engine(ble); e.start()
        e.ingestLocation(Coords(1.0, 2.0, 5f, "gps", bearingDeg = 270f, speedMps = 1.5f))
        e.ingestPressure(1013.0, PressureSource.SENSOR)
        e.tick(0)
        e.cycleInstrument() // COMPASS -> ALTITUDE
        e.cycleInstrument() // ALTITUDE -> PRESSURE
        e.tick(100)
        assertEquals("1013", ble.sent.last())
    }

    @Test fun ingestTempUpdatesState() {
        val e = engine(RecordingBle()); e.start()
        e.ingestTemp(54)
        assertEquals(54, e.state.value.onboardTempF)
    }
}
