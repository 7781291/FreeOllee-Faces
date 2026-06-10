package com.blizzardcaron.freeolleefaces.health

/**
 * Data source for the Steps face: today's total step count from the platform's health store.
 * Nothing thrown escapes — availability and permission gaps surface as [Availability] /
 * [hasReadPermission], and read failures come back as [Result.failure].
 */
interface StepsProvider {

    enum class Availability { UNAVAILABLE, UPDATE_REQUIRED, AVAILABLE }

    /** Whether the platform health store is usable on this device right now. */
    fun availability(): Availability

    /** True once the user has granted read access to steps. */
    suspend fun hasReadPermission(): Boolean

    /**
     * Sum of steps from local midnight to now, as written by any source. Returns 0 when there
     * are no records yet today; [Result.failure] when the health store is unavailable, read
     * access is missing, or the read fails.
     */
    suspend fun todaySteps(): Result<Long>
}
