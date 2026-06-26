package com.blizzardcaron.freeolleefaces.auto

import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Android 17+ diagnostic: logs why the app's scheduled jobs (WorkManager runs through
 * JobScheduler) are stuck pending, via JobScheduler.getPendingJobReasonStats(jobId).
 * Read-only, no behavior change. No-op below API 37.
 */
object JobDiagnostics {

    private const val TAG = "JobDiagnostics"
    private const val API_37 = 37

    fun logPendingJobReasons(context: Context) {
        if (Build.VERSION.SDK_INT < API_37) return
        logPending37(context)
    }

    @RequiresApi(API_37)
    private fun logPending37(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val pending = scheduler.allPendingJobs
        if (pending.isEmpty()) {
            Log.i(TAG, "no pending jobs")
            return
        }
        pending.forEach { job ->
            val stats = scheduler.getPendingJobReasonStats(job.id)
            Log.i(TAG, "job ${job.id}: pending-reason stats=$stats")
        }
    }
}
