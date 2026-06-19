package com.blizzardcaron.freeolleefaces.alarm

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.isoDayNumber

/**
 * One phone-side alarm. The watch stores only HH:MM + chime + enable (no day field), so [daysMask]
 * lives here: bit (isoDayNumber-1) set means "repeats that weekday" — bit0=Mon … bit6=Sun. [label]
 * is phone-side only; [chimeIndex] is the watch chime (an index into [AlarmSchedule.CHIME_NAMES]).
 * A new alarm defaults to all 7 days.
 */
data class Alarm(
    val id: String,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val daysMask: Int = ALL_DAYS,
    val chimeIndex: Int = 0,
    val label: String = "",
) {
    init {
        require(hour in 0..23) { "hour must be 0..23 (got $hour)" }
        require(minute in 0..59) { "minute must be 0..59 (got $minute)" }
        require(daysMask in 0..0x7F) { "daysMask is 7 bits (got $daysMask)" }
        require(chimeIndex in AlarmSchedule.CHIME_NAMES.indices) {
            "chimeIndex must be in ${AlarmSchedule.CHIME_NAMES.indices} (got $chimeIndex)"
        }
    }

    /** True if this alarm repeats on [day] (its bit is set in [daysMask]). */
    fun repeatsOn(day: DayOfWeek): Boolean = (daysMask shr (day.isoDayNumber - 1)) and 1 == 1

    companion object {
        const val ALL_DAYS = 0x7F

        /** The single-bit mask for [day] (Mon=bit0 … Sun=bit6). */
        fun bit(day: DayOfWeek): Int = 1 shl (day.isoDayNumber - 1)
    }
}
