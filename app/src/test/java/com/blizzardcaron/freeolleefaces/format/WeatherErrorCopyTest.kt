package com.blizzardcaron.freeolleefaces.format

import com.blizzardcaron.freeolleefaces.weather.WeatherFetchError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherErrorCopyTest {

    @Test
    fun `ServerError shows status code and a retry hint`() {
        val msg = WeatherErrorCopy.describe(WeatherFetchError.ServerError(502))
        assertTrue(msg, msg.contains("502"))
        assertTrue(msg, msg.contains("unavailable"))
    }

    @Test
    fun `Timeout shows a retry hint without a code`() {
        val msg = WeatherErrorCopy.describe(WeatherFetchError.Timeout)
        assertTrue(msg, msg.contains("timed out"))
    }

    @Test
    fun `Network reports a connection problem`() {
        val msg = WeatherErrorCopy.describe(WeatherFetchError.Network("reset"))
        assertTrue(msg, msg.contains("connection", ignoreCase = true))
    }

    @Test
    fun `ClientError shows the status code`() {
        val msg = WeatherErrorCopy.describe(WeatherFetchError.ClientError(400))
        assertTrue(msg, msg.contains("400"))
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
