package com.blizzardcaron.freeolleefaces.format

import com.blizzardcaron.freeolleefaces.weather.WeatherFetchError

/** Maps a weather-fetch failure to user-facing copy. Never exposes the raw upstream body. */
object WeatherErrorCopy {
    fun describe(error: Throwable): String = when (error) {
        is WeatherFetchError.ServerError ->
            "Weather service unavailable (${error.statusCode}) — try again shortly"
        is WeatherFetchError.Timeout ->
            "Weather service timed out — try again shortly"
        is WeatherFetchError.Network ->
            "No connection to weather service — try again shortly"
        is WeatherFetchError.ClientError ->
            "Couldn't read weather (${error.statusCode})"
        is WeatherFetchError.Malformed ->
            "Couldn't read weather (bad data)"
        else ->
            "Couldn't fetch weather"
    }
}
