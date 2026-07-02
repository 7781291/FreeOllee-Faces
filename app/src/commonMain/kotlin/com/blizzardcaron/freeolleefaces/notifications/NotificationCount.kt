package com.blizzardcaron.freeolleefaces.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.blizzardcaron.freeolleefaces.ble.AndroidBleClient
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Counts undismissed, non-persistent notifications and pushes the badge to the watch's
 * weekday slot. Requires the user to grant "Notification access" in system settings. Live
 * pushes are debounced (~2 s) and only happen while the notification overlay is enabled
 * ([Prefs.notificationsEnabled]) — independent of which name-tag face is active;
 * [com.blizzardcaron.freeolleefaces.auto.AutoUpdateWorker] is the periodic backstop.
 *
 * When the count *increases* (a new notification arrived, not a dismissal), the watch also
 * plays a chime via the alarm-record preview mechanism ([OlleeProtocol.buildAlarmPacket] with
 * `playNow = true` — the firmware's transient "Try chime", which is not persisted). The chime
 * is gated by [Prefs.notificationChimeEnabled] and suppressed during the quiet-hours window
 * (mirroring [Prefs.pushPauseWindow] semantics) so the watch stays silent at night. A debounce
 * flurry chimes at most once.
 *
 * Two inherent limitations of the weekday slot (0x34), both unavoidable on this hardware:
 * - **Clock-face only:** the count renders only while the watch is on the Clock face; other
 *   firmware faces (Alarm/Timer/Stopwatch) show their own upper-panel content.
 * - **Shared register:** the official Ollee app writes the same 0x34 slot, so last-writer-wins —
 *   it can overwrite the count (and vice-versa). The worker backstop re-asserts on its cycle.
 */
class NotificationCountService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pushJob: Job? = null
    private val prefs by lazy { Prefs(appSettings(applicationContext)) }

    /** True when the pending (debounced) push was caused by a count increase → chime once. */
    @Volatile
    private var pendingChime = false

    override fun onListenerConnected() {
        // Seed and sync on (re)bind, even if the count is unchanged. Never chimes.
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
        val previous = prefs.notificationCount
        val changed = count != previous
        if (count > previous) pendingChime = true
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
        // Debounce: a flurry of posts/removals collapses into one push (and at most one chime).
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(DEBOUNCE_MS)
            val chime = pendingChime
            pendingChime = false
            if (!prefs.notificationsEnabled) return@launch
            val addr = prefs.watchAddress ?: return@launch
            val client = AndroidBleClient(applicationContext)
            client.sendPacket(addr, NotificationCount.packetFor(prefs.notificationCount))
            if (chime && prefs.notificationChimeEnabled && !inQuietHours()) {
                // Transient "Try chime" preview (playNow) — sounds immediately, not persisted.
                client.sendPacket(
                    addr,
                    OlleeProtocol.buildAlarmPacket(
                        hour = 0,
                        minute = 0,
                        chimeIndex = prefs.notificationChimeIndex,
                        playNow = true,
                    ),
                )
            }
        }
    }

    /**
     * Whether "now" falls inside the app's quiet-hours window. Mirrors
     * [Prefs.pushPauseWindow]'s gating (both power saving and quiet hours must be on) and
     * handles overnight wrap (e.g. 22:00 → 07:00).
     */
    private fun inQuietHours(): Boolean {
        if (!prefs.powerSavingEnabled || !prefs.quietHoursEnabled) return false
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR + cal.get(Calendar.MINUTE)
        val start = prefs.quietHoursStartMin
        val end = prefs.quietHoursEndMin
        return if (start <= end) nowMin in start until end else nowMin >= start || nowMin < end
    }

    companion object {
        private const val DEBOUNCE_MS = 2_000L
        private const val MINUTES_PER_HOUR = 60
    }
}
