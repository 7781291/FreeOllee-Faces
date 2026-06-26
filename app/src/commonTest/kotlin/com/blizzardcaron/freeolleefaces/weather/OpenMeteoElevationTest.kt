package com.blizzardcaron.freeolleefaces.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenMeteoElevationTest {
    @Test fun parsesElevation() {
        val json = """{"latitude":40.0,"longitude":-105.0,"elevation":1620.0,"current":{"temperature_2m":70.0}}"""
        assertEquals(1620.0, OpenMeteoClient.parseElevationM(json), 1e-6)
    }
    @Test fun missingFieldThrowsMalformed() {
        assertFailsWith<WeatherFetchError.Malformed> {
            OpenMeteoClient.parseElevationM("""{"current":{"temperature_2m":70.0}}""")
        }
    }
    @Test fun urlRequestsForecastWithCurrent() {
        assertEquals(
            "https://api.open-meteo.com/v1/forecast?latitude=40.0&longitude=-105.0&current=temperature_2m",
            OpenMeteoClient.buildElevationUrl(40.0, -105.0),
        )
    }
}
