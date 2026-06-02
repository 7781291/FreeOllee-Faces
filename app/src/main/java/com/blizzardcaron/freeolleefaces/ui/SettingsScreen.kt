package com.blizzardcaron.freeolleefaces.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.prefs.IntervalOptions

data class SettingsCallbacks(
    val onBack: () -> Unit,
    val onSelectWatch: () -> Unit,
    val onIntervalChange: (Int) -> Unit,
    val onSleepEnabledChange: (Boolean) -> Unit,
    val onSleepStartChange: (Int) -> Unit,
    val onSleepEndChange: (Int) -> Unit,
    val onLatChange: (String) -> Unit,
    val onLngChange: (String) -> Unit,
    val onUseMyLocation: () -> Unit,
)

@Composable
fun SettingsScreen(
    state: HomeState,
    callbacks: SettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    BackHandler { callbacks.onBack() }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = callbacks.onBack) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider()

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            WatchSection(state, callbacks)
            HorizontalDivider()
            LocationSection(state, callbacks)
            HorizontalDivider()
            IntervalSection(state, callbacks)
            HorizontalDivider()
            SleepSection(state, callbacks)
        }
    }
}

@Composable
private fun WatchSection(state: HomeState, callbacks: SettingsCallbacks) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(state.watchLabel, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = callbacks.onSelectWatch) {
            Text(if (state.watchSelected) "change" else "select")
        }
    }
}

@Composable
private fun LocationSection(state: HomeState, callbacks: SettingsCallbacks) {
    Text("Location", style = MaterialTheme.typography.titleSmall)
    Text(
        if (state.locating) "Locating…"
        else state.locationLabel + (state.locationFreshness?.let { " · $it" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = state.lat,
        onValueChange = callbacks.onLatChange,
        label = { Text("Latitude") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.lng,
        onValueChange = callbacks.onLngChange,
        label = { Text("Longitude") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = callbacks.onUseMyLocation, modifier = Modifier.fillMaxWidth()) {
        Text("Use my location")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalSection(state: HomeState, callbacks: SettingsCallbacks) {
    Text("Update interval", style = MaterialTheme.typography.titleSmall)
    Text(
        "How often Temperature and Steps push to the watch.",
        style = MaterialTheme.typography.bodySmall,
    )
    val options = IntervalOptions.ALLOWED
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, minutes ->
            SegmentedButton(
                selected = state.updateIntervalMinutes == minutes,
                onClick = { callbacks.onIntervalChange(minutes) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text("$minutes") }
        }
    }
}

@Composable
private fun SleepSection(state: HomeState, callbacks: SettingsCallbacks) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Power-saving sleep")
        Switch(checked = state.sleepEnabled, onCheckedChange = callbacks.onSleepEnabledChange)
    }
    if (state.sleepEnabled) {
        val context = LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { showTimePicker(context, state.sleepStartMin, callbacks.onSleepStartChange) },
                modifier = Modifier.weight(1f),
            ) { Text("From ${minutesToLabel(state.sleepStartMin)}") }
            OutlinedButton(
                onClick = { showTimePicker(context, state.sleepEndMin, callbacks.onSleepEndChange) },
                modifier = Modifier.weight(1f),
            ) { Text("To ${minutesToLabel(state.sleepEndMin)}") }
        }
    }
}

private fun minutesToLabel(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

private fun showTimePicker(
    context: android.content.Context,
    currentMin: Int,
    onPicked: (Int) -> Unit,
) {
    android.app.TimePickerDialog(
        context,
        { _, hour, minute -> onPicked(hour * 60 + minute) },
        currentMin / 60,
        currentMin % 60,
        true,
    ).show()
}
