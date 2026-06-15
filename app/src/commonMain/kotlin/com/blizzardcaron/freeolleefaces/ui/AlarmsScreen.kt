package com.blizzardcaron.freeolleefaces.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
        AppBar(title = "Alarms")

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
                // Key by id so deleting a card doesn't hand its remembered state (open chime
                // dropdown, hour text buffer) to the card that slides into its position.
                key(alarm.id) {
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
                    val isPm = alarm.hour >= 12
                    val hour12 = if (alarm.hour % 12 == 0) 12 else alarm.hour % 12
                    HourField(hour12) { onSave(alarm.copy(hour = hour24(it, isPm))) }
                    NumberField("M", alarm.minute) { onSave(alarm.copy(minute = it.coerceIn(0, 59))) }
                    TextButton(onClick = { onSave(alarm.copy(hour = hour24(hour12, !isPm))) }) {
                        Text(if (isPm) "PM" else "AM")
                    }
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

/** Sunday-first week, matching the official Ollee app's alarm screen. */
private val WEEK_SUNDAY_FIRST = listOf(DayOfWeek.SUNDAY) + DayOfWeek.entries.filter { it != DayOfWeek.SUNDAY }

/** One toggle chip per weekday, Sunday-first: S M T W T F S. */
@Composable
private fun DayChips(mask: Int, onChange: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (day in WEEK_SUNDAY_FIRST) {
            val bit = Alarm.bit(day)
            val selected = mask and bit != 0
            FilterChip(
                selected = selected,
                onClick = { onChange(if (selected) mask and bit.inv() else mask or bit) },
                label = { Text(day.name.take(1)) },
                // Default M3 selected chips (secondaryContainer) read nearly the same as
                // unselected on this theme — use primary so active days are unmistakable.
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
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
            AlarmSchedule.CHIME_NAMES.forEachIndexed { i, name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { onChange(i); open = false },
                )
            }
        }
    }
}

/**
 * Hour entry for the 12-hour clock. Unlike [NumberField] it keeps a local edit buffer so the
 * field can pass through empty/out-of-range text while typing (e.g. clearing "12" to type "8"),
 * committing only 1..12. NumberField's 0-renders-empty convention can't express that here:
 * hour 0 doesn't exist on a 12-hour clock, and coercing the transient 0 back to a digit made
 * hours 2-9 unreachable by normal typing.
 */
@Composable
private fun HourField(value: Int, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val t = raw.filter(Char::isDigit).take(2)
            text = t
            t.toIntOrNull()?.takeIf { it in 1..12 }?.let(onCommit)
        },
        label = { Text("H") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(80.dp),
    )
}

/** 12-hour clock + AM/PM back to the 0..23 hour the [Alarm] model stores (12 AM = 0, 12 PM = 12). */
private fun hour24(hour12: Int, pm: Boolean) = (hour12 % 12) + if (pm) 12 else 0
