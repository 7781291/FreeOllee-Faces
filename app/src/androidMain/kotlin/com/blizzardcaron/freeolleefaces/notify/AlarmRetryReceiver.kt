package com.blizzardcaron.freeolleefaces.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blizzardcaron.freeolleefaces.auto.AlarmRearm

/**
 * Backs the alarm-failure notification's "Retry" action: clear the notification and run a
 * fresh re-arm pass with a full backstop budget. [goAsync] keeps the process alive for the
 * BLE push (same pattern as AlarmRearmReceiver).
 */
class AlarmRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        ErrorNotifier.clearAlarm(ctx)
        val pending = goAsync()
        AlarmRearm.rearm(ctx) { pending.finish() }
    }

    companion object {
        const val REQUEST_CODE = 2002
    }
}
