package com.blizzardcaron.freeolleefaces.timer

import kotlinx.datetime.LocalTime

/** Pure clock math for the alarm-mode quick timer — no UI/Android deps. */
object QuickAlarm {

    /**
     * Seconds from [now] until the next occurrence of [targetHour]:[targetMinute] (target
     * seconds = 0). Rolls forward a full day when the target is at or before [now], so the
     * result is always in 1..86400. A target equal to [now] returns 86400 (next day).
     */
    fun countdownSeconds(now: LocalTime, targetHour: Int, targetMinute: Int): Int {
        val target = targetHour * 3600 + targetMinute * 60
        val nowSec = now.hour * 3600 + now.minute * 60 + now.second
        val delta = ((target - nowSec) % 86_400 + 86_400) % 86_400
        return if (delta == 0) 86_400 else delta
    }
}
