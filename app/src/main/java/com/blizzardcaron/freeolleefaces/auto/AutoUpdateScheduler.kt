package com.blizzardcaron.freeolleefaces.auto

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Single re-arm entry point for the auto-update chain. */
object AutoUpdateScheduler {

    const val WORK_NAME = "auto_update_chain"
    const val WATCHDOG_NAME = "auto_update_watchdog"

    /** Re-arm the chain from current Prefs. Safe to call repeatedly (always REPLACEs). */
    fun reschedule(context: Context) {
        val ctx = context.applicationContext
        val prefs = Prefs(ctx)
        val wm = WorkManager.getInstance(ctx)

        ensureWatchdog(wm)

        when (prefs.autoSource) {
            AutoSource.OFF -> wm.cancelUniqueWork(WORK_NAME)

            AutoSource.TEMPERATURE -> {
                val now = ZonedDateTime.now(ZoneId.systemDefault())
                val sleep = if (prefs.sleepEnabled) {
                    SleepWindow(prefs.sleepStartMin, prefs.sleepEndMin)
                } else null
                val interval = prefs.tempIntervalMinutes
                val nowMinOfDay = now.hour * 60 + now.minute
                val inSleep = sleep != null &&
                    AutoUpdateSchedule.isInSleepWindow(nowMinOfDay, sleep.startMin, sleep.endMin)
                val overdue = prefs.lastAutoSendMs
                    ?.let { it + interval * 60_000L <= System.currentTimeMillis() } ?: true
                val delayMs = if (overdue && !inSleep) {
                    0L
                } else {
                    val fire = AutoUpdateSchedule.nextTemperatureFire(now, interval, sleep)
                    Duration.between(now, fire).toMillis().coerceAtLeast(0)
                }
                enqueueNext(ctx, delayMs, sunAttempt = 0)
            }

            AutoSource.SUN -> enqueueNext(ctx, 0L, sunAttempt = 0)
        }
    }

    /** Enqueue the single next chain run (REPLACE keeps exactly one pending). */
    fun enqueueNext(context: Context, delayMs: Long, sunAttempt: Int) {
        val data = Data.Builder()
            .putInt(AutoUpdateWorker.KEY_SUN_ATTEMPT, sunAttempt)
            .build()
        val req = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    private fun ensureWatchdog(wm: WorkManager) {
        val req = PeriodicWorkRequestBuilder<WatchdogWorker>(24, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(WATCHDOG_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
