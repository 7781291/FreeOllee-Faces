package com.blizzardcaron.freeolleefaces.format

import com.blizzardcaron.freeolleefaces.weather.WeatherFetchError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeatherErrorCopyTest {

    @Test
    fun `ServerError shows status code and a retry hint`() {
        val msg = WeatherErrorCopy.describe(WeatherFetchError.ServerError(502))
        assertTrue(msg.contains("502"), msg)
        assertTrue(msg.contains("unavailable"), msg)
    }

    @Test
    fun `Timeout shows a retry hint without a code`() {
        val msg = WeatherErrorCopy.describe(WeatherFetchError.Timeout)
        assertTrue(msg.contains("timed out"), msg)
    }

    @Test
    fun `Network reports a connection problem`() {
        val msg = WeatherErrorCopy.describe(WeatherFetchError.Network("reset"))
        assertTrue(msg.contains("connection", ignoreCase = true), msg)
    }

    @Test
    fun `ClientError shows the status code`() {
        val msg = WeatherErrorCopy.describe(WeatherFetchError.ClientError(400))
        assertTrue(msg.contains("400"), msg)
    }

    @Test
    fun `Malformed reports bad data`() {
        assertEquals(
            "Couldn't read weather (bad data)",
            WeatherErrorCopy.describe(WeatherFetchError.Malformed("no current")),
        )
    }

    @Test
    fun `unknown throwable falls back to generic copy`() {
        assertEquals(
            "Couldn't fetch weather",
            WeatherErrorCopy.describe(RuntimeException("boom")),
        )
    }
}
