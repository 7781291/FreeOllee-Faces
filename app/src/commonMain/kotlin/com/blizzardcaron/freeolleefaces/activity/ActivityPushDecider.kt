package com.blizzardcaron.freeolleefaces.activity

/** Pure policy for when to write the name-tag: on change, on a heartbeat, or when forced (MODE). */
object ActivityPushDecider {

    const val HEARTBEAT_MS = 3_000L

    fun shouldPush(
        lastPushedText: String?,
        newText: String,
        msSinceLastPush: Long,
        forced: Boolean,
    ): Boolean =
        forced ||
            lastPushedText == null ||
            newText != lastPushedText ||
            msSinceLastPush >= HEARTBEAT_MS
}
