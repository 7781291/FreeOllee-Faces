package com.blizzardcaron.freeolleefaces.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.alarm.Alarm
import com.blizzardcaron.freeolleefaces.alarm.AlarmSchedule
import com.blizzardcaron.freeolleefaces.alarm.AlarmsRepository
import kotlinx.datetime.DayOfWeek

@Composable
fun AlarmsScreen(
    alarms: List<Alarm>,
    nextSummary: String,
    onAdd: () -> Unit,
    onSave: (Alarm) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Alarms", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Done") }
        }
        HorizontalDivider()

        Text(nextSummary, style = MaterialTheme.typography.titleMedium)

        val atMax = alarms.size >= AlarmsRepository.MAX_ALARMS
        Button(onClick = onAdd, enabled = !atMax, modifier = Modifier.fillMaxWidth()) {
            Text(if (atMax) "Max ${AlarmsRepository.MAX_ALARMS} alarms" else "Add alarm")
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (alarms.isEmpty()) {
                Text("No alarms yet. Tap \"Add alarm\" to create one.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            for (alarm in alarms) {
                AlarmCard(
                    alarm = alarm,
                    onSave = onSave,
                    onToggle = { onToggle(alarm.id, it) },
                    onDelete = { onDelete(alarm.id) },
                )
            }
        }
    }
}

@Composable
private fun AlarmCard(
    alarm: Alarm,
    onSave: (Alarm) -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NumberField("H", alarm.hour) { onSave(alarm.copy(hour = it.coerceIn(0, 23))) }
                    NumberField("M", alarm.minute) { onSave(alarm.copy(minute = it.coerceIn(0, 59))) }
                }
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            }

            DayChips(mask = alarm.daysMask) { onSave(alarm.copy(daysMask = it)) }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ChimePicker(index = alarm.chimeIndex) { onSave(alarm.copy(chimeIndex = it)) }
                TextButton(onClick = onDelete) { Text("Delete") }
            }

            OutlinedTextField(
                value = alarm.label,
                onValueChange = { onSave(alarm.copy(label = it.take(24))) },
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One toggle chip per weekday, Mon-first: M T W T F S S. */
@Composable
private fun DayChips(mask: Int, onChange: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (day in DayOfWeek.entries) {
            val bit = Alarm.bit(day)
            val selected = mask and bit != 0
            FilterChip(
                selected = selected,
                onClick = { onChange(if (selected) mask and bit.inv() else mask or bit) },
                label = { Text(day.name.take(1)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ChimePicker(index: Int, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("♪ ${AlarmSchedule.chimeName(index)}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            repeat(14) { i ->   // the watch's 14 tones, indices 0x00..0x0D
                DropdownMenuItem(
                    text = { Text(AlarmSchedule.chimeName(i)) },
                    onClick = { onChange(i); open = false },
                )
            }
        }
    }
}
