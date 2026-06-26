package com.blizzardcaron.freeolleefaces.activity

/** Pure policy for when to write the name-tag: on change, on a heartbeat, or when forced (MODE). */
object ActivityPushDecider {

    const val HEARTBEAT_MS = 3_000L

    /** Hard floor: never write the name-tag more than once per second, whatever the reason. */
    const val MIN_PUSH_INTERVAL_MS = 1_000L

    fun shouldPush(
        lastPushedText: String?,
        newText: String,
        msSinceLastPush: Long,
        forced: Boolean,
    ): Boolean {
        if (msSinceLastPush < MIN_PUSH_INTERVAL_MS) return false
        return forced ||
            lastPushedText == null ||
            newText != lastPushedText ||
            msSinceLastPush >= HEARTBEAT_MS
    }
}
