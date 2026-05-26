package com.blizzardcaron.freeolleefaces.weather

import com.blizzardcaron.freeolleefaces.format.TempUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

object OpenMeteoClient {

    private const val BASE = "https://api.open-meteo.com/v1/forecast"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000

    /** Pure URL builder so the unit param is unit-testable without HTTP. */
    fun buildUrl(lat: Double, lng: Double, unit: TempUnit): URL =
        URL(
            "$BASE?latitude=$lat&longitude=$lng" +
                "&current=temperature_2m&temperature_unit=${unit.openMeteoParam}"
        )

    /** Fetches `current.temperature_2m` in the requested [unit], retrying per [policy]. */
    suspend fun currentTemp(
        lat: Double,
        lng: Double,
        unit: TempUnit,
        policy: RetryPolicy,
    ): Result<Double> =
        withContext(Dispatchers.IO) {
            withRetry(
                policy = policy,
                isTransient = { it is WeatherFetchError && it.isTransient },
            ) {
                fetchOnce(lat, lng, unit)
            }
        }

    /** A single HTTP attempt. Throws a [WeatherFetchError] on any failure. */
    private fun fetchOnce(lat: Double, lng: Double, unit: TempUnit): Double {
        val conn = buildUrl(lat, lng, unit).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            val code = try {
                conn.responseCode
            } catch (e: SocketTimeoutException) {
                throw WeatherFetchError.Timeout
            } catch (e: IOException) {
                throw WeatherFetchError.Network(e.message ?: e.javaClass.simpleName)
            }
            WeatherFetchError.fromHttpStatus(code)?.let { throw it }
            val body = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: SocketTimeoutException) {
                throw WeatherFetchError.Timeout
            } catch (e: IOException) {
                throw WeatherFetchError.Network(e.message ?: e.javaClass.simpleName)
            }
            return parseCurrentTemperatureF(body)
        } finally {
            conn.disconnect()
        }
    }

    /** Extracts `current.temperature_2m` from the response JSON. Unit is whatever the URL requested. */
    fun parseCurrentTemperatureF(json: String): Double {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw WeatherFetchError.Malformed("response is not valid JSON: ${e.message}")
        }
        val current = root.optJSONObject("current")
            ?: throw WeatherFetchError.Malformed("response missing 'current' block")
        if (!current.has("temperature_2m")) {
            throw WeatherFetchError.Malformed("response 'current' missing 'temperature_2m'")
        }
        return current.getDouble("temperature_2m")
    }
}
