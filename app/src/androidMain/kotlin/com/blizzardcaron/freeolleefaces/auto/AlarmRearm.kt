package com.blizzardcaron.freeolleefaces.auto

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blizzardcaron.freeolleefaces.alarm.AlarmSchedule
import com.blizzardcaron.freeolleefaces.alarm.AlarmsRepository
import com.blizzardcaron.freeolleefaces.ble.AndroidBleClient
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.prefs.alarmSettings
import com.blizzardcaron.freeolleefaces.prefs.appSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * `nextFire + 60 s`. A failed BLE push is tolerated: the watch keeps its last good alarm and the
 * next trigger / app open / boot retries — eventually consistent.
 *
 * The BLE push is **debounced** ([PUSH_DEBOUNCE_MS]): inline alarm cards call rearm on every edit
 * (each H/M digit, each day-chip tap), and back-to-back GATT connects would collide. Each call
 * bumps a generation counter; only the latest survives the delay and pushes, with the final state.
 * The AlarmManager trigger is NOT debounced — it is idempotent and must never be dropped.
 */
object AlarmRearm {

    const val TAG = "ALARM_REARM"
    private const val REQUEST_CODE = 4025   // one slot: each schedule replaces the last
    private const val PUSH_DEBOUNCE_MS = 750L
    private val generation = AtomicInteger()

    /**
     * Runs the full pass. Each call's [onComplete] fires after its own push attempt — or, when
     * superseded by a newer call, after its debounce delay elapses without pushing.
     */
    fun rearm(context: Context, onComplete: () -> Unit = {}) {
        val ctx = context.applicationContext

        // Schedule the trigger first — it must survive even if the push below fails.
        val am = ctx.getSystemService(AlarmManager::class.java)
        val trigger = PendingIntent.getBroadcast(
            ctx, REQUEST_CODE, Intent(ctx, AlarmRearmReceiver::class.java),
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
        if (address == null) {
            Log.w(TAG, "no watch selected — skipping push")
            onComplete()
            return
        }
        val myGen = generation.incrementAndGet()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(PUSH_DEBOUNCE_MS)
                if (generation.get() != myGen) return@launch   // superseded by a newer rearm
                // Recompute at push time so a burst of edits sends the final state.
                val latest = AlarmSchedule.nextFire(
                    AlarmsRepository(alarmSettings(ctx)).getAll(),
                    Clock.System.now().toLocalDateTime(zone),
                )
                val result = AndroidBleClient(ctx).sendPacket(address, AlarmSchedule.packetFor(latest))
                Log.i(
                    TAG,
                    if (result.isSuccess) "push OK (${if (latest != null) "armed ${latest.dateTime}" else "disarm"})"
                    else "push FAIL ${result.exceptionOrNull()?.message} (will retry on next trigger/open/boot)",
                )
            } finally {
                onComplete()
            }
        }
    }
}
