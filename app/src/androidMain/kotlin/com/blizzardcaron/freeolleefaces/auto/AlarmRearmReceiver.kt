package com.blizzardcaron.freeolleefaces.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires ~1 minute after each computed watch-alarm fire (exact AlarmManager trigger set by
 * [AlarmRearm]) and re-runs the re-arm pass, advancing the watch's single alarm to the next
 * occurrence. [goAsync] keeps the process alive for the BLE push (same pattern as
 * DevToolsReceiver).
 */
class AlarmRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        AlarmRearm.rearm(context) { pending.finish() }
    }
}
