package com.blizzardcaron.freeolleefaces.auto

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.blizzardcaron.freeolleefaces.ble.OlleeBleClient
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.health.StepsRepository
import com.blizzardcaron.freeolleefaces.notifications.NotificationCount
import com.blizzardcaron.freeolleefaces.notify.ErrorNotifier
import com.blizzardcaron.freeolleefaces.notify.FailureKind
import com.blizzardcaron.freeolleefaces.notify.NotifyAction
import com.blizzardcaron.freeolleefaces.notify.NotifyDecision
import com.blizzardcaron.freeolleefaces.notify.StepsFailureClassifier
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.sun.SunCalc
import com.blizzardcaron.freeolleefaces.weather.OpenMeteoClient
import com.blizzardcaron.freeolleefaces.weather.RetryPolicy
import com.blizzardcaron.freeolleefaces.weather.WeatherFetchError
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Performs one due send for the selected source, then enqueues the next chain run.
 * Reads all state from Prefs; uses the saved coordinates (no location fix).
 */
class AutoUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)

        val face = prefs.activeFace
        val lat = prefs.lastLat
        val lng = prefs.lastLng
        val address = prefs.watchAddress

        // CUSTOM has no schedule; clear any stale error notification and stop the chain.
        if (face == ActiveFace.CUSTOM) {
            applyHealth(ctx, prefs, null, inSleep = false)
            return Result.success()
        }

        // STEPS needs only a watch (no coordinates); handle before the location guard.
        if (face == ActiveFace.STEPS) {
            if (address == null) {
                prefs.recordAutoSend("Skipped: set watch in app")
                applyHealth(ctx, prefs, FailureKind.SETUP_INCOMPLETE, inSleepNow(prefs))
                return Result.success()
            }
            return runSteps(ctx, prefs, address, ZonedDateTime.now(ZoneId.systemDefault()))
        }

        // NOTIFICATIONS needs only a watch; push the cached count as a backstop to the
        // listener's live pushes. Handle before the location guard.
        if (face == ActiveFace.NOTIFICATIONS) {
            if (address == null) {
                prefs.recordAutoSend("Skipped: set watch in app")
                applyHealth(ctx, prefs, FailureKind.SETUP_INCOMPLETE, inSleepNow(prefs))
                return Result.success()
            }
            return runNotifications(ctx, prefs, address, ZonedDateTime.now(ZoneId.systemDefault()))
        }

        if (lat == null || lng == null || address == null) {
            prefs.recordAutoSend("Skipped: set location/watch in app")
            applyHealth(ctx, prefs, FailureKind.SETUP_INCOMPLETE, inSleepNow(prefs))
            return Result.success()
        }

        val now = ZonedDateTime.now(ZoneId.systemDefault())
        return when (face) {
            ActiveFace.TEMPERATURE -> runTemperature(ctx, prefs, lat, lng, address, now)
            ActiveFace.SUN -> runSun(ctx, prefs, lat, lng, address, now)
            ActiveFace.STEPS, ActiveFace.CUSTOM, ActiveFace.NOTIFICATIONS -> Result.success() // handled above
        }
    }

    private suspend fun runSteps(
        ctx: Context,
        prefs: Prefs,
        address: String,
        now: ZonedDateTime,
    ): Result {
        val sleep = if (prefs.sleepEnabled) {
            SleepWindow(prefs.sleepStartMin, prefs.sleepEndMin)
        } else null
        val nowMinOfDay = now.hour * 60 + now.minute
        val inSleep = sleep != null &&
            AutoUpdateSchedule.isInSleepWindow(nowMinOfDay, sleep.startMin, sleep.endMin)

        // Set true only inside the !inSleep block when handleSendFailure enqueues a backstop
        // retry; the asleep branch never writes it. Guards the normal re-arm below so a failed
        // run enqueues either a backstop or the normal next run, never both.
        var backstopped = false
        if (!inSleep) {
            StepsRepository(ctx).todaySteps()
                .onSuccess { count ->
                    prefs.recordStepsFetch(count)
                    val payload = DisplayFormatter.steps(count)
                    OlleeBleClient(ctx).send(address, payload)
                        .onSuccess {
                            prefs.recordAutoSend("Sent '$payload'")
                            applyHealth(ctx, prefs, null, inSleep)
                        }
                        .onFailure {
                            backstopped = handleSendFailure(
                                ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep,
                            )
                        }
                }
                .onFailure { error ->
                    when (val kind = StepsFailureClassifier.kindFor(error)) {
                        // Transient read glitch with access intact — don't alarm the user; the
                        // chain re-arms below and retries next cycle.
                        null -> prefs.recordAutoSend("Skipped: steps read failed (will retry)")
                        // Genuine access gap (HC unavailable, or steps/background read not
                        // granted) — actionable, so notify with "grant Health access".
                        else -> {
                            prefs.recordAutoSend("Skipped: grant Health access")
                            applyHealth(ctx, prefs, kind, inSleep)
                        }
                    }
                }
        } else {
            prefs.recordAutoSend("Asleep (power saving)")
        }

        if (!backstopped) {
            val fire = AutoUpdateSchedule.nextTemperatureFire(now, prefs.updateIntervalMinutes, sleep)
            val delayMs = Duration.between(now, fire).toMillis().coerceAtLeast(0)
            AutoUpdateScheduler.enqueueNext(ctx, delayMs, sendAttempt = 0)
        }
        return Result.success()
    }

    private suspend fun runNotifications(
        ctx: Context,
        prefs: Prefs,
        address: String,
        now: ZonedDateTime,
    ): Result {
        val sleep = if (prefs.sleepEnabled) {
            SleepWindow(prefs.sleepStartMin, prefs.sleepEndMin)
        } else null
        val nowMinOfDay = now.hour * 60 + now.minute
        val inSleep = sleep != null &&
            AutoUpdateSchedule.isInSleepWindow(nowMinOfDay, sleep.startMin, sleep.endMin)

        var backstopped = false
        if (!inSleep) {
            val count = prefs.notificationCount
            val packet = NotificationCount.packetFor(count)
            OlleeBleClient(ctx).sendPacket(address, packet)
                .onSuccess {
                    prefs.recordAutoSend("Sent notifications: $count")
                    applyHealth(ctx, prefs, null, inSleep)
                }
                .onFailure {
                    backstopped = handleSendFailure(ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep)
                }
        } else {
            prefs.recordAutoSend("Asleep (power saving)")
        }

        if (!backstopped) {
            val fire = AutoUpdateSchedule.nextTemperatureFire(now, prefs.updateIntervalMinutes, sleep)
            val delayMs = Duration.between(now, fire).toMillis().coerceAtLeast(0)
            AutoUpdateScheduler.enqueueNext(ctx, delayMs, sendAttempt = 0)
        }
        return Result.success()
    }

    private suspend fun runTemperature(
        ctx: Context,
        prefs: Prefs,
        lat: Double,
        lng: Double,
        address: String,
        now: ZonedDateTime,
    ): Result {
        val sleep = if (prefs.sleepEnabled) {
            SleepWindow(prefs.sleepStartMin, prefs.sleepEndMin)
        } else null
        val nowMinOfDay = now.hour * 60 + now.minute

        // Guard: if we somehow fired inside the sleep window, skip the send.
        val inSleep = sleep != null &&
            AutoUpdateSchedule.isInSleepWindow(nowMinOfDay, sleep.startMin, sleep.endMin)
        // Set true only inside the !inSleep block when handleSendFailure enqueues a backstop
        // retry; the asleep branch never writes it. Guards the normal re-arm below so a failed
        // run enqueues either a backstop or the normal next run, never both.
        var backstopped = false
        if (!inSleep) {
            OpenMeteoClient.currentTemp(lat, lng, prefs.tempUnit, RetryPolicy.Background)
                .onSuccess { temp ->
                    prefs.recordTempFetch(temp, prefs.tempUnit)
                    val payload = DisplayFormatter.temperature(temp, prefs.tempUnit)
                    OlleeBleClient(ctx).send(address, payload)
                        .onSuccess {
                            prefs.recordAutoSend("Sent '$payload'")
                            applyHealth(ctx, prefs, null, inSleep)
                        }
                        .onFailure {
                            backstopped = handleSendFailure(
                                ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep,
                            )
                        }
                }
                .onFailure { err ->
                    val suffix = (err as? WeatherFetchError)?.statusCode?.let { " (HTTP $it)" } ?: ""
                    prefs.recordAutoSend("Skipped: weather fetch failed$suffix")
                    applyHealth(ctx, prefs, FailureKind.WEATHER_FETCH_FAILED, inSleep)
                }
        } else {
            prefs.recordAutoSend("Asleep (power saving)")
        }

        if (!backstopped) {
            val fire = AutoUpdateSchedule.nextTemperatureFire(now, prefs.updateIntervalMinutes, sleep)
            val delayMs = Duration.between(now, fire).toMillis().coerceAtLeast(0)
            AutoUpdateScheduler.enqueueNext(ctx, delayMs, sendAttempt = 0)
        }
        return Result.success()
    }

    private suspend fun runSun(
        ctx: Context,
        prefs: Prefs,
        lat: Double,
        lng: Double,
        address: String,
        now: ZonedDateTime,
    ): Result {
        val inSleep = inSleepNow(prefs)
        val event = SunCalc.nextEvent(now.toInstant(), lat, lng, ZoneId.systemDefault())
        if (event == null) {
            prefs.recordAutoSend("Skipped: no sun event (polar)")
            AutoUpdateScheduler.enqueueNext(ctx, Duration.ofHours(12).toMillis(), sendAttempt = 0)
            return Result.success()
        }

        val payload = DisplayFormatter.sunTime(event.kind, event.time.toLocalTime())
        val sendResult = OlleeBleClient(ctx).send(address, payload)

        if (sendResult.isSuccess) {
            prefs.recordAutoSend("Sent '$payload'")
            applyHealth(ctx, prefs, null, inSleep)
            scheduleAfterEvent(ctx, now, event.time)
        } else if (!handleSendFailure(ctx, prefs, FailureKind.SUN_UNREACHABLE, inSleep)) {
            // Budget exhausted — resume the normal sun chain after the (missed) event.
            scheduleAfterEvent(ctx, now, event.time)
        }
        return Result.success()
    }

    private fun scheduleAfterEvent(ctx: Context, now: ZonedDateTime, eventTime: ZonedDateTime) {
        val wake = AutoUpdateSchedule.nextSunWake(eventTime)
        val delayMs = Duration.between(now, wake).toMillis().coerceAtLeast(0)
        AutoUpdateScheduler.enqueueNext(ctx, delayMs, sendAttempt = 0)
    }

    /**
     * A watch send failed. While budget remains, re-enqueue a backstop run with backoff and post
     * no notification (returns true — caller must NOT also enqueue the normal next run). Once the
     * budget is exhausted, surface [kind] via the notification path and return false so the caller
     * resumes the normal chain. The attempt count is carried in the worker's input data.
     */
    private fun handleSendFailure(
        ctx: Context,
        prefs: Prefs,
        kind: FailureKind,
        inSleep: Boolean,
    ): Boolean {
        val attempt = inputData.getInt(KEY_SEND_ATTEMPT, 0)
        return if (AutoUpdateSchedule.hasBackstopBudget(attempt)) {
            prefs.recordAutoSend("Retry ${attempt + 1}: watch unreachable")
            AutoUpdateScheduler.enqueueNext(
                ctx, AutoUpdateSchedule.backstopDelayMs(attempt), sendAttempt = attempt + 1,
            )
            true
        } else {
            prefs.recordAutoSend("Skipped: watch unreachable")
            applyHealth(ctx, prefs, kind, inSleep)
            false
        }
    }

    private fun applyHealth(ctx: Context, prefs: Prefs, kind: FailureKind?, inSleep: Boolean) {
        when (val action = NotifyDecision.decide(kind, prefs.lastNotifiedKind, inSleep)) {
            is NotifyAction.Notify -> {
                // Only record it as shown if it actually posted; otherwise (permission not yet
                // granted) leave the state untouched so a later grant still surfaces the failure.
                if (ErrorNotifier.notify(ctx, action.kind)) {
                    prefs.lastNotifiedKind = action.kind
                }
            }
            NotifyAction.Clear -> {
                ErrorNotifier.clear(ctx)
                prefs.lastNotifiedKind = null
            }
            NotifyAction.Nothing -> {}
        }
    }

    private fun inSleepNow(prefs: Prefs): Boolean {
        if (!prefs.sleepEnabled) return false
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val m = now.hour * 60 + now.minute
        return AutoUpdateSchedule.isInSleepWindow(m, prefs.sleepStartMin, prefs.sleepEndMin)
    }

    companion object {
        const val KEY_SEND_ATTEMPT = "send_attempt"
    }
}
