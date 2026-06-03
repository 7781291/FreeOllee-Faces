package com.blizzardcaron.freeolleefaces.auto

import java.time.ZonedDateTime

/** Sleep window bounds as minute-of-day; `endMin` may be < `startMin` (wraps midnight). */
data class SleepWindow(val startMin: Int, val endMin: Int)

/** Pure scheduling math — no Android dependencies, fully unit-testable. */
object AutoUpdateSchedule {

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
        now: ZonedDateTime,
        intervalMinutes: Int,
        sleep: SleepWindow?,
    ): ZonedDateTime {
        val base = now.plusMinutes(intervalMinutes.toLong())
        if (sleep == null) return base
        val baseMinOfDay = base.hour * 60 + base.minute
        if (!isInSleepWindow(baseMinOfDay, sleep.startMin, sleep.endMin)) return base
        return snapToEnd(base, sleep.endMin)
    }

    /** Wake right after the event goes stale. */
    fun nextSunWake(eventTime: ZonedDateTime, bufferSeconds: Long = 60): ZonedDateTime =
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
            0 -> 2L
            1 -> 5L
            else -> 15L
        }
        return minutes * 60_000L
    }

    private fun snapToEnd(from: ZonedDateTime, endMin: Int): ZonedDateTime {
        var candidate = from
            .withHour(endMin / 60)
            .withMinute(endMin % 60)
            .withSecond(0)
            .withNano(0)
        if (!candidate.isAfter(from)) candidate = candidate.plusDays(1)
        return candidate
    }
}
