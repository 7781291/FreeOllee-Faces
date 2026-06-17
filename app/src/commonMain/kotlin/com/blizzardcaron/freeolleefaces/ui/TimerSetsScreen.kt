package com.blizzardcaron.freeolleefaces.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.blizzardcaron.freeolleefaces.timer.QuickAlarm
import com.blizzardcaron.freeolleefaces.timer.TimerSet
import com.blizzardcaron.freeolleefaces.timer.TimerSetEditing
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun TimerSetsScreen(
    sets: List<TimerSet>,
    activeId: String?,
    sending: Boolean,
    quickTimerSeconds: Int,
    quickTimerStartFromApp: Boolean,
    quickTimerIntervalMode: Boolean,
    onSaveQuick: (Int) -> Unit,
    onToggleStartFromApp: (Boolean) -> Unit,
    onToggleIntervalMode: (Boolean) -> Unit,
    quickTimerAlarmMode: Boolean,
    quickTimerAlarmHour: Int,
    quickTimerAlarmMinute: Int,
    onToggleAlarmMode: (Boolean) -> Unit,
    onSaveAlarmTime: (Int, Int) -> Unit,
    onSendAlarm: () -> Unit,
    onSendQuick: () -> Unit,
    onOpen: (TimerSet) -> Unit,
    onNew: () -> Unit,
    onDuplicate: (TimerSet) -> Unit,
    onDelete: (TimerSet) -> Unit,
    onSend: (TimerSet) -> Unit,
    onStart: (TimerSet) -> Unit,
    onMoveUp: (TimerSet) -> Unit,
    onMoveDown: (TimerSet) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppBar(title = "Timer")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Quick timer", style = MaterialTheme.typography.titleMedium)
                ToggleRow("Alarm mode", quickTimerAlarmMode, onToggleAlarmMode)

                if (quickTimerAlarmMode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val pm = isPm(quickTimerAlarmHour)
                        HourField(hour12Of(quickTimerAlarmHour)) {
                            onSaveAlarmTime(hour24(it, pm), quickTimerAlarmMinute)
                        }
                        NumberField("M", quickTimerAlarmMinute) {
                            onSaveAlarmTime(quickTimerAlarmHour, it.coerceIn(0, 59))
                        }
                        TextButton(onClick = {
                            onSaveAlarmTime(hour24(hour12Of(quickTimerAlarmHour), !pm), quickTimerAlarmMinute)
                        }) { Text(if (pm) "PM" else "AM") }
                    }
                    Text(
                        alarmPreview(quickTimerAlarmHour, quickTimerAlarmMinute),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onSendAlarm, enabled = !sending, modifier = Modifier.fillMaxWidth()) {
                        Text("▶ Send alarm")
                    }
                } else {
                    val (h, m, s) = TimerSetEditing.secondsToHms(quickTimerSeconds)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        NumberField("H", h) { onSaveQuick(TimerSetEditing.hmsToSeconds(it, m, s)) }
                        NumberField("M", m) { onSaveQuick(TimerSetEditing.hmsToSeconds(h, it, s)) }
                        NumberField("S", s) { onSaveQuick(TimerSetEditing.hmsToSeconds(h, m, it)) }
                    }
                    // The official app's three independent controls. "Send to watch" pushes one frame
                    // whose start/mode is decided by these toggles, not by which button you tap.
                    ToggleRow("Start timer from app", quickTimerStartFromApp, onToggleStartFromApp)
                    ToggleRow(
                        "Interval mode",
                        quickTimerIntervalMode,
                        onToggleIntervalMode,
                        enabled = quickTimerStartFromApp,
                    )
                    val sendLabel = when {
                        !quickTimerStartFromApp -> "Send to watch"
                        quickTimerIntervalMode -> "▶ Send & start intervals"
                        else -> "▶ Send & start quick timer"
                    }
                    Button(onClick = onSendQuick, enabled = !sending, modifier = Modifier.fillMaxWidth()) {
                        Text(sendLabel)
                    }
                }
            }
        }

        val atMax = sets.size >= TimerSetsRepository.MAX_SETS
        Button(onClick = onNew, enabled = !atMax, modifier = Modifier.fillMaxWidth()) {
            Text(if (atMax) "Max ${TimerSetsRepository.MAX_SETS} sets" else "New set")
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (sets.isEmpty()) {
                Text("No sets yet. Tap \"New set\" to create one.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            sets.forEachIndexed { index, set ->
                TimerSetRow(
                    set = set,
                    active = set.id == activeId,
                    sending = sending,
                    canMoveUp = index > 0,
                    canMoveDown = index < sets.lastIndex,
                    onOpen = { onOpen(set) },
                    onDuplicate = { onDuplicate(set) },
                    onDelete = { onDelete(set) },
                    onSend = { onSend(set) },
                    onStart = { onStart(set) },
                    onMoveUp = { onMoveUp(set) },
                    onMoveDown = { onMoveDown(set) },
                )
            }
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
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSend: () -> Unit,
    onStart: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val cardColors = if (active) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    val border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Card(modifier = Modifier.fillMaxWidth(), colors = cardColors, border = border) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable { onOpen() }.padding(12.dp),
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
                    RadioButton(selected = active, onClick = onSend, enabled = !sending)
                    Text(if (set.name.isBlank()) "(unnamed)" else set.name,
                        style = MaterialTheme.typography.titleMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("▲") }
                    TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("▼") }
                }
            }
            val count = set.slots.count { it.durationSeconds > 0 }
            val first = set.slots.firstOrNull { it.durationSeconds > 0 }?.durationSeconds
            val summary = if (first != null) {
                "$count of 10 set · first ${TimerSetEditing.formatHms(first)}"
            } else "all blank"
            Text(summary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onStart, enabled = !sending) { Text("▶ Start intervals") }
                TextButton(onClick = onDuplicate) { Text("Duplicate") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/** "Fires 7:00 AM · in 9h 0m" — resolved from the current time; recomputed on recomposition. */
private fun alarmPreview(targetHour: Int, targetMinute: Int): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time
    val raw = QuickAlarm.countdownSeconds(now, targetHour, targetMinute)
    val capped = raw > 86_399
    val delta = raw.coerceAtMost(86_399)
    val ampm = if (isPm(targetHour)) "PM" else "AM"
    val fires = "${hour12Of(targetHour)}:${targetMinute.toString().padStart(2, '0')} $ampm"
    val span = "${delta / 3600}h ${(delta % 3600) / 60}m"
    return "Fires $fires · in $span" + if (capped) " (capped)" else ""
}
