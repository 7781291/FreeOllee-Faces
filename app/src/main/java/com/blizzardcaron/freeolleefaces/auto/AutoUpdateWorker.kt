package com.blizzardcaron.freeolleefaces.auto

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.blizzardcaron.freeolleefaces.ble.OlleeBleClient
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
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

        val source = prefs.autoSource
        val lat = prefs.lastLat
        val lng = prefs.lastLng
        val address = prefs.watchAddress

        // OFF, or not enough config to send: stop the chain. Re-arm triggers restart it.
        if (source == AutoSource.OFF) return Result.success()
        if (lat == null || lng == null || address == null) {
            prefs.recordAutoSend("Skipped: set location/watch in app")
            return Result.success()
        }

        val now = ZonedDateTime.now(ZoneId.systemDefault())
        return when (source) {
            AutoSource.TEMPERATURE -> runTemperature(ctx, prefs, lat, lng, address, now)
            AutoSource.SUN -> runSun(ctx, prefs, lat, lng, address, now)
            AutoSource.OFF -> Result.success()
        }
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
        if (!inSleep) {
            OpenMeteoClient.currentTemp(lat, lng, prefs.tempUnit, RetryPolicy.Background)
                .onSuccess { temp ->
                    val payload = DisplayFormatter.temperature(temp, prefs.tempUnit)
                    OlleeBleClient(ctx).send(address, payload)
                        .onSuccess { prefs.recordAutoSend("Sent '$payload'") }
                        .onFailure { prefs.recordAutoSend("Skipped: watch unreachable") }
                }
                .onFailure { err ->
                    val suffix = (err as? WeatherFetchError)?.statusCode?.let { " (HTTP $it)" } ?: ""
                    prefs.recordAutoSend("Skipped: weather fetch failed$suffix")
                }
        } else {
            prefs.recordAutoSend("Asleep (power saving)")
        }

        val fire = AutoUpdateSchedule.nextTemperatureFire(now, prefs.tempIntervalMinutes, sleep)
        val delayMs = Duration.between(now, fire).toMillis().coerceAtLeast(0)
        AutoUpdateScheduler.enqueueNext(ctx, delayMs, sunAttempt = 0)
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
        val event = SunCalc.nextEvent(now.toInstant(), lat, lng, ZoneId.systemDefault())
        if (event == null) {
            prefs.recordAutoSend("Skipped: no sun event (polar)")
            AutoUpdateScheduler.enqueueNext(ctx, Duration.ofHours(12).toMillis(), sunAttempt = 0)
            return Result.success()
        }

        val payload = DisplayFormatter.sunTime(event.kind, event.time.toLocalTime())
        val sendResult = OlleeBleClient(ctx).send(address, payload)

        if (sendResult.isSuccess) {
            prefs.recordAutoSend("Sent '$payload'")
            scheduleAfterEvent(ctx, now, event.time)
        } else {
            val attempt = inputData.getInt(KEY_SUN_ATTEMPT, 0)
            if (attempt < MAX_SUN_RETRIES) {
                prefs.recordAutoSend("Retry ${attempt + 1}: watch unreachable")
                AutoUpdateScheduler.enqueueNext(
                    ctx, Duration.ofMinutes(15).toMillis(), sunAttempt = attempt + 1,
                )
            } else {
                prefs.recordAutoSend("Skipped: watch unreachable")
                scheduleAfterEvent(ctx, now, event.time)
            }
        }
        return Result.success()
    }

    private fun scheduleAfterEvent(ctx: Context, now: ZonedDateTime, eventTime: ZonedDateTime) {
        val wake = AutoUpdateSchedule.nextSunWake(eventTime)
        val delayMs = Duration.between(now, wake).toMillis().coerceAtLeast(0)
        AutoUpdateScheduler.enqueueNext(ctx, delayMs, sunAttempt = 0)
    }

    companion object {
        const val KEY_SUN_ATTEMPT = "sun_attempt"
        const val MAX_SUN_RETRIES = 3
    }
}
