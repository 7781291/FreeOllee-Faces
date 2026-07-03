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

    /** Inclusive day-scan window: today plus a full week, covering same-weekday-but-time-passed. */
    private const val SCAN_DAYS_AHEAD = 7

    /** Hours in a 12-hour clock half (noon/midnight boundary for AM/PM conversion). */
    private const val HOURS_PER_12H_CLOCK = 12

    /** Day-of-week name abbreviation length, e.g. "Tuesday" -> "Tue". */
    private const val DAY_ABBREVIATION_LENGTH = 3

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
        for (offset in 0..SCAN_DAYS_AHEAD) {
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
                hour = next.hour,
                minute = next.minute,
                chimeIndex = next.chimeIndex,
                playNow = false,
                enabled = true,
            )
        } else {
            OlleeProtocol.buildAlarmPacket(hour = 0, minute = 0, chimeIndex = 0, playNow = false, enabled = false)
        }

    /** UI summary, e.g. "Next: Tue 7:00 AM · Breeze" — or "No alarms". */
    fun formatNext(next: NextFire?): String {
        if (next == null) return "No alarms"
        val day = next.dateTime.dayOfWeek.name.take(DAY_ABBREVIATION_LENGTH).lowercase()
            .replaceFirstChar { it.uppercase() }
        val h12 = when { next.hour == 0 -> HOURS_PER_12H_CLOCK
            next.hour > HOURS_PER_12H_CLOCK -> next.hour - HOURS_PER_12H_CLOCK
            else -> next.hour }
        val amPm = if (next.hour < HOURS_PER_12H_CLOCK) "AM" else "PM"
        val mm = next.minute.toString().padStart(2, '0')
        return "Next: $day $h12:$mm $amPm · ${chimeName(next.chimeIndex)}"
    }

    /**
     * Sentinel chime index with no matching watch tone. The firmware has no melody to play at
     * this index, so the notification fires silently — but the watch's screen flash still fires
     * with it, since the flash is tied to the chime-fire event itself, not to which tone plays.
     * Lets the notification chime act as a "flash only" mode without needing a real (and often
     * too-long) melody. Not offered for alarms — only wired into the notification-chime picker.
     */
    const val SILENT_CHIME_INDEX = 15

    /** Watch chime tone name for indices 0x00..0x0E, or [SILENT_CHIME_INDEX]. */
    fun chimeName(index: Int): String = when (index) {
        SILENT_CHIME_INDEX -> "No sound (flash only)"
        else -> CHIME_NAMES.getOrNull(index) ?: "Chime ${index + 1}"
    }

    /**
     * The watch's 15 tones in index order, read from the official app's chime dropdown
     * (2026-06-11). Indices 0-2 are hardware-verified against the protocol doc; the dropdown
     * order matches them, so the rest follow it.
     */
    val CHIME_NAMES = listOf(
        "Classic", "Breeze", "Westminster", "Retro", "Wire", "Plumber", "Indy", "Galactic",
        "Dinosaur", "Superman", "Tequila", "Beethoven", "Blocks", "Ghosts", "Sand",
    )
}
