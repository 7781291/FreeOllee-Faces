package com.blizzardcaron.freeolleefaces.auto

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Sleep window bounds as minute-of-day; `endMin` may be < `startMin` (wraps midnight). */
data class SleepWindow(val startMin: Int, val endMin: Int)

/** Pure scheduling math — no Android dependencies, fully unit-testable. */
object AutoUpdateSchedule {

    // Wall-clock arithmetic is done by round-tripping through a fixed UTC instant: the inputs are
    // already localized LocalDateTimes, so this just adds/subtracts wall-clock minutes/seconds with
    // correct day rollover (no DST surprises, matching the previous ZonedDateTime field math).
    private val ARITHMETIC_ZONE = TimeZone.UTC

    private const val MINUTES_PER_HOUR = 60
    private const val DEFAULT_SUN_WAKE_BUFFER_SECONDS = 60L
    private const val BACKSTOP_DELAY_1_MINUTES = 2L
    private const val BACKSTOP_DELAY_2_MINUTES = 5L
    private const val BACKSTOP_DELAY_3_MINUTES = 15L
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val HOURS_PER_DAY = 24L
    private const val MINUTES_PER_HOUR_L = 60L

    /** Start inclusive, end exclusive. `startMin == endMin` means never in window. */
    fun isInSleepWindow(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean {
        if (startMin == endMin) return false
        return if (startMin < endMin) {
            minuteOfDay in startMin until endMin
        } else {
            minuteOfDay >= startMin || minuteOfDay < endMin
        }
    }

    /**
     * Next temperature fire = [now] + [intervalMinutes]; if that lands inside [sleep],
     * snap forward to the next occurrence of `sleep.endMin`.
     */
    fun nextTemperatureFire(
        now: LocalDateTime,
        intervalMinutes: Int,
        sleep: SleepWindow?,
    ): LocalDateTime {
        val base = now.plusMinutes(intervalMinutes.toLong())
        val baseMinOfDay = base.hour * MINUTES_PER_HOUR + base.minute
        return if (sleep != null && isInSleepWindow(baseMinOfDay, sleep.startMin, sleep.endMin)) {
            snapToEnd(base, sleep.endMin)
        } else {
            base
        }
    }

    /** Wake right after the event goes stale. */
    fun nextSunWake(eventTime: LocalDateTime, bufferSeconds: Long = DEFAULT_SUN_WAKE_BUFFER_SECONDS): LocalDateTime =
        eventTime.plusSeconds(bufferSeconds)

    /** Backstop retry budget: up to this many re-tries after the first failed send. */
    const val MAX_SEND_RETRIES = 3

    /** Whether a send that failed at the (0-based) [attempt] still has backstop budget left. */
    fun hasBackstopBudget(attempt: Int): Boolean = attempt < MAX_SEND_RETRIES

    /**
     * Backoff before the backstop retry that follows the (0-based) failed [attempt]:
     * 2 min → 5 min → 15 min, then held at 15 min. Replaces SUN's old flat 15/15/15.
     */
    fun backstopDelayMs(attempt: Int): Long {
        val minutes = when (attempt) {
            0 -> BACKSTOP_DELAY_1_MINUTES
            1 -> BACKSTOP_DELAY_2_MINUTES
            else -> BACKSTOP_DELAY_3_MINUTES
        }
        return minutes * MILLIS_PER_MINUTE
    }

    private fun snapToEnd(from: LocalDateTime, endMin: Int): LocalDateTime {
        var candidate = LocalDateTime(
            from.date,
            LocalTime(endMin / MINUTES_PER_HOUR, endMin % MINUTES_PER_HOUR, 0, 0),
        )
        if (candidate <= from) candidate = candidate.plusMinutes(HOURS_PER_DAY * MINUTES_PER_HOUR_L)
        return candidate
    }

    private fun LocalDateTime.plusMinutes(minutes: Long): LocalDateTime =
        toInstant(ARITHMETIC_ZONE)
            .plus(minutes, DateTimeUnit.MINUTE)
            .toLocalDateTime(ARITHMETIC_ZONE)

    private fun LocalDateTime.plusSeconds(seconds: Long): LocalDateTime =
        toInstant(ARITHMETIC_ZONE)
            .plus(seconds, DateTimeUnit.SECOND)
            .toLocalDateTime(ARITHMETIC_ZONE)
}
