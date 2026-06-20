package com.blizzardcaron.freeolleefaces.timer

import kotlinx.datetime.LocalTime

/** Pure clock math for the alarm-mode quick timer — no UI/Android deps. */
object QuickAlarm {

    private const val SECONDS_PER_HOUR = 3600
    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_PER_DAY = 86_400

    /**
     * Seconds from [now] until the next occurrence of [targetHour]:[targetMinute] (target
     * seconds = 0). Rolls forward a full day when the target is at or before [now], so the
     * result is always in 1..86400. A target equal to [now] returns 86400 (next day).
     */
    fun countdownSeconds(now: LocalTime, targetHour: Int, targetMinute: Int): Int {
        val target = targetHour * SECONDS_PER_HOUR + targetMinute * SECONDS_PER_MINUTE
        val nowSec = now.hour * SECONDS_PER_HOUR + now.minute * SECONDS_PER_MINUTE + now.second
        val delta = ((target - nowSec) % SECONDS_PER_DAY + SECONDS_PER_DAY) % SECONDS_PER_DAY
        return if (delta == 0) SECONDS_PER_DAY else delta
    }
}
