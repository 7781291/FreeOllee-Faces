package com.blizzardcaron.freeolleefaces.auto

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Once-daily insurance: re-arm the chain in case it was dropped while the app was never opened. */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AutoUpdateScheduler.reschedule(applicationContext)
        val prefs = com.blizzardcaron.freeolleefaces.prefs.Prefs(
            com.blizzardcaron.freeolleefaces.prefs.appSettings(applicationContext),
        )
        val ble = com.blizzardcaron.freeolleefaces.ble.AndroidBleClient(applicationContext)
        com.blizzardcaron.freeolleefaces.activity.ActivityRecovery.recoverIfStranded(
            prefs = prefs,
            store = com.blizzardcaron.freeolleefaces.activity.AndroidActivityTrackStore(applicationContext),
            autoSleep = com.blizzardcaron.freeolleefaces.activity.ActivityAutoSleepManager(ble, prefs),
            watchAddress = prefs.watchAddress,
            sessionRunning = com.blizzardcaron.freeolleefaces.activity.ActivitySessionHost.isRunning,
        )
        return Result.success()
    }
}
