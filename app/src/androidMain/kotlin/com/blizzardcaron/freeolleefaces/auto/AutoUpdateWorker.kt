package com.blizzardcaron.freeolleefaces.auto

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.blizzardcaron.freeolleefaces.ble.AndroidBleClient
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.health.AndroidStepsProvider
import com.blizzardcaron.freeolleefaces.notifications.AndroidNotificationAccess
import com.blizzardcaron.freeolleefaces.notifications.NotificationCount
import com.blizzardcaron.freeolleefaces.notify.ErrorNotifier
import com.blizzardcaron.freeolleefaces.notify.FailureKind
import com.blizzardcaron.freeolleefaces.notify.NotifyAction
import com.blizzardcaron.freeolleefaces.notify.NotifyDecision
import com.blizzardcaron.freeolleefaces.notify.StepsFailureClassifier
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import com.blizzardcaron.freeolleefaces.sun.SunCalc
import com.blizzardcaron.freeolleefaces.weather.OpenMeteoClient
import com.blizzardcaron.freeolleefaces.weather.RetryPolicy
import com.blizzardcaron.freeolleefaces.weather.WeatherFetchError
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

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
        val prefs = Prefs(appSettings(ctx))

        val face = prefs.activeFace
        val lat = prefs.lastLat
        val lng = prefs.lastLng
        val address = prefs.watchAddress

        // The notification count is an independent overlay on the weekday slot, decoupled from the
        // name-tag face. Best-effort backstop to the listener's live pushes; it piggybacks on
        // whatever schedule the active face arms and never affects that face's success/scheduling.
        maybePushNotificationCount(ctx, prefs, address)

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
            return runSteps(ctx, prefs, address, nowLocal())
        }

        if (lat == null || lng == null || address == null) {
            prefs.recordAutoSend("Skipped: set location/watch in app")
            applyHealth(ctx, prefs, FailureKind.SETUP_INCOMPLETE, inSleepNow(prefs))
            return Result.success()
        }

        val now = nowLocal()
        return when (face) {
            ActiveFace.TEMPERATURE -> runTemperature(ctx, prefs, lat, lng, address, now)
            ActiveFace.SUN -> runSun(ctx, prefs, lat, lng, address, now)
            ActiveFace.STEPS, ActiveFace.CUSTOM -> Result.success() // handled above
        }
    }

    /**
     * Best-effort re-push of the notification overlay to the weekday slot when it's enabled, the
     * watch is set, and we're awake. Fire-and-forget: it must not touch the active face's failure
     * accounting or re-arm logic (the live listener service is the primary path).
     *
     * Self-heals the orphaned-count case: if the overlay is enabled but notification access has
     * been revoked, the live service is dead and the count can no longer update — so instead of
     * re-asserting a stale number we restore the real weekday table (`packetFor(0)`).
     */
    private suspend fun maybePushNotificationCount(ctx: Context, prefs: Prefs, address: String?) {
        if (!prefs.notificationsEnabled || address == null || inSleepNow(prefs)) return
        val count = if (AndroidNotificationAccess(ctx).isGranted()) prefs.notificationCount else 0
        runCatching {
            AndroidBleClient(ctx).sendPacket(address, NotificationCount.packetFor(count))
        }
    }

    private suspend fun runSteps(
        ctx: Context,
        prefs: Prefs,
        address: String,
        now: LocalDateTime,
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
            AndroidStepsProvider(ctx).todaySteps()
                .onSuccess { count ->
                    prefs.recordStepsFetch(count)
                    val payload = DisplayFormatter.steps(count)
                    AndroidBleClient(ctx).send(address, payload)
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
                    val kind = StepsFailureClassifier.kindFor(error)
                    val cached = prefs.lastStepCount
                    if (cached != null) {
                        // Read failed but we have a cached count — push it marked stale ('E').
                        val payload = DisplayFormatter.steps(cached, stale = true)
                        AndroidBleClient(ctx).send(address, payload)
                            .onSuccess { prefs.recordAutoSend("Sent stale '$payload'") }
                            .onFailure {
                                backstopped = handleSendFailure(
                                    ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep,
                                )
                            }
                    } else {
                        prefs.recordAutoSend("Skipped: steps read failed (will retry)")
                    }
                    // A genuine access gap is still actionable — surface it regardless of the
                    // stale push above. Transient glitches (kind == null) stay quiet.
                    if (kind != null) applyHealth(ctx, prefs, kind, inSleep)
                }
        } else {
            prefs.recordAutoSend("Asleep (power saving)")
        }

        if (!backstopped) {
            val fire = AutoUpdateSchedule.nextTemperatureFire(now, prefs.updateIntervalMinutes, sleep)
            val delayMs = millisBetween(now, fire)
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
        now: LocalDateTime,
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
                    AndroidBleClient(ctx).send(address, payload)
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
                    val cached = prefs.tempValue
                    if (cached != null && prefs.tempCacheUnit == prefs.tempUnit) {
                        // Fetch failed but we have a cached temp — push it marked stale ('E').
                        val payload = DisplayFormatter.temperature(cached, prefs.tempUnit, stale = true)
                        AndroidBleClient(ctx).send(address, payload)
                            .onSuccess {
                                prefs.recordAutoSend("Sent stale '$payload'$suffix")
                                applyHealth(ctx, prefs, null, inSleep)
                            }
                            .onFailure {
                                backstopped = handleSendFailure(
                                    ctx, prefs, FailureKind.WATCH_UNREACHABLE, inSleep,
                                )
                            }
                    } else {
                        prefs.recordAutoSend("Skipped: weather fetch failed$suffix")
                        applyHealth(ctx, prefs, FailureKind.WEATHER_FETCH_FAILED, inSleep)
                    }
                }
        } else {
            prefs.recordAutoSend("Asleep (power saving)")
        }

        if (!backstopped) {
            val fire = AutoUpdateSchedule.nextTemperatureFire(now, prefs.updateIntervalMinutes, sleep)
            val delayMs = millisBetween(now, fire)
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
        now: LocalDateTime,
    ): Result {
        val inSleep = inSleepNow(prefs)
        val event = SunCalc.nextEvent(
            now.toInstant(TimeZone.currentSystemDefault()), lat, lng, TimeZone.currentSystemDefault(),
        )
        if (event == null) {
            prefs.recordAutoSend("Skipped: no sun event (polar)")
            AutoUpdateScheduler.enqueueNext(ctx, 12L * 60L * 60L * 1000L, sendAttempt = 0)
            return Result.success()
        }

        val payload = DisplayFormatter.sunTime(event.kind, event.time.time)
        val sendResult = AndroidBleClient(ctx).send(address, payload)

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

    private fun scheduleAfterEvent(ctx: Context, now: LocalDateTime, eventTime: LocalDateTime) {
        val wake = AutoUpdateSchedule.nextSunWake(eventTime)
        val delayMs = millisBetween(now, wake)
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
        val now = nowLocal()
        val m = now.hour * 60 + now.minute
        return AutoUpdateSchedule.isInSleepWindow(m, prefs.sleepStartMin, prefs.sleepEndMin)
    }

    private fun nowLocal(): LocalDateTime =
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    /** Wall-clock millis from [from] to [to], localized to the system zone, clamped at 0. */
    private fun millisBetween(from: LocalDateTime, to: LocalDateTime): Long {
        val zone = TimeZone.currentSystemDefault()
        val diff = to.toInstant(zone).toEpochMilliseconds() - from.toInstant(zone).toEpochMilliseconds()
        return diff.coerceAtLeast(0)
    }

    companion object {
        const val KEY_SEND_ATTEMPT = "send_attempt"
    }
}
