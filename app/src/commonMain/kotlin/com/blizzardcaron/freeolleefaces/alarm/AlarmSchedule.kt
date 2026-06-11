package com.blizzardcaron.freeolleefaces.alarm

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus

/**
 * Pure next-fire computation over the 5 logical alarms. The watch stores a single alarm with no
 * day-of-week field, so the phone computes which occurrence is next and (in the re-arm engine)
 * keeps the watch's one slot pointed at it. No Android deps — mirrors
 * [com.blizzardcaron.freeolleefaces.auto.AutoUpdateSchedule].
 */
object AlarmSchedule {

    /** The soonest upcoming occurrence across all enabled alarms. */
    data class NextFire(val dateTime: LocalDateTime, val hour: Int, val minute: Int, val chimeIndex: Int)

    /**
     * The minimum next occurrence across [alarms], or null if nothing is due (none enabled, or
     * enabled but with empty day masks — the inert state). Today's HH:MM counts only if strictly
     * after [now]. Ties go to the earliest-listed alarm.
     */
    fun nextFire(alarms: List<Alarm>, now: LocalDateTime): NextFire? =
        alarms.filter { it.enabled }
            .mapNotNull { a -> nextOccurrence(a, now)?.let { NextFire(it, a.hour, a.minute, a.chimeIndex) } }
            .minByOrNull { it.dateTime }

    private fun nextOccurrence(alarm: Alarm, now: LocalDateTime): LocalDateTime? {
        // Scan today..+7 days; the +7 covers "only weekday bit is today's, but the time passed".
        for (offset in 0..7) {
            val date = now.date.plus(offset, DateTimeUnit.DAY)
            if (!alarm.repeatsOn(date.dayOfWeek)) continue
            val candidate = LocalDateTime(date, LocalTime(alarm.hour, alarm.minute))
            if (candidate > now) return candidate
        }
        return null
    }
}
