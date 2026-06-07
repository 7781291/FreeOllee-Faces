package com.blizzardcaron.freeolleefaces.weather

import com.blizzardcaron.freeolleefaces.format.TempUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenMeteoClientUrlTest {

    @Test
    fun `buildUrl puts lat and lng into the query string`() {
        val url = OpenMeteoClient.buildUrl(44.31, -72.04, TempUnit.FAHRENHEIT)
        assertTrue(url.contains("latitude=44.31"), "url should contain latitude param: $url")
        assertTrue(url.contains("longitude=-72.04"), "url should contain longitude param: $url")
    }

    @Test
    fun `buildUrl emits temperature_unit=fahrenheit for FAHRENHEIT`() {
        val url = OpenMeteoClient.buildUrl(0.0, 0.0, TempUnit.FAHRENHEIT)
        assertTrue(url.contains("temperature_unit=fahrenheit"), "url should request fahrenheit: $url")
    }

    @Test
    fun `buildUrl emits temperature_unit=celsius for CELSIUS`() {
        val url = OpenMeteoClient.buildUrl(0.0, 0.0, TempUnit.CELSIUS)
        assertTrue(url.contains("temperature_unit=celsius"), "url should request celsius: $url")
    }

    @Test
    fun `buildUrl always requests current temperature_2m`() {
        val url = OpenMeteoClient.buildUrl(0.0, 0.0, TempUnit.FAHRENHEIT)
        assertTrue(url.contains("current=temperature_2m"), "url should request current temperature_2m: $url")
    }

    @Test
    fun `buildUrl uses HTTPS and the Open-Meteo host`() {
        val url = OpenMeteoClient.buildUrl(0.0, 0.0, TempUnit.FAHRENHEIT)
        assertTrue(url.startsWith("https://"), "url should use HTTPS: $url")
        assertTrue(url.contains("api.open-meteo.com"), "url should target the Open-Meteo host: $url")
    }
}
