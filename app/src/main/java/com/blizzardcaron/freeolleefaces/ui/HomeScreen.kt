package com.blizzardcaron.freeolleefaces.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.auto.ActiveFace
import com.blizzardcaron.freeolleefaces.format.TempUnit

data class HomeState(
    val activeFace: ActiveFace = ActiveFace.TEMPERATURE,
    val watchLabel: String = "Watch: none selected",
    val watchSelected: Boolean = false,
    val sending: Boolean = false,

    val locationLabel: String = "Location: not set",
    val locationFreshness: String? = null,
    val locating: Boolean = false,

    val tempUnit: TempUnit = TempUnit.FAHRENHEIT,
    val tempPreview: PreviewState = PreviewState.WaitingForCoords,
    val tempUpdated: String? = null,
    val tempNext: String? = null,
    val updateIntervalMinutes: Int = 15,
    val sleepEnabled: Boolean = true,
    val sleepStartMin: Int = 22 * 60,
    val sleepEndMin: Int = 6 * 60,

    val sunPreview: PreviewState = PreviewState.WaitingForCoords,
    val sunUpdated: String? = null,
    val sunNext: String? = null,

    val stepsPreview: PreviewState = PreviewState.Loading,
    val stepsUpdated: String? = null,
    val stepsHealthGranted: Boolean = false,

    val custom: String = "",
    val customSent: String? = null,

    val lat: String = "",
    val lng: String = "",
)

data class HomeCallbacks(
    val onOpenFaces: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onUpdateNow: () -> Unit,
    val onTempUnitChange: (TempUnit) -> Unit,
    val onCustomChange: (String) -> Unit,
    val onSendCustom: () -> Unit,
    val onGrantHealth: () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = callbacks.onOpenFaces) { Text("Faces") }
                IconButton(onClick = callbacks.onOpenSettings) {
                    Text("⚙", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        HorizontalDivider()

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.watchSelected && state.activeFace != ActiveFace.CUSTOM) {
                SettingsHint("No watch selected — open Settings (⚙)")
            }
            when (state.activeFace) {
                ActiveFace.TEMPERATURE -> TemperatureBody(state, callbacks)
                ActiveFace.SUN -> SunBody(state, callbacks)
                ActiveFace.STEPS -> StepsBody(state, callbacks)
                ActiveFace.CUSTOM -> CustomBody(state, callbacks)
            }
        }

        val context = LocalContext.current
        val versionText = remember {
            val name = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull()
            versionLabel(name, context.packageName)
        }
        Text(
            versionText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun faceTitle(face: ActiveFace): String = when (face) {
    ActiveFace.TEMPERATURE -> "Temperature"
    ActiveFace.SUN -> "Sun event"
    ActiveFace.STEPS -> "Steps"
    ActiveFace.CUSTOM -> "Custom"
}

@Composable
private fun SettingsHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemperatureBody(state: HomeState, callbacks: HomeCallbacks) {
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
}

@Composable
private fun StepsBody(state: HomeState, callbacks: HomeCallbacks) {
    if (!state.stepsHealthGranted) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Health access needed", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Steps come from Health Connect, where your step-tracking app writes them. " +
                        "Grant read access to show today's count on your watch.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = callbacks.onGrantHealth, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant Health access")
                }
            }
        }
    }
    FaceValue(state.stepsPreview, state.stepsUpdated, next = null)
    Button(onClick = callbacks.onUpdateNow, modifier = Modifier.fillMaxWidth()) { Text("Update now") }
    HorizontalDivider()
    Text(
        "Pushed every ${state.updateIntervalMinutes} min while awake.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SunBody(state: HomeState, callbacks: HomeCallbacks) {
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
