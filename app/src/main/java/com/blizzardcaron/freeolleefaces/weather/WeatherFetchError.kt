package com.blizzardcaron.freeolleefaces.weather

/** Typed failures from a weather fetch, classified as transient (retry-worthy) or permanent. */
sealed class WeatherFetchError(message: String) : Exception(message) {

    abstract val isTransient: Boolean

    /** HTTP status code when the failure carries one, else null. Used for logging. */
    open val statusCode: Int? = null

    /** HTTP 5xx — Open-Meteo is up but degraded. */
    data class ServerError(override val statusCode: Int) :
        WeatherFetchError("Open-Meteo returned HTTP $statusCode") {
        override val isTransient = true
    }

    /** Connect or read timed out. */
    object Timeout : WeatherFetchError("Open-Meteo request timed out") {
        override val isTransient = true
    }

    /** Other network-level failure (no route, connection reset, etc.). */
    data class Network(val detail: String) :
        WeatherFetchError("Network error contacting Open-Meteo: $detail") {
        override val isTransient = true
    }

    /** HTTP 4xx (or any other non-2xx) — our request is wrong; retrying won't help. */
    data class ClientError(override val statusCode: Int) :
        WeatherFetchError("Open-Meteo rejected request: HTTP $statusCode") {
        override val isTransient = false
    }

    /** Response body was not the JSON we expected. */
    data class Malformed(val detail: String) :
        WeatherFetchError("Malformed Open-Meteo response: $detail") {
        override val isTransient = false
    }

    companion object {
        /** Maps an HTTP status to an error, or null for 2xx. */
        fun fromHttpStatus(code: Int): WeatherFetchError? = when {
            code in 200..299 -> null
            code in 500..599 -> ServerError(code)
            else -> ClientError(code)
        }
    }
}
