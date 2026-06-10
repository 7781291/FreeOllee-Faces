package com.blizzardcaron.freeolleefaces.location

data class Coords(val lat: Double, val lng: Double, val accuracyM: Float?, val provider: String?)

/** One-shot device location for the weather/sun faces; platform implementations own the plumbing. */
interface LocationProvider {

    /**
     * Best-effort current coordinates: a fresh fix bounded by [timeoutMs], falling back to the
     * most recent last-known fix. [Result.failure] when permission is missing or no fix exists.
     */
    suspend fun fetch(timeoutMs: Long = 10_000): Result<Coords>
}
