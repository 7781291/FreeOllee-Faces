package com.blizzardcaron.freeolleefaces.notifications

/**
 * Pure logic for the notification badge shown in the watch's weekday slot. Framework-free
 * so it unit-tests without Android objects; the service maps live notifications onto
 * [ActiveNotification] before calling in.
 */
object NotificationCount {

    /** A framework-free view of one active (shade) notification. */
    data class ActiveNotification(
        val packageName: String,
        val isClearable: Boolean,
        val isOngoing: Boolean,
        val isGroupSummary: Boolean,
    )

    /**
     * Counts undismissed, non-persistent notifications: clearable, not ongoing, not a group
     * summary row, and not posted by us ([ownPackage]). Each surviving entry counts once, so
     * multiple notifications from one app all count.
     */
    fun countFrom(notifications: List<ActiveNotification>, ownPackage: String): Int =
        notifications.count {
            it.isClearable &&
                !it.isOngoing &&
                !it.isGroupSummary &&
                it.packageName != ownPackage
        }

    /**
     * Formats a count for the 2-cell slot: null at zero (caller restores the real weekday),
     * zero-padded for 1..9, plain for 10..99, capped "99" beyond (two cells can't show three
     * digits).
     */
    fun format(n: Int): String? = when {
        n <= 0 -> null
        n >= 99 -> "99"
        else -> "%02d".format(n)
    }
}
