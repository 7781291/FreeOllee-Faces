package com.blizzardcaron.freeolleefaces.notifications

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/** Whether the user has granted this app "Notification access" (the listener binding). */
class AndroidNotificationAccess(private val context: Context) : NotificationAccessChecker {
    override fun isGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
}
