package com.blizzardcaron.freeolleefaces.auto

/** Lets shared code request a background reschedule without knowing about WorkManager. */
interface Scheduler {
    fun reschedule()
}
