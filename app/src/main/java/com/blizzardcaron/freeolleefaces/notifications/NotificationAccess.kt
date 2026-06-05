package com.blizzardcaron.freeolleefaces.notifications

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/** Whether the user has granted this app "Notification access" (the listener binding). */
object NotificationAccess {
    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
}
