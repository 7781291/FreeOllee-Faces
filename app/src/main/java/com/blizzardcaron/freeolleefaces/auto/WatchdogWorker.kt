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
        return Result.success()
    }
}
