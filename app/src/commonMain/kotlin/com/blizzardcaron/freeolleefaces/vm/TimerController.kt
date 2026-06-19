package com.blizzardcaron.freeolleefaces.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.blizzardcaron.freeolleefaces.ble.BleClient
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol
import com.blizzardcaron.freeolleefaces.ble.TimerReadback
import com.blizzardcaron.freeolleefaces.prefs.Prefs
import com.blizzardcaron.freeolleefaces.timer.QuickAlarm
import com.blizzardcaron.freeolleefaces.timer.Reorder
import com.blizzardcaron.freeolleefaces.timer.TimerSet
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import com.blizzardcaron.freeolleefaces.ui.HomeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Owns the timer cluster extracted from [com.blizzardcaron.freeolleefaces.AppViewModel]: timer
 * sets CRUD/reorder, the quick-timer toggles, and the BLE push path (`pushTimerFrame`). Moved
 * verbatim; the only renames are `viewModelScope` -> `scope`, `state.X` -> `state().X`, and
 * `Clock.System` -> the injected [clock]. The shared `HomeState.sending` flag is NOT duplicated
 * here — [state]/[update] read and write the VM's single shared flag (also used by the VM's own
 * `sendCustom` and read by the UI), via the injected accessors.
 */
class TimerController(
    private val timerRepo: TimerSetsRepository,
    private val ble: BleClient,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val showSnackbar: (String) -> Unit,
    private val state: () -> HomeState,
    private val update: ((HomeState) -> HomeState) -> Unit,
    private val clock: Clock = Clock.System,
) {
    var timerSets by mutableStateOf(timerRepo.getAll())
        private set
    var timerActiveId by mutableStateOf(timerRepo.activeId())
        private set
    var quickTimerSeconds by mutableStateOf(prefs.quickTimerSeconds)
        private set
    var quickTimerStartFromApp by mutableStateOf(prefs.quickTimerStartFromApp)
        private set
    var quickTimerIntervalMode by mutableStateOf(prefs.quickTimerIntervalMode)
        private set
    var quickTimerAlarmMode by mutableStateOf(prefs.quickTimerAlarmMode)
        private set
    var quickTimerAlarmHour by mutableStateOf(prefs.quickTimerAlarmHour)
        private set
    var quickTimerAlarmMinute by mutableStateOf(prefs.quickTimerAlarmMinute)
        private set

    fun refreshTimers() {
        timerSets = timerRepo.getAll()
        timerActiveId = timerRepo.activeId()
    }

    fun newTimerSet() {
        val set = TimerSet.blank(randomId(), "Set ${timerSets.size + 1}")
        timerRepo.save(set)
        refreshTimers()
        editingSet = set
    }

    var editingSet by mutableStateOf<TimerSet?>(null)
        private set

    fun editTimerSet(set: TimerSet?) { editingSet = set }

    fun saveTimerSet(set: TimerSet) {
        timerRepo.save(set)
        refreshTimers()
    }

    fun duplicateTimerSet(src: TimerSet) {
        if (timerSets.size < TimerSetsRepository.MAX_SETS) {
            timerRepo.save(src.copy(id = randomId(), name = src.name + " copy"))
            refreshTimers()
        }
    }

    fun deleteTimerSet(set: TimerSet) {
        timerRepo.delete(set.id)
        refreshTimers()
    }

    fun moveTimerSetUp(set: TimerSet) {
        val index = timerSets.indexOfFirst { it.id == set.id }
        if (index < 0) return
        timerRepo.reorder(Reorder.moveUp(timerSets.map { it.id }, index))
        refreshTimers()
    }

    fun moveTimerSetDown(set: TimerSet) {
        val index = timerSets.indexOfFirst { it.id == set.id }
        if (index < 0) return
        timerRepo.reorder(Reorder.moveDown(timerSets.map { it.id }, index))
        refreshTimers()
    }

    /** Shared path for the three 0x26 sends: addr check, in-flight guard, push, snackbar. */
    private fun pushTimerFrame(packet: ByteArray, successMsg: String, onSuccess: () -> Unit = {}) {
        val addr = prefs.watchAddress
        if (addr == null) {
            showSnackbar("No watch selected — open Settings (⚙)")
            return
        }
        if (state().sending) return
        scope.launch {
            update { it.copy(sending = true) }
            val result = ble.sendPacket(addr, packet)
            update { it.copy(sending = false) }
            result
                .onSuccess {
                    onSuccess()
                    // Partial read-back confirmation: the watch's 0x2c reply exposes only the active
                    // countdown value + run flag, not the full slot table (see TimerConfirm).
                    val confirmed = TimerReadback.confirm(ble, addr, packet)
                    showSnackbar(if (confirmed) successMsg else "$successMsg — but the watch didn't confirm it")
                }
                .onFailure { showSnackbar("Send failed — long-press ALARM to wake the watch, then retry") }
        }
    }

    fun saveQuickTimer(seconds: Int) {
        prefs.quickTimerSeconds = seconds
        quickTimerSeconds = prefs.quickTimerSeconds // read back to apply the >=0 coercion
    }

    /** "Start timer from app" toggle — whether a Send to watch also starts the countdown. */
    fun toggleQuickTimerStartFromApp(enabled: Boolean) {
        prefs.quickTimerStartFromApp = enabled
        quickTimerStartFromApp = enabled
    }

    /** "Interval timer mode" toggle — interval (10-slot sequence) vs. single quick-timer countdown. */
    fun toggleQuickTimerIntervalMode(enabled: Boolean) {
        prefs.quickTimerIntervalMode = enabled
        quickTimerIntervalMode = enabled
    }

    fun toggleQuickTimerAlarmMode(enabled: Boolean) {
        prefs.quickTimerAlarmMode = enabled
        quickTimerAlarmMode = enabled
    }

    fun saveQuickTimerAlarmTime(hour: Int, minute: Int) {
        prefs.quickTimerAlarmHour = hour
        prefs.quickTimerAlarmMinute = minute
        quickTimerAlarmHour = prefs.quickTimerAlarmHour // read back to apply coercion
        quickTimerAlarmMinute = prefs.quickTimerAlarmMinute
    }

    /**
     * Alarm-mode quick timer: compute the countdown to the next occurrence of the saved
     * wall-clock target and push it as a single countdown the watch starts immediately.
     * Capped at 23:59:59 so the header hours byte stays <= 23 (the watch UI's own max).
     */
    fun sendQuickAlarm() {
        val now = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
        val seconds = QuickAlarm.countdownSeconds(now, quickTimerAlarmHour, quickTimerAlarmMinute)
            .coerceAtMost(86_399)
        val slots = timerActiveId?.let { timerRepo.get(it) }?.durations() ?: List(10) { 0 }
        val packet = OlleeProtocol.buildTimerPacket(
            slots,
            headerSeconds = seconds,
            startMode = OlleeProtocol.TimerStartMode.START_SINGLE
        )
        pushTimerFrame(packet, "Started alarm timer on watch")
    }

    fun sendTimerSet(set: TimerSet) {
        val packet = OlleeProtocol.buildTimerPacket(
            set.durations(),
            headerSeconds = quickTimerSeconds,
            startMode = OlleeProtocol.TimerStartMode.SAVE
        )
        pushTimerFrame(packet, "Sent '${set.name}' to watch") {
            timerRepo.setActive(set.id)
            timerActiveId = set.id
        }
    }

    fun startTimerSet(set: TimerSet) {
        val packet = OlleeProtocol.buildTimerPacket(
            set.durations(),
            headerSeconds = quickTimerSeconds,
            startMode = OlleeProtocol.TimerStartMode.START_INTERVAL
        )
        pushTimerFrame(packet, "Started '${set.name}' on watch") {
            timerRepo.setActive(set.id)
            timerActiveId = set.id
        }
    }

    /**
     * "Send to watch" for the Quick timer. The two independent toggles ([quickTimerStartFromApp],
     * [quickTimerIntervalMode]) — not which button was pressed — select the start/mode, decoupled
     * exactly as the official app does. Slots are the active set's (preserved on the watch), or
     * zeros if no set is active.
     */
    fun sendQuickTimer() {
        val mode = OlleeProtocol.TimerStartMode.of(quickTimerStartFromApp, quickTimerIntervalMode)
        val slots = timerActiveId?.let { timerRepo.get(it) }?.durations() ?: List(10) { 0 }
        val packet = OlleeProtocol.buildTimerPacket(
            slots,
            headerSeconds = quickTimerSeconds,
            startMode = mode
        )
        val msg = when (mode) {
            OlleeProtocol.TimerStartMode.SAVE -> "Sent quick timer to watch"
            OlleeProtocol.TimerStartMode.START_INTERVAL -> "Started intervals on watch"
            OlleeProtocol.TimerStartMode.START_SINGLE -> "Started quick timer on watch"
        }
        pushTimerFrame(packet, msg)
    }
}
