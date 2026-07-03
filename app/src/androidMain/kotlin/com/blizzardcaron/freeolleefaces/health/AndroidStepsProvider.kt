package com.blizzardcaron.freeolleefaces.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads today's total step count from Android Health Connect, where the user's step-tracking
 * app (a fitness ring, phone pedometer, etc.) publishes `StepsRecord` entries. No watch or
 * BLE concerns live here — this is purely the data source for the Steps face.
 *
 * Nothing thrown escapes: availability and permission gaps surface as
 * [StepsProvider.Availability] / [hasReadPermission], and aggregate failures come back as
 * [Result.failure].
 */
class AndroidStepsProvider(context: Context) : StepsProvider {

    private val appContext = context.applicationContext

    /** Whether Health Connect is usable on this device right now. */
    override fun availability(): StepsProvider.Availability = when (HealthConnectClient.getSdkStatus(appContext)) {
        HealthConnectClient.SDK_AVAILABLE -> StepsProvider.Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> StepsProvider.Availability.UPDATE_REQUIRED
        else -> StepsProvider.Availability.UNAVAILABLE
    }

    /** True once the user has granted read access to steps. */
    override suspend fun hasReadPermission(): Boolean {
        if (availability() != StepsProvider.Availability.AVAILABLE) return false
        val granted = client().permissionController.getGrantedPermissions()
        return granted.contains(READ_STEPS)
    }

    /**
     * Sum of steps from local midnight to now (system zone), as written by any source. Returns 0
     * when there are no records yet today; [Result.failure] when Health
     * Connect is unavailable, read access is missing, or the aggregate call fails (e.g. a
     * background read without `READ_HEALTH_DATA_IN_BACKGROUND`).
     */
    override suspend fun todaySteps(): Result<Long> {
        val failure: Throwable? = when {
            availability() != StepsProvider.Availability.AVAILABLE ->
                IllegalStateException("Health Connect unavailable")
            !hasReadPermission() ->
                SecurityException("Steps read permission not granted")
            else -> null
        }
        if (failure != null) return Result.failure(failure)
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val end = java.time.Instant.now()
        return runCatching {
            val result: AggregationResult = client().aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                )
            )
            result[StepsRecord.COUNT_TOTAL] ?: 0L
        }

    /**
     * Sum of steps from [sinceEpochMs] to now, as written by any source. Used for an in-progress
     * Activity session's step count rather than the whole day's total. Same failure surface as
     * [todaySteps].
     */
    override suspend fun stepsSince(sinceEpochMs: Long): Result<Long> {
        val failure: Throwable? = when {
            availability() != StepsProvider.Availability.AVAILABLE ->
                IllegalStateException("Health Connect unavailable")
            !hasReadPermission() ->
                SecurityException("Steps read permission not granted")
            else -> null
        }
        if (failure != null) return Result.failure(failure)
        val start = java.time.Instant.ofEpochMilli(sinceEpochMs)
        val end = java.time.Instant.now()
        return runCatching {
            val result: AggregationResult = client().aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                )
            )
            result[StepsRecord.COUNT_TOTAL] ?: 0L
        }
    }
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(appContext)

    companion object {
        private val READ_STEPS = HealthPermission.getReadPermission(StepsRecord::class)

        /**
         * Permission set requested through the Health Connect permission contract. The
         * background-read string lets [com.blizzardcaron.freeolleefaces.auto.AutoUpdateWorker]
         * read while the app is not in the foreground; it is a plain string to avoid coupling
         * to a specific connect-client constant.
         */
        val PERMISSIONS: Set<String> = setOf(
            READ_STEPS,
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
        )
    }
}
