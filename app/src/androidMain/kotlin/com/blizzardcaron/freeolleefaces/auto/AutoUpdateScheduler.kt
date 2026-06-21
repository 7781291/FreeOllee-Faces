package com.blizzardcaron.freeolleefaces.auto

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.TimeUnit

/** Single re-arm entry point for the auto-update chain. */
object AutoUpdateScheduler {

    const val WORK_NAME = "auto_update_chain"
    const val WATCHDOG_NAME = "auto_update_watchdog"

    private const val MINUTES_PER_HOUR = 60
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val WATCHDOG_PERIOD_HOURS = 24L

    /** Re-arm the chain from current Prefs. Safe to call repeatedly (always REPLACEs). */
    fun reschedule(context: Context) {
        val ctx = context.applicationContext
        val prefs = Prefs(appSettings(ctx))
        val wm = WorkManager.getInstance(ctx)

        ensureWatchdog(wm)

        when (prefs.activeComplication) {
            ActiveComplication.CUSTOM -> wm.cancelUniqueWork(WORK_NAME)

            ActiveComplication.TEMPERATURE -> scheduleIntervalFace(ctx, prefs, prefs.updateIntervalMinutes)

            ActiveComplication.STEPS -> scheduleIntervalFace(ctx, prefs, prefs.updateIntervalMinutes)

            ActiveComplication.SUN -> enqueueNext(ctx, 0L, sendAttempt = 0)
        }
    }

    /**
     * Arm the next run for a fixed-interval face (Temperature, Steps): fire now if overdue and
     * awake, otherwise at the next sleep-aware interval boundary.
     */
    private fun scheduleIntervalFace(ctx: Context, prefs: Prefs, intervalMinutes: Int) {
        val zone = TimeZone.currentSystemDefault()
        val now = Clock.System.now().toLocalDateTime(zone)
        val sleep = prefs.pushPauseWindow()
        val nowMinOfDay = now.hour * MINUTES_PER_HOUR + now.minute
        val inSleep = sleep != null &&
            AutoUpdateSchedule.isInSleepWindow(nowMinOfDay, sleep.startMin, sleep.endMin)
        val overdue = prefs.lastAutoSendMs
            ?.let { it + intervalMinutes * MILLIS_PER_MINUTE <= System.currentTimeMillis() } ?: true
        val delayMs = if (overdue && !inSleep) {
            0L
        } else {
            val fire = AutoUpdateSchedule.nextTemperatureFire(now, intervalMinutes, sleep)
            (fire.toInstant(zone).toEpochMilliseconds() - now.toInstant(zone).toEpochMilliseconds())
                .coerceAtLeast(0)
        }
        enqueueNext(ctx, delayMs, sendAttempt = 0)
    }

    /** Enqueue the single next chain run (REPLACE keeps exactly one pending). */
    fun enqueueNext(context: Context, delayMs: Long, sendAttempt: Int) {
        val data = Data.Builder()
            .putInt(AutoUpdateWorker.KEY_SEND_ATTEMPT, sendAttempt)
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
        val req = PeriodicWorkRequestBuilder<WatchdogWorker>(WATCHDOG_PERIOD_HOURS, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(WATCHDOG_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
