package com.blizzardcaron.freeolleefaces.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.prefs.IntervalOptions
import com.blizzardcaron.freeolleefaces.resources.Res
import com.blizzardcaron.freeolleefaces.resources.wordmark_super_free
import org.jetbrains.compose.resources.painterResource

private const val MINUTES_PER_HOUR = 60
private const val WORDMARK_WIDTH_FRACTION = 0.6f

@Composable
fun SettingsScreen(
    state: HomeState,
    callbacks: SettingsCallbacks,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { callbacks.onBack() }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppBar(title = "Settings", connectionStatus = state.connectionStatus, onReconnect = onReconnect)

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
            PowerSavingSection(state, callbacks)
            HorizontalDivider()
            AboutSection(state)
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
        if (state.locating) {
            "Locating…"
        } else {
            state.locationLabel + (state.locationFreshness?.let { " · $it" } ?: "")
        },
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

internal fun minutesToLabel(min: Int): String {
    val h = min / MINUTES_PER_HOUR
    return "%d:%02d %s".format(hour12Of(h), min % MINUTES_PER_HOUR, if (isPm(h)) "PM" else "AM")
}

@Composable
private fun AboutSection(state: HomeState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painter = painterResource(Res.drawable.wordmark_super_free),
                contentDescription = "Super FreeOllee",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(WORDMARK_WIDTH_FRACTION),
            )
            Text(
                state.versionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Not affiliated with Ollee · GPL-3.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
