package com.blizzardcaron.freeolleefaces.notifications

/** Whether the user has granted this app notification access (the listener binding). */
interface NotificationAccessChecker {
    fun isGranted(): Boolean
}
