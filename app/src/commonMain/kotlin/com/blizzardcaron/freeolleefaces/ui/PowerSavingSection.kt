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
internal fun PowerSavingSection(state: HomeState, callbacks: SettingsCallbacks) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Power saving")
        Switch(
            checked = state.powerSavingEnabled,
            onCheckedChange = callbacks.onPowerSavingEnabledChange,
        )
    }
    if (state.powerSavingEnabled) {
        PowerSavingBody(state, callbacks)
    }
}

@Composable
private fun PowerSavingBody(state: HomeState, callbacks: SettingsCallbacks) {
    Text("Screen sleeps after", style = MaterialTheme.typography.bodyMedium)
    PeriodRow(
        periods = OlleeProtocol.CONFIG_PERIOD_VALUES_SEC,
        selected = state.screenSleepTimeoutSec,
        onChange = callbacks.onScreenSleepTimeoutChange,
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Quiet hours")
        Switch(
            checked = state.quietHoursEnabled,
            onCheckedChange = callbacks.onQuietHoursEnabledChange,
        )
    }

    if (state.quietHoursEnabled) {
        QuietHoursConfig(state, callbacks)
    } else {
        Text(
            "Screen sleeps after ${state.screenSleepTimeoutSec}s, all day.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun QuietHoursConfig(state: HomeState, callbacks: SettingsCallbacks) {
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }

    val crosses = state.quietHoursStartMin > state.quietHoursEndMin
    Text(
        "Sleeps ${minutesToLabel(state.quietHoursStartMin)} → " +
            "${minutesToLabel(state.quietHoursEndMin)}${if (crosses) " (next day)" else ""}",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { pickingStart = true }, modifier = Modifier.weight(1f)) {
            Text("From ${minutesToLabel(state.quietHoursStartMin)}")
        }
        OutlinedButton(onClick = { pickingEnd = true }, modifier = Modifier.weight(1f)) {
            Text("To ${minutesToLabel(state.quietHoursEndMin)}")
        }
    }
    Text(
        "In quiet hours the screen sleeps and updates pause. " +
            "Outside, the screen stays on and updates run.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Tip: enable \"Bluetooth always on\" on the watch so the schedule applies on time.",
        style = MaterialTheme.typography.bodySmall,
    )

    if (pickingStart) {
        TimePickerDialog(
            initialMinuteOfDay = state.quietHoursStartMin,
            onConfirm = {
                callbacks.onQuietHoursStartChange(it)
                pickingStart = false
            },
            onDismiss = { pickingStart = false },
        )
    }
    if (pickingEnd) {
        TimePickerDialog(
            initialMinuteOfDay = state.quietHoursEndMin,
            onConfirm = {
                callbacks.onQuietHoursEndChange(it)
                pickingEnd = false
            },
            onDismiss = { pickingEnd = false },
        )
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
