package com.blizzardcaron.freeolleefaces.instruments

import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.format.TempUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class InstrumentRenderTest {
    private fun state(
        heading: Float? = null, altM: Double? = null, hpa: Double? = null, tempF: Int? = null,
    ) = InstrumentsState(headingDeg = heading, altitudeM = altM, pressureHpa = hpa, onboardTempF = tempF)

    @Test fun compassRendersDegreesAndCardinal() {
        assertEquals("270#W ", Instrument.COMPASS.render(state(heading = 270f), ActivityUnit.METRIC, TempUnit.FAHRENHEIT))
        assertEquals("315#NW", Instrument.COMPASS.render(state(heading = 315f), ActivityUnit.METRIC, TempUnit.FAHRENHEIT))
        assertEquals("000#N ", Instrument.COMPASS.render(state(heading = 0f), ActivityUnit.METRIC, TempUnit.FAHRENHEIT))
    }

    @Test fun compassUnknownIsDashes() {
        assertEquals("---#", Instrument.COMPASS.render(state(), ActivityUnit.METRIC, TempUnit.FAHRENHEIT))
    }

    @Test fun altitudeFollowsUnit() {
        assertEquals("375m", Instrument.ALTITUDE.render(state(altM = 375.0), ActivityUnit.METRIC, TempUnit.FAHRENHEIT))
        // 375 m * 3.28084 = 1230.3 ft -> 1230
        assertEquals("1230f", Instrument.ALTITUDE.render(state(altM = 375.0), ActivityUnit.IMPERIAL, TempUnit.FAHRENHEIT))
        assertEquals("---", Instrument.ALTITUDE.render(state(), ActivityUnit.METRIC, TempUnit.FAHRENHEIT))
    }

    @Test fun pressureFollowsUnit() {
        assertEquals("1013", Instrument.PRESSURE.render(state(hpa = 1013.0), ActivityUnit.METRIC, TempUnit.FAHRENHEIT))
        // 1013 hPa * 0.0295299830714 = 29.91 inHg
        assertEquals("29.91", Instrument.PRESSURE.render(state(hpa = 1013.0), ActivityUnit.IMPERIAL, TempUnit.FAHRENHEIT))
        assertEquals("----", Instrument.PRESSURE.render(state(), ActivityUnit.METRIC, TempUnit.FAHRENHEIT))
    }

    @Test fun temperatureFollowsTempUnit() {
        // reuses DisplayFormatter.temperature: "  54#F"
        assertEquals("  54#F", Instrument.TEMPERATURE.render(state(tempF = 54), ActivityUnit.METRIC, TempUnit.FAHRENHEIT))
        // 54F -> 12.2C -> 12
        assertEquals("  12#C", Instrument.TEMPERATURE.render(state(tempF = 54), ActivityUnit.METRIC, TempUnit.CELSIUS))
        assertEquals("----", Instrument.TEMPERATURE.render(state(), ActivityUnit.METRIC, TempUnit.FAHRENHEIT))
    }

    @Test fun nextCyclesAllFour() {
        assertEquals(Instrument.ALTITUDE, Instrument.COMPASS.next())
        assertEquals(Instrument.COMPASS, Instrument.TEMPERATURE.next())
    }
}
