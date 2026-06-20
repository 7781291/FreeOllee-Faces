package com.blizzardcaron.freeolleefaces.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.blizzardcaron.freeolleefaces.alarm.Alarm
import com.blizzardcaron.freeolleefaces.alarm.AlarmSchedule
import com.blizzardcaron.freeolleefaces.alarm.AlarmsRepository
import com.blizzardcaron.freeolleefaces.auto.AlarmScheduler
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Owns the alarm cluster extracted from [com.blizzardcaron.freeolleefaces.AppViewModel]: the
 * alarm list (here [items] — renamed from the VM's old `alarms` to avoid
 * `viewModel.alarms.alarms`), the next-fire summary, and the 5 CRUD operations. Moved verbatim;
 * the only renames are `alarms` -> `items` and the injected [clock] (was `Clock.System` directly
 * in the VM). The alarm cluster needs only the repo and scheduler — it never touches BLE, prefs,
 * a coroutine scope, or the snackbar channel.
 */
class AlarmController(
    private val alarmRepo: AlarmsRepository,
    private val alarmScheduler: AlarmScheduler,
    private val clock: Clock = Clock.System,
) {
    var items by mutableStateOf(alarmRepo.getAll())
        private set

    /** e.g. "Next: Tue 7:00 AM · Breeze" — or "No alarms". */
    val nextAlarmSummary: String
        get() = AlarmSchedule.formatNext(
            AlarmSchedule.nextFire(items, clock.now().toLocalDateTime(TimeZone.currentSystemDefault())),
        )

    fun refreshAlarms() { items = alarmRepo.getAll() }

    fun addAlarm() {
        if (items.size >= AlarmsRepository.MAX_ALARMS) return
        alarmRepo.save(Alarm(id = randomId(), hour = 7, minute = 0))
        items = alarmRepo.getAll()
        alarmScheduler.rearm()
    }

    fun saveAlarm(alarm: Alarm) {
        val before = alarmRepo.get(alarm.id)
        alarmRepo.save(alarm)
        items = alarmRepo.getAll()
        // The label is phone-side only — a label-only edit (every keystroke lands here) must not
        // re-push the watch. Anything schedule-affecting re-arms.
        if (before == null || before.copy(label = alarm.label) != alarm) alarmScheduler.rearm()
    }

    fun toggleAlarm(id: String, enabled: Boolean) {
        alarmRepo.get(id)?.let { saveAlarm(it.copy(enabled = enabled)) }
    }

    fun deleteAlarm(id: String) {
        alarmRepo.delete(id)
        items = alarmRepo.getAll()
        alarmScheduler.rearm()
    }
}
