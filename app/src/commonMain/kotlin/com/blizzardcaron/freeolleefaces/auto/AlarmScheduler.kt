package com.blizzardcaron.freeolleefaces.auto

/**
 * Lets shared code request an alarm re-arm pass — recompute the next fire, push the armed/disarm
 * frame to the watch, and (re)schedule the post-fire trigger — without knowing about AlarmManager.
 */
interface AlarmScheduler {
    fun rearm()
}
