package com.blizzardcaron.freeolleefaces.auto

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blizzardcaron.freeolleefaces.alarm.AlarmRearmRecovery
import com.blizzardcaron.freeolleefaces.alarm.AlarmSchedule
import com.blizzardcaron.freeolleefaces.alarm.AlarmsRepository
import com.blizzardcaron.freeolleefaces.ble.AlarmReadback
import com.blizzardcaron.freeolleefaces.ble.AndroidBleClient
import com.blizzardcaron.freeolleefaces.notify.ErrorNotifier
import com.blizzardcaron.freeolleefaces.notify.FailureKind
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.prefs.alarmSettings
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * The single alarm re-arm pass: compute the next fire across the saved alarms, push the matching
 * armed/disarm `0x25` frame to the watch, and schedule (or cancel) the exact AlarmManager trigger
 * that re-runs this pass ~1 minute after the alarm fires — which is what advances repeat-day
 * alarms to their next occurrence (the watch itself has no day-of-week field).
 *
 * The watch cannot tell the phone an alarm fired, so the trigger time is our own computed
 * `nextFire + 60 s`. A failed BLE push is NOT left for the next trigger — that next trigger
 * only runs after the *next* alarm should already have fired, which would silently skip it
 * (observed on-device 2026-06-12). Instead [AlarmRearmRecovery] drives a backstop chain:
 * exact re-arm passes at 2/5/15 min, then the alarm-failure notification once the budget is
 * spent. A later successful push cancels the chain and clears the notification.
 *
 * The BLE push is **debounced** ([PUSH_DEBOUNCE_MS]): inline alarm cards call rearm on every edit
 * (each H/M digit, each day-chip tap), and back-to-back GATT connects would collide. Each call
 * bumps a generation counter; only the latest survives the delay and pushes, with the final state.
 * The AlarmManager trigger is NOT debounced — it is idempotent and must never be dropped.
 */
object AlarmRearm {

    const val TAG = "ALARM_REARM"
    const val EXTRA_ATTEMPT = "rearm_attempt"
    private const val REQUEST_CODE = 4025 // fire+60s trigger: one slot, each schedule replaces the last
    private const val BACKSTOP_REQUEST_CODE = 4026 // failed-push backstop: separate slot, same receiver
    private const val PUSH_DEBOUNCE_MS = 750L
    private val generation = AtomicInteger()
    private val pushMutex = Mutex()

