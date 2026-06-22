package com.blizzardcaron.freeolleefaces.location

data class Coords(
    val lat: Double,
    val lng: Double,
    /**
     * Horizontal accuracy radius in metres; smaller is better (Android Location.getAccuracy
     * convention). null when unknown.
     */
    val accuracyM: Float?,
    val provider: String?,
    val altM: Double? = null,
    /** Course over ground in degrees [0,360); only meaningful while moving. null when unknown. */
    val bearingDeg: Float? = null,
    /** Ground speed in m/s; null when unknown. Used to gate a valid compass heading. */
    val speedMps: Float? = null,
)

/** One-shot device location for the weather/sun faces; platform implementations own the plumbing. */
interface LocationProvider {

    /**
     * Best-effort current coordinates: a fresh fix bounded by [timeoutMs], falling back to the
     * most recent last-known fix. [Result.failure] when permission is missing or no fix exists.
     */
    suspend fun fetch(timeoutMs: Long = 10_000): Result<Coords>
}
