package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.auto.ActiveFace
import com.blizzardcaron.freeolleefaces.format.TempUnit

data class HomeState(
    val activeFace: ActiveFace = ActiveFace.TEMPERATURE,
    val watchLabel: String = "Watch: none selected",
    val watchSelected: Boolean = false,
    val status: String = "Ready.",
    val sending: Boolean = false,

    val tempUnit: TempUnit = TempUnit.FAHRENHEIT,
    val tempPreview: PreviewState = PreviewState.WaitingForCoords,
    val tempUpdated: String? = null,
    val tempNext: String? = null,
    val tempIntervalText: String = "60",
    val sleepEnabled: Boolean = true,
    val sleepStartMin: Int = 22 * 60,
    val sleepEndMin: Int = 6 * 60,

    val sunPreview: PreviewState = PreviewState.WaitingForCoords,
    val sunUpdated: String? = null,
    val sunNext: String? = null,

    val custom: String = "",
    val customSent: String? = null,

    val showLocationFallback: Boolean = false,
    val lat: String = "",
    val lng: String = "",
)

data class HomeCallbacks(
    val onOpenFaces: () -> Unit,
    val onSelectWatch: () -> Unit,
    val onUpdateNow: () -> Unit,
    val onTempUnitChange: (TempUnit) -> Unit,
    val onTempIntervalChange: (String) -> Unit,
    val onSleepEnabledChange: (Boolean) -> Unit,
    val onSleepStartChange: (Int) -> Unit,
    val onSleepEndChange: (Int) -> Unit,
    val onCustomChange: (String) -> Unit,
    val onSendCustom: () -> Unit,
    val onLatChange: (String) -> Unit,
    val onLngChange: (String) -> Unit,
    val onUseMyLocation: () -> Unit,
)

@Composable
fun HomeScreen(
    state: HomeState,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(faceTitle(state.activeFace), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = callbacks.onOpenFaces) { Text("Faces") }
        }

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

        HorizontalDivider()

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.activeFace) {
                ActiveFace.TEMPERATURE -> TemperatureBody(state, callbacks)
                ActiveFace.SUN -> SunBody(state, callbacks)
                ActiveFace.CUSTOM -> CustomBody(state, callbacks)
            }
        }

        Text(state.status, style = MaterialTheme.typography.bodySmall)
    }
}

private fun faceTitle(face: ActiveFace): String = when (face) {
    ActiveFace.TEMPERATURE -> "Temperature"
    ActiveFace.SUN -> "Sun event"
    ActiveFace.CUSTOM -> "Custom"
}

@Composable
private fun FaceValue(preview: PreviewState, updated: String?, next: String?) {
    when (preview) {
        is PreviewState.WaitingForCoords -> Text("Waiting for coordinates…", style = MaterialTheme.typography.bodyMedium)
        is PreviewState.Loading -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
        is PreviewState.Ready -> {
            Text(preview.human, style = MaterialTheme.typography.headlineMedium)
            Text(
                "Watch: '${preview.payload}'",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        is PreviewState.Error -> Text(preview.message, style = MaterialTheme.typography.bodyMedium)
        PreviewState.NoEvent -> Text("No sunrise/sunset in next 24 h.", style = MaterialTheme.typography.bodyMedium)
    }
    if (updated != null) Text(updated, style = MaterialTheme.typography.bodySmall)
    if (next != null) Text(next, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun LocationFallback(state: HomeState, callbacks: HomeCallbacks) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Location unavailable", style = MaterialTheme.typography.titleSmall)
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemperatureBody(state: HomeState, callbacks: HomeCallbacks) {
    if (state.showLocationFallback) LocationFallback(state, callbacks)
    FaceValue(state.tempPreview, state.tempUpdated, state.tempNext)
    Button(onClick = callbacks.onUpdateNow, modifier = Modifier.fillMaxWidth()) { Text("Update now") }
    HorizontalDivider()
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
    OutlinedTextField(
        value = state.tempIntervalText,
        onValueChange = callbacks.onTempIntervalChange,
        label = { Text("Every (minutes, min 15)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
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

@Composable
private fun SunBody(state: HomeState, callbacks: HomeCallbacks) {
    if (state.showLocationFallback) LocationFallback(state, callbacks)
    FaceValue(state.sunPreview, state.sunUpdated, state.sunNext)
    Button(onClick = callbacks.onUpdateNow, modifier = Modifier.fillMaxWidth()) { Text("Update now") }
}

@Composable
private fun CustomBody(state: HomeState, callbacks: HomeCallbacks) {
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
    ) { Text("Send to watch") }
    if (state.customSent != null) {
        Text(state.customSent, style = MaterialTheme.typography.bodySmall)
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
