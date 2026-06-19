package com.blizzardcaron.freeolleefaces.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.timer.QuickAlarm
import com.blizzardcaron.freeolleefaces.timer.TimerSet
import com.blizzardcaron.freeolleefaces.timer.TimerSetEditing
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_MINUTE = 60
private const val MAX_MINUTE = 59
private const val MAX_TIMER_SECONDS = 86_399

@Composable
fun TimerSetsScreen(
    sets: List<TimerSet>,
    activeId: String?,
    sending: Boolean,
    quickTimer: QuickTimerState,
    callbacks: TimerSetsCallbacks,
    connectionStatus: ConnectionStatus,
    modifier: Modifier = Modifier,
) {
    BackHandler { callbacks.onBack() }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppBar(
            title = "Timer",
            connectionStatus = connectionStatus,
            onReconnect = callbacks.onReconnect,
        )

        QuickTimerCard(quickTimer = quickTimer, sending = sending, callbacks = callbacks)

        val atMax = sets.size >= TimerSetsRepository.MAX_SETS
        Button(onClick = callbacks.onNew, enabled = !atMax, modifier = Modifier.fillMaxWidth()) {
            Text(if (atMax) "Max ${TimerSetsRepository.MAX_SETS} sets" else "New set")
        }

        TimerSetList(sets = sets, activeId = activeId, sending = sending, callbacks = callbacks)
    }
}

@Composable
private fun QuickTimerCard(
    quickTimer: QuickTimerState,
    sending: Boolean,
    callbacks: TimerSetsCallbacks,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Quick timer", style = MaterialTheme.typography.titleMedium)
            ToggleRow("Alarm mode", quickTimer.alarmMode, callbacks.onToggleAlarmMode)

            if (quickTimer.alarmMode) {
                AlarmModeSection(quickTimer = quickTimer, sending = sending, callbacks = callbacks)
            } else {
                TimerModeSection(quickTimer = quickTimer, sending = sending, callbacks = callbacks)
            }
        }
    }
}

@Composable
private fun AlarmModeSection(
    quickTimer: QuickTimerState,
    sending: Boolean,
    callbacks: TimerSetsCallbacks,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val pm = isPm(quickTimer.alarmHour)
        HourField(hour12Of(quickTimer.alarmHour)) {
            callbacks.onSaveAlarmTime(hour24(it, pm), quickTimer.alarmMinute)
        }
        NumberField("M", quickTimer.alarmMinute) {
            callbacks.onSaveAlarmTime(quickTimer.alarmHour, it.coerceIn(0, MAX_MINUTE))
        }
        TextButton(onClick = {
            callbacks.onSaveAlarmTime(hour24(hour12Of(quickTimer.alarmHour), !pm), quickTimer.alarmMinute)
        }) { Text(if (pm) "PM" else "AM") }
    }
    Text(
        alarmPreview(quickTimer.alarmHour, quickTimer.alarmMinute),
        style = MaterialTheme.typography.bodySmall,
    )
    Button(onClick = callbacks.onSendAlarm, enabled = !sending, modifier = Modifier.fillMaxWidth()) {
        Text("▶ Send & start quick timer")
    }
}

@Composable
private fun TimerModeSection(
    quickTimer: QuickTimerState,
    sending: Boolean,
    callbacks: TimerSetsCallbacks,
) {
    val (h, m, s) = TimerSetEditing.secondsToHms(quickTimer.seconds)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NumberField("H", h) { callbacks.onSaveQuick(TimerSetEditing.hmsToSeconds(it, m, s)) }
        NumberField("M", m) { callbacks.onSaveQuick(TimerSetEditing.hmsToSeconds(h, it, s)) }
        NumberField("S", s) { callbacks.onSaveQuick(TimerSetEditing.hmsToSeconds(h, m, it)) }
    }
    // The official app's three independent controls. "Send to watch" pushes one frame
    // whose start/mode is decided by these toggles, not by which button you tap.
    ToggleRow("Start timer from app", quickTimer.startFromApp, callbacks.onToggleStartFromApp)
    ToggleRow(
        "Interval mode",
        quickTimer.intervalMode,
        callbacks.onToggleIntervalMode,
        enabled = quickTimer.startFromApp,
    )
    val sendLabel = when {
        !quickTimer.startFromApp -> "Send to watch"
        quickTimer.intervalMode -> "▶ Send & start intervals"
        else -> "▶ Send & start quick timer"
    }
    Button(onClick = callbacks.onSendQuick, enabled = !sending, modifier = Modifier.fillMaxWidth()) {
        Text(sendLabel)
    }
}

