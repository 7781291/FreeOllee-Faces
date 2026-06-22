package com.blizzardcaron.freeolleefaces.weather

import com.blizzardcaron.freeolleefaces.format.TempUnit
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object OpenMeteoClient {

    private const val BASE = "https://api.open-meteo.com/v1/forecast"
    private const val CONNECT_TIMEOUT_MS = 8000L
    private const val READ_TIMEOUT_MS = 8000L

    @Serializable
    private data class OpenMeteoResponse(val current: Current? = null) {
        @Serializable
        data class Current(@SerialName("temperature_2m") val temp2m: Double? = null)
    }

    @Serializable
    private data class PressureResponse(val current: PCurrent? = null) {
        @Serializable
        data class PCurrent(@SerialName("surface_pressure") val surfacePressure: Double? = null)
    }

    private val parseJson = Json { ignoreUnknownKeys = true }

    /** Pure URL builder so the unit param is unit-testable without HTTP. */
    fun buildUrl(lat: Double, lng: Double, unit: TempUnit): String =
        "$BASE?latitude=$lat&longitude=$lng" +
            "&current=temperature_2m&temperature_unit=${unit.openMeteoParam}"

    /** Fetches `current.temperature_2m` in the requested [unit], retrying per [policy]. */
    suspend fun currentTemp(
        lat: Double,
        lng: Double,
        unit: TempUnit,
        policy: RetryPolicy,
    ): Result<Double> =
        withRetry(
            policy = policy,
            isTransient = { it is WeatherFetchError && it.isTransient },
        ) {
            fetchOnce(lat, lng, unit)
        }

    /**
     * A single HTTP attempt. Throws a [WeatherFetchError] on any failure.
     *
     * Each catch arm maps its specific exception to the matching [WeatherFetchError], but the
     * actual `throw` is consolidated to one site below so ThrowsCount stays low without losing
     * per-exception specificity (and without resorting to a generic `catch (e: Exception)`).
     */
    private suspend fun fetchOnce(lat: Double, lng: Double, unit: TempUnit): Double {
        val client = HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = READ_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = READ_TIMEOUT_MS
            }
        }
        val mappedError: WeatherFetchError
        try {
            val response = client.get(buildUrl(lat, lng, unit))
            WeatherFetchError.fromHttpStatus(response.status.value)?.let { throw it }
            val body = response.bodyAsText()
            return parseCurrentTemperatureF(body)
        } catch (e: SocketTimeoutException) {
            mappedError = WeatherFetchError.Timeout(e)
        } catch (e: ConnectTimeoutException) {
            mappedError = WeatherFetchError.Timeout(e)
        } catch (e: TimeoutCancellationException) {
            mappedError = WeatherFetchError.Timeout(e)
        } catch (e: IOException) {
            mappedError = WeatherFetchError.Network(e.message ?: "I/O error", e)
        } finally {
            client.close()
        }
        throw mappedError
    }

    /** Extracts `current.temperature_2m` from the response JSON. Unit is whatever the URL requested. */
    fun parseCurrentTemperatureF(json: String): Double {
        val response = try {
            parseJson.decodeFromString<OpenMeteoResponse>(json)
        } catch (e: SerializationException) {
            throw WeatherFetchError.Malformed("response is not valid JSON: ${e.message}", e)
        }
        return response.current?.temp2m
            ?: throw WeatherFetchError.Malformed("response missing 'current' or 'temperature_2m'")
    }

    /** Pure URL builder for the surface-pressure fallback (hPa), unit-testable without HTTP. */
    fun buildPressureUrl(lat: Double, lng: Double): String =
        "$BASE?latitude=$lat&longitude=$lng&current=surface_pressure"

    /** Parses the JSON response to extract surface pressure in hPa. */
    fun parseSurfacePressureHpa(json: String): Double {
        val response = try {
            parseJson.decodeFromString<PressureResponse>(json)
        } catch (e: SerializationException) {
            throw WeatherFetchError.Malformed("response is not valid JSON: ${e.message}", e)
        }
        return response.current?.surfacePressure
            ?: throw WeatherFetchError.Malformed("response missing 'current' or 'surface_pressure'")
    }

    /** Fetches surface pressure once, handling network errors and timeouts. */
    private suspend fun fetchPressureOnce(lat: Double, lng: Double): Double {
        val client = HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = READ_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = READ_TIMEOUT_MS
            }
        }
        val mappedError: WeatherFetchError
        try {
            val response = client.get(buildPressureUrl(lat, lng))
            WeatherFetchError.fromHttpStatus(response.status.value)?.let { throw it }
            return parseSurfacePressureHpa(response.bodyAsText())
        } catch (e: SocketTimeoutException) {
            mappedError = WeatherFetchError.Timeout(e)
        } catch (e: ConnectTimeoutException) {
            mappedError = WeatherFetchError.Timeout(e)
        } catch (e: TimeoutCancellationException) {
            mappedError = WeatherFetchError.Timeout(e)
        } catch (e: IOException) {
            mappedError = WeatherFetchError.Network(e.message ?: "I/O error", e)
        } finally {
            client.close()
        }
        throw mappedError
    }

    /** Fetches surface pressure, retrying per [policy]. */
    suspend fun currentPressureHpa(lat: Double, lng: Double, policy: RetryPolicy): Result<Double> =
        withRetry(
            policy = policy,
            isTransient = { it is WeatherFetchError && it.isTransient },
        ) {
            fetchPressureOnce(lat, lng)
        }
}
