package com.blizzardcaron.freeolleefaces.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.blizzardcaron.freeolleefaces.auto.ActiveFace
import com.blizzardcaron.freeolleefaces.ble.OlleeBleClient
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Counts undismissed, non-persistent notifications and pushes the badge to the watch's
 * weekday slot. Requires the user to grant "Notification access" in system settings. Live
 * pushes are debounced (~2 s) and only happen while [ActiveFace.NOTIFICATIONS] is active;
 * [com.blizzardcaron.freeolleefaces.auto.AutoUpdateWorker] is the periodic backstop.
 */
class NotificationCountService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pushJob: Job? = null
    private val prefs by lazy { Prefs(applicationContext) }

    override fun onListenerConnected() {
        // Seed and sync on (re)bind, even if the count is unchanged.
        prefs.notificationCount = computeCount()
        schedulePush()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = recomputeAndPush()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recomputeAndPush()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun recomputeAndPush() {
        val count = computeCount()
        val changed = count != prefs.notificationCount
        prefs.notificationCount = count
        if (changed) schedulePush()
    }

    private fun computeCount(): Int {
        val active = activeNotifications ?: return prefs.notificationCount
        val mapped = active.map { sbn ->
            val flags = sbn.notification.flags
            NotificationCount.ActiveNotification(
                packageName = sbn.packageName,
                isClearable = sbn.isClearable,
                isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0,
                isGroupSummary = (flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            )
        }
        return NotificationCount.countFrom(mapped, ownPackage = packageName)
    }

    private fun schedulePush() {
        // Debounce: a flurry of posts/removals collapses into one push.
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(DEBOUNCE_MS)
            if (prefs.activeFace != ActiveFace.NOTIFICATIONS) return@launch
            val addr = prefs.watchAddress ?: return@launch
            OlleeBleClient(applicationContext)
                .sendPacket(addr, NotificationCount.packetFor(prefs.notificationCount))
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 2_000L
    }
}
