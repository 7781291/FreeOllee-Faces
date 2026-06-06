package com.blizzardcaron.freeolleefaces.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherFetchErrorTest {

    @Test
    fun `fromHttpStatus returns null for 2xx`() {
        assertNull(WeatherFetchError.fromHttpStatus(200))
        assertNull(WeatherFetchError.fromHttpStatus(204))
    }

    @Test
    fun `fromHttpStatus maps 5xx to a transient ServerError`() {
        val e = WeatherFetchError.fromHttpStatus(502)
        assertTrue(e is WeatherFetchError.ServerError)
        assertEquals(502, e!!.statusCode)
        assertTrue(e.isTransient)
    }

    @Test
    fun `fromHttpStatus maps 4xx to a permanent ClientError`() {
        val e = WeatherFetchError.fromHttpStatus(400)
        assertTrue(e is WeatherFetchError.ClientError)
        assertEquals(400, e!!.statusCode)
        assertFalse(e.isTransient)
    }

    @Test
    fun `Timeout and Network are transient with no status code`() {
        assertTrue(WeatherFetchError.Timeout.isTransient)
        assertNull(WeatherFetchError.Timeout.statusCode)
        assertTrue(WeatherFetchError.Network("conn reset").isTransient)
        assertNull(WeatherFetchError.Network("conn reset").statusCode)
    }

    @Test
    fun `Malformed is permanent`() {
        assertFalse(WeatherFetchError.Malformed("no current block").isTransient)
    }
}