    /**
     * Outcome of each completed push attempt, for UI snackbars — including "no watch selected",
     * so edits never silently look like they reached a watch. Debounce-superseded calls never
     * emit. No-subscriber emissions (receiver/boot pushes with the app closed) drop harmlessly.
     */
    val pushResults = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Runs the full pass. Each call's [onComplete] fires after its own push attempt — or, when
     * superseded by a newer call, after its debounce delay elapses without pushing.
     */
    fun rearm(context: Context, attempt: Int = 0, onComplete: () -> Unit = {}) {
        val ctx = context.applicationContext

        // Schedule the trigger first — it must survive even if the push below fails.
        val am = ctx.getSystemService(AlarmManager::class.java)
        val trigger = PendingIntent.getBroadcast(
            ctx,
            REQUEST_CODE,
            Intent(ctx, AlarmRearmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val zone = TimeZone.currentSystemDefault()
        val next = AlarmSchedule.nextFire(
            AlarmsRepository(alarmSettings(ctx)).getAll(),
            Clock.System.now().toLocalDateTime(zone),
        )
        if (next != null) {
            val atMs = next.dateTime.toInstant(zone).toEpochMilliseconds() + 60_000L
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, trigger)
                Log.i(TAG, "next fire ${next.dateTime}; trigger set for fire+60s")
            } else {
                // SCHEDULE_EXACT_ALARM revoked (possible on API 31-32 only): an inexact trigger
                // still re-arms the watch, just possibly late.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, trigger)
                Log.w(TAG, "next fire ${next.dateTime}; exact alarms not permitted — inexact trigger set")
            }
        } else {
            am.cancel(trigger)
            Log.i(TAG, "no alarms due; trigger cancelled, will push disarm")
        }

        val address = Prefs(appSettings(ctx)).watchAddress
        val myGen = generation.incrementAndGet()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(PUSH_DEBOUNCE_MS)
                if (generation.get() != myGen) return@launch // superseded by a newer rearm
                if (address == null) {
                    // Inside the debounce so an edit burst surfaces ONE snackbar, not one per tap.
                    // Same message the manual send path shows (AppViewModel.pushTimerFrame).
                    Log.w(TAG, "no watch selected — skipping push")
                    pushResults.tryEmit("No watch selected — open Settings (⚙)")
                    return@launch
                }
                // Serialize pushes: GATT round-trips run seconds — far longer than the debounce —
                // so without the lock an older push still in flight can land AFTER a newer one and
                // leave the watch holding stale state (observed on-device 2026-06-11).
                pushMutex.withLock {
                    if (generation.get() != myGen) return@launch // superseded while waiting
                    // Recompute at push time so a burst of edits sends the final state.
                    val latest = AlarmSchedule.nextFire(
                        AlarmsRepository(alarmSettings(ctx)).getAll(),
                        Clock.System.now().toLocalDateTime(zone),
                    )
                    val ble = AndroidBleClient(ctx)
                    val packet = AlarmSchedule.packetFor(latest)
                    val sent = ble.sendPacket(address, packet)
                    val confirmed = sent.isSuccess && AlarmReadback.confirm(
                        ble, address, packet,
                        enabled = latest != null,
                        hour = latest?.hour ?: 0, minute = latest?.minute ?: 0, chimeIndex = latest?.chimeIndex ?: 0,
                    )
                    val outcome = if (confirmed) {
                        val detail = if (latest != null) "armed ${latest.dateTime}" else "disarm"
                        "push+confirm OK ($detail) [attempt $attempt]"
                    } else {
                        val reason = sent.exceptionOrNull()?.message ?: "read-back mismatch"
                        "push/confirm FAIL $reason [attempt $attempt]"
                    }
                    Log.i(TAG, outcome)
                    val action = AlarmRearmRecovery.afterPush(confirmed, attempt)
                    applyRecovery(ctx, am, action)
                    pushResults.tryEmit(
                        when {
                            !confirmed && action is AlarmRearmRecovery.Action.ScheduleRetry ->
                                "Alarm send failed — long-press ALARM to wake the watch (retrying automatically)"
                            !confirmed ->
                                "Alarm send failed after several tries — long-press ALARM to wake the watch, then tap Retry in the notification"
                            latest != null -> "Sent to watch — ${AlarmSchedule.formatNext(latest)}"
                            else -> "Sent to watch — alarm off"
                        },
                    )
                }
            } finally {
                onComplete()
            }
        }
    }

    /** Applies one [AlarmRearmRecovery] outcome: backstop scheduling, cleanup, or notification. */
    private fun applyRecovery(ctx: Context, am: AlarmManager, action: AlarmRearmRecovery.Action) {
        when (action) {
            AlarmRearmRecovery.Action.ClearFailure -> {
                am.cancel(backstopTrigger(ctx, nextAttempt = 0))
                ErrorNotifier.clearAlarm(ctx)
            }
            is AlarmRearmRecovery.Action.ScheduleRetry -> {
                val atMs = System.currentTimeMillis() + action.delayMs
                val pi = backstopTrigger(ctx, action.nextAttempt)
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                }
                Log.i(TAG, "backstop retry ${action.nextAttempt} in ${action.delayMs / 1000}s")
            }
            AlarmRearmRecovery.Action.NotifyFailure -> {
                Log.w(TAG, "backstop budget spent — posting alarm-failure notification")
                ErrorNotifier.notify(ctx, FailureKind.ALARM_UNREACHABLE)
            }
        }
    }

    /** Extras don't participate in PendingIntent matching, so the same shape serves cancel(). */
    private fun backstopTrigger(ctx: Context, nextAttempt: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            BACKSTOP_REQUEST_CODE,
            Intent(ctx, AlarmRearmReceiver::class.java).putExtra(EXTRA_ATTEMPT, nextAttempt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
