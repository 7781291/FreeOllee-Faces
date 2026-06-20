package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.ble.OlleeProtocol

@Composable
internal fun AutoSleepSection(state: HomeState, callbacks: SettingsCallbacks) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Scheduled auto-sleep")
        Switch(
            checked = state.autoSleepScheduleEnabled,
            onCheckedChange = callbacks.onAutoSleepScheduleEnabledChange,
        )
    }
    if (state.autoSleepScheduleEnabled) {
        AutoSleepWindowConfig(state, callbacks)
    }
}

@Composable
private fun AutoSleepWindowConfig(state: HomeState, callbacks: SettingsCallbacks) {
    val periods = OlleeProtocol.CONFIG_PERIOD_VALUES_SEC // 5/10/30/60/120 (official app's picker)
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }

    val crosses = state.autoSleepWindowStartMin > state.autoSleepWindowEndMin
    Text(
        "Sleeps ${minutesToLabel(state.autoSleepWindowStartMin)} → " +
            "${minutesToLabel(state.autoSleepWindowEndMin)}${if (crosses) " (next day)" else ""}",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { pickingStart = true }, modifier = Modifier.weight(1f)) {
            Text("From ${minutesToLabel(state.autoSleepWindowStartMin)}")
        }
        OutlinedButton(onClick = { pickingEnd = true }, modifier = Modifier.weight(1f)) {
            Text("To ${minutesToLabel(state.autoSleepWindowEndMin)}")
        }
    }

    AutoSleepProfileRow(
        label = "In window: screen sleeps",
        enabled = state.autoSleepInWindowOn,
        onEnabledChange = callbacks.onAutoSleepInWindowOnChange,
        periods = periods,
        periodSec = state.autoSleepInWindowPeriodSec,
        onPeriodChange = callbacks.onAutoSleepInWindowPeriodChange,
    )
    AutoSleepProfileRow(
        label = if (state.autoSleepOutWindowOn) "Outside window: screen sleeps" else "Outside window: screen stays on",
        enabled = state.autoSleepOutWindowOn,
        onEnabledChange = callbacks.onAutoSleepOutWindowOnChange,
        periods = periods,
        periodSec = state.autoSleepOutWindowPeriodSec,
        onPeriodChange = callbacks.onAutoSleepOutWindowPeriodChange,
    )

    // Bluetooth-always-on advisory (static — the BLE_CONTINUOUS bit position wasn't isolated
    // in the spike, so we can't reliably read its live state; show the guidance unconditionally).
    Text(
        "Tip: enable \"Bluetooth always on\" on the watch so the schedule applies on time.",
        style = MaterialTheme.typography.bodySmall,
    )

    if (pickingStart) {
        TimePickerDialog(
            initialMinuteOfDay = state.autoSleepWindowStartMin,
            onConfirm = {
                callbacks.onAutoSleepWindowStartChange(it)
                pickingStart = false
            },
            onDismiss = { pickingStart = false },
        )
    }
    if (pickingEnd) {
        TimePickerDialog(
            initialMinuteOfDay = state.autoSleepWindowEndMin,
            onConfirm = {
                callbacks.onAutoSleepWindowEndChange(it)
                pickingEnd = false
            },
            onDismiss = { pickingEnd = false },
        )
    }
}

/** A labelled "screen sleeps" toggle plus, when on, its period picker — one per in/out-of-window profile. */
@Composable
private fun AutoSleepProfileRow(
    label: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    periods: List<Int>,
    periodSec: Int,
    onPeriodChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label)
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
    if (enabled) {
        PeriodRow(periods, periodSec, onPeriodChange)
    }
}

@Composable
private fun PeriodRow(periods: List<Int>, selected: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        periods.forEach { p ->
            FilterChip(selected = p == selected, onClick = { onChange(p) }, label = { Text("${p}s") })
        }
    }
}