@Composable
private fun ColumnScope.TimerSetList(
    sets: List<TimerSet>,
    activeId: String?,
    sending: Boolean,
    callbacks: TimerSetsCallbacks,
) {
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (sets.isEmpty()) {
            Text(
                "No sets yet. Tap \"New set\" to create one.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        sets.forEachIndexed { index, set ->
            TimerSetRow(
                set = set,
                active = set.id == activeId,
                sending = sending,
                canMoveUp = index > 0,
                canMoveDown = index < sets.lastIndex,
                callbacks = TimerSetRowCallbacks(
                    onOpen = { callbacks.onOpen(set) },
                    onDuplicate = { callbacks.onDuplicate(set) },
                    onDelete = { callbacks.onDelete(set) },
                    onSend = { callbacks.onSend(set) },
                    onStart = { callbacks.onStart(set) },
                    onMoveUp = { callbacks.onMoveUp(set) },
                    onMoveDown = { callbacks.onMoveDown(set) },
                ),
            )
        }
    }
}

/** A label + Switch row, used for the Quick timer's "Start from app" / "Interval mode" toggles. */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun TimerSetRow(
    set: TimerSet,
    active: Boolean,
    sending: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    callbacks: TimerSetRowCallbacks,
) {
    val cardColors = if (active) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    val border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Card(modifier = Modifier.fillMaxWidth(), colors = cardColors, border = border) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable { callbacks.onOpen() }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    // Radio = send: timerActiveId only updates on a successful send, so the radio
                    // moves when the push lands and stays put on failure (snackbar covers errors).
                    RadioButton(selected = active, onClick = callbacks.onSend, enabled = !sending)
                    Text(
                        if (set.name.isBlank()) "(unnamed)" else set.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = callbacks.onMoveUp, enabled = canMoveUp) { Text("▲") }
                    TextButton(onClick = callbacks.onMoveDown, enabled = canMoveDown) { Text("▼") }
                }
            }
            val count = set.slots.count { it.durationSeconds > 0 }
            val first = set.slots.firstOrNull { it.durationSeconds > 0 }?.durationSeconds
            val summary = if (first != null) {
                "$count of 10 set · first ${TimerSetEditing.formatHms(first)}"
            } else {
                "all blank"
            }
            Text(summary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = callbacks.onStart, enabled = !sending) { Text("▶ Start intervals") }
                TextButton(onClick = callbacks.onDuplicate) { Text("Duplicate") }
                TextButton(onClick = callbacks.onDelete) { Text("Delete") }
            }
        }
    }
}

/** "Fires 7:00 AM · in 9h 0m" — resolved from the current time; recomputed on recomposition. */
private fun alarmPreview(targetHour: Int, targetMinute: Int): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
    val raw = QuickAlarm.countdownSeconds(now, targetHour, targetMinute)
    val capped = raw > MAX_TIMER_SECONDS
    val delta = raw.coerceAtMost(MAX_TIMER_SECONDS)
    val ampm = if (isPm(targetHour)) "PM" else "AM"
    val fires = "${hour12Of(targetHour)}:${targetMinute.toString().padStart(2, '0')} $ampm"
    val span = "${delta / SECONDS_PER_HOUR}h ${(delta % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE}m"
    return "Fires $fires · in $span" + if (capped) " (capped)" else ""
}
