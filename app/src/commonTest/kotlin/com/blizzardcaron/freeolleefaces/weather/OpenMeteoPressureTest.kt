package com.blizzardcaron.freeolleefaces.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenMeteoPressureTest {
    @Test fun parsesSurfacePressure() {
        val json = """{"current":{"surface_pressure":1007.8}}"""
        assertEquals(1007.8, OpenMeteoClient.parseSurfacePressureHpa(json), 1e-6)
    }
    @Test fun missingFieldThrowsMalformed() {
        assertFailsWith<WeatherFetchError.Malformed> {
            OpenMeteoClient.parseSurfacePressureHpa("""{"current":{}}""")
        }
    }
    @Test fun urlRequestsSurfacePressure() {
        assertEquals(
            "https://api.open-meteo.com/v1/forecast?latitude=40.0&longitude=-105.0&current=surface_pressure",
            OpenMeteoClient.buildPressureUrl(40.0, -105.0),
        )
    }
}
