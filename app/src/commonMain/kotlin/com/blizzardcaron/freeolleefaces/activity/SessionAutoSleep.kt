package com.blizzardcaron.freeolleefaces.activity

/** The auto-sleep operations the session engine needs; implemented by [ActivityAutoSleepManager]. */
interface SessionAutoSleep {
    suspend fun disableForActivity(address: String): Boolean
    suspend fun restoreAfterActivity(address: String): Boolean
}
