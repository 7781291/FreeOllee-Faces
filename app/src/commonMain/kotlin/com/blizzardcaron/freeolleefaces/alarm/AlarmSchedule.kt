package com.blizzardcaron.freeolleefaces.alarm

import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
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

    /**
     * The exact `0x25` frame for [next]: an armed real alarm (`enabled=true, playNow=false` —
     * verified to ring ~35 s and self-stop), or the disarm frame when nothing is due
     * (`enabled=false` — verified silent on-device, even over an already-armed alarm).
     */
    fun packetFor(next: NextFire?): ByteArray =
        if (next != null) {
            OlleeProtocol.buildAlarmPacket(
                hour = next.hour, minute = next.minute, chimeIndex = next.chimeIndex,
                playNow = false, enabled = true,
            )
        } else {
            OlleeProtocol.buildAlarmPacket(hour = 0, minute = 0, chimeIndex = 0, playNow = false, enabled = false)
        }

    /** UI summary, e.g. "Next: Tue 7:00 AM · Breeze" — or "No alarms". */
    fun formatNext(next: NextFire?): String {
        if (next == null) return "No alarms"
        val day = next.dateTime.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        val h12 = when { next.hour == 0 -> 12; next.hour > 12 -> next.hour - 12; else -> next.hour }
        val amPm = if (next.hour < 12) "AM" else "PM"
        val mm = next.minute.toString().padStart(2, '0')
        return "Next: $day $h12:$mm $amPm · ${chimeName(next.chimeIndex)}"
    }

    /** Watch chime tone name; only the first few of the 14 are named in the protocol doc. */
    fun chimeName(index: Int): String = CHIME_NAMES.getOrNull(index) ?: "Chime ${index + 1}"

    private val CHIME_NAMES = listOf("Classic", "Breeze", "Westminster")
}
