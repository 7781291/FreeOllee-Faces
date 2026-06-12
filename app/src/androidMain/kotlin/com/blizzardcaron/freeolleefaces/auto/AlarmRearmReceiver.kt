package com.blizzardcaron.freeolleefaces.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires for both of [AlarmRearm]'s exact triggers — ~1 minute after each computed watch-alarm
 * fire (no extra: fresh attempt-0 budget) and each failed-push backstop retry (attempt carried
 * in [AlarmRearm.EXTRA_ATTEMPT]) — and re-runs the re-arm pass. [goAsync] keeps the process
 * alive for the BLE push (same pattern as DevToolsReceiver).
 */
class AlarmRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val attempt = intent.getIntExtra(AlarmRearm.EXTRA_ATTEMPT, 0)
        AlarmRearm.rearm(context, attempt) { pending.finish() }
    }
}
