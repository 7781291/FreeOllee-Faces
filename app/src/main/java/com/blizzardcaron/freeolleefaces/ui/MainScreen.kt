package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.format.TempUnit
import androidx.compose.ui.platform.LocalContext
import com.blizzardcaron.freeolleefaces.auto.AutoSource

data class MainScreenState(
    val lat: String = "",
    val lng: String = "",
    val custom: String = "",
    val watchLabel: String = "Watch: none selected",
    val status: String = "Ready.",
    val sending: Boolean = false,
    val watchSelected: Boolean = false,
    val tempUnit: TempUnit = TempUnit.FAHRENHEIT,
    val tempPreview: PreviewState = PreviewState.WaitingForCoords,
    val sunPreview: PreviewState = PreviewState.WaitingForCoords,
    val autoSource: AutoSource = AutoSource.OFF,
    val tempIntervalText: String = "60",
    val sleepEnabled: Boolean = true,
    val sleepStartMin: Int = 22 * 60,
    val sleepEndMin: Int = 6 * 60,
    val lastAutoSummary: String = "No auto-updates yet",
)

data class MainScreenCallbacks(
    val onLatChange: (String) -> Unit,
    val onLngChange: (String) -> Unit,
    val onCustomChange: (String) -> Unit,
    val onSelectWatch: () -> Unit,
    val onUseMyLocation: () -> Unit,
    val onRefresh: () -> Unit,
    val onTempUnitChange: (TempUnit) -> Unit,
    val onSendTemperature: () -> Unit,
    val onSendSunTime: () -> Unit,
    val onSendCustom: () -> Unit,
    val onRetryTemperature: () -> Unit,
    val onAutoSourceChange: (AutoSource) -> Unit,
    val onTempIntervalChange: (String) -> Unit,
    val onSleepEnabledChange: (Boolean) -> Unit,
    val onSleepStartChange: (Int) -> Unit,
    val onSleepEndChange: (Int) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainScreenState,
    callbacks: MainScreenCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Pinned header — stays visible regardless of scroll position.
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("FreeOllee Faces", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = callbacks.onRefresh) { Text("Refresh") }
        }

        Text(state.status, style = MaterialTheme.typography.bodyMedium)

        HorizontalDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.tempUnit == TempUnit.FAHRENHEIT,
                    onClick = { callbacks.onTempUnitChange(TempUnit.FAHRENHEIT) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("°F") }
                SegmentedButton(
                    selected = state.tempUnit == TempUnit.CELSIUS,
                    onClick = { callbacks.onTempUnitChange(TempUnit.CELSIUS) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("°C") }
            }

            Text(state.watchLabel, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = callbacks.onSelectWatch, modifier = Modifier.fillMaxWidth()) {
                Text("Select watch")
            }

            OutlinedTextField(
                value = state.lat,
                onValueChange = callbacks.onLatChange,
                label = { Text("Latitude") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.lng,
                onValueChange = callbacks.onLngChange,
                label = { Text("Longitude") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = callbacks.onUseMyLocation, modifier = Modifier.fillMaxWidth()) {
                Text("Use my location")
            }

            HorizontalDivider()

            PreviewCard(
                title = "Temperature",
                state = state.tempPreview,
                onRetry = callbacks.onRetryTemperature,
                onSend = callbacks.onSendTemperature,
                sendEnabled = state.tempPreview is PreviewState.Ready && state.watchSelected && !state.sending,
            )

            PreviewCard(
                title = "Next sun event",
                state = state.sunPreview,
                onRetry = null, // sun is local; no retry path
                onSend = callbacks.onSendSunTime,
                sendEnabled = state.sunPreview is PreviewState.Ready && state.watchSelected && !state.sending,
            )

            AutoUpdateCard(state = state, callbacks = callbacks)

            HorizontalDivider()

            OutlinedTextField(
                value = state.custom,
                onValueChange = callbacks.onCustomChange,
                label = { Text("Custom (up to 6 chars)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = callbacks.onSendCustom,
                enabled = state.watchSelected && !state.sending,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send custom") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoUpdateCard(
    state: MainScreenState,
    callbacks: MainScreenCallbacks,
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Auto-update", style = MaterialTheme.typography.titleMedium)

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.autoSource == AutoSource.OFF,
                    onClick = { callbacks.onAutoSourceChange(AutoSource.OFF) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                ) { Text("Off") }
                SegmentedButton(
                    selected = state.autoSource == AutoSource.TEMPERATURE,
                    onClick = { callbacks.onAutoSourceChange(AutoSource.TEMPERATURE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                ) { Text("Temp") }
                SegmentedButton(
                    selected = state.autoSource == AutoSource.SUN,
                    onClick = { callbacks.onAutoSourceChange(AutoSource.SUN) },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                ) { Text("Sun") }
            }

            when (state.autoSource) {
                AutoSource.TEMPERATURE -> {
                    OutlinedTextField(
                        value = state.tempIntervalText,
                        onValueChange = callbacks.onTempIntervalChange,
                        label = { Text("Interval (minutes, min 15)") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Power-saving sleep")
                        Switch(
                            checked = state.sleepEnabled,
                            onCheckedChange = callbacks.onSleepEnabledChange,
                        )
                    }
                    if (state.sleepEnabled) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    showTimePicker(context, state.sleepStartMin, callbacks.onSleepStartChange)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("From ${minutesToLabel(state.sleepStartMin)}") }
                            OutlinedButton(
                                onClick = {
                                    showTimePicker(context, state.sleepEndMin, callbacks.onSleepEndChange)
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("To ${minutesToLabel(state.sleepEndMin)}") }
                        }
                    }
                }
                AutoSource.SUN -> Text(
                    "Re-sends automatically after each sunrise/sunset; no schedule needed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                AutoSource.OFF -> Text(
                    "Auto-update is off.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Text(
                "Last auto-update: ${state.lastAutoSummary}",
                style = MaterialTheme.typography.bodySmall,
            )
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

@Composable
private fun PreviewCard(
    title: String,
    state: PreviewState,
    onRetry: (() -> Unit)?,
    onSend: () -> Unit,
    sendEnabled: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            when (state) {
                is PreviewState.WaitingForCoords -> Text(
                    "Waiting for coordinates…",
                    style = MaterialTheme.typography.bodyMedium,
                )
                is PreviewState.Loading -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                is PreviewState.Ready -> {
                    Text(
                        "Watch: '${state.payload}'",
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    )
                    Text(state.human, style = MaterialTheme.typography.bodyMedium)
                }
                is PreviewState.Error -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
                PreviewState.NoEvent -> Text(
                    "No sunrise/sunset in next 24 h.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = onSend,
                enabled = sendEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Send to watch") }
        }
    }
}
