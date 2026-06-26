package com.blizzardcaron.freeolleefaces.activity

/**
 * Pure policy for re-posting the activity foreground notification. The session engine emits faster
 * than 1 Hz, and posting on every emission trips Android 17's NotificationManager rate limiter
 * (which sheds the excess and flickers the promoted Live Update chip). Cap the post rate and skip
 * identical text. Kept pure (no Android deps) so the throttle is unit-testable.
 */
object ActivityNotificationThrottle {

    const val MIN_INTERVAL_MS = 2_000L

    /**
     * True if the notification should be re-posted now: only when at least [MIN_INTERVAL_MS] has
     * passed since [lastPostMs] AND the text changed. A value throttled away during the cooldown is
     * superseded by the engine's next (~1s) emission, so the latest text still lands shortly after.
     */
    fun shouldPost(
        nowMs: Long,
        lastPostMs: Long,
        newText: String,
        lastText: String?,
    ): Boolean =
        nowMs - lastPostMs >= MIN_INTERVAL_MS && newText != lastText
}
