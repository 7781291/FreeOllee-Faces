package com.blizzardcaron.freeolleefaces.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blizzardcaron.freeolleefaces.auto.AutoUpdateScheduler
import com.blizzardcaron.freeolleefaces.prefs.Prefs

/**
 * Backs the error notification's "Retry" action: clear the notification and fire one immediate
 * update for the active face via the existing self-rescheduling worker. No new send logic —
 * the worker re-evaluates the notification (clears on success, re-posts on a fresh failure)
 * and re-arms the normal chain.
 */
class RetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        ErrorNotifier.clear(ctx)
        // Forget the dismissed failure so a *repeat* of the same failure on the retried run is
        // treated as fresh and re-notifies. Without this, NotifyDecision would see the prior
        // kind unchanged and stay silent — the user would tap Retry and get no feedback that it
        // failed again.
        Prefs(ctx).lastNotifiedKind = null
        AutoUpdateScheduler.enqueueNext(ctx, 0L, sendAttempt = 0)
    }

    companion object {
        const val REQUEST_CODE = 2001
    }
}
