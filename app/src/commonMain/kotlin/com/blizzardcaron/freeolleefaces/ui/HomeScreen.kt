package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.auto.ActiveComplication
import com.blizzardcaron.freeolleefaces.auto.displayLabel
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.format.TempUnit

private enum class ComplicationCardId { TEMPERATURE, STEPS, CUSTOM, NOTIFICATIONS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeState,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf<ComplicationCardId?>(null) }
    fun toggle(id: ComplicationCardId) { expanded = if (expanded == id) null else id }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Complications", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = callbacks.onOpenTimerSets) { Text("Timers") }
                TextButton(onClick = callbacks.onOpenComplications) { Text("Complications") }
                IconButton(onClick = callbacks.onOpenSettings) {
                    Text("⚙", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        HorizontalDivider()

        Text(
            "Active: ${state.activeComplication.displayLabel()}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.watchSelected && state.activeComplication != ActiveComplication.CUSTOM) {
                SettingsHint("No watch selected — open Settings (⚙)")
            }

            ComplicationCard(
                title = ActiveComplication.TEMPERATURE.displayLabel(),
                active = state.activeComplication == ActiveComplication.TEMPERATURE,
                preview = state.tempPreview,
                updated = state.tempUpdated,
                next = state.tempNext,
                expanded = expanded == ComplicationCardId.TEMPERATURE,
                onToggle = { toggle(ComplicationCardId.TEMPERATURE) },
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
            }

            ComplicationCard(
                title = ActiveComplication.SUN.displayLabel(),
                active = state.activeComplication == ActiveComplication.SUN,
                preview = state.sunPreview,
                updated = state.sunUpdated,
                next = state.sunNext,
                expanded = false,
                onToggle = null,
            )

            ComplicationCard(
                title = ActiveComplication.STEPS.displayLabel(),
                active = state.activeComplication == ActiveComplication.STEPS,
                preview = state.stepsPreview,
                updated = state.stepsUpdated,
                next = null,
                expanded = expanded == ComplicationCardId.STEPS,
                onToggle = { toggle(ComplicationCardId.STEPS) },
            ) {
                if (!state.stepsHealthGranted) {
                    Text("Health access needed", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Steps come from Health Connect, where your step-tracking app writes them. " +
                            "Grant read access to show today's count on your watch.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = callbacks.onGrantHealth, modifier = Modifier.fillMaxWidth()) {
                        Text("Grant Health access")
                    }
                } else {
                    Text(
                        "Pushed every ${state.updateIntervalMinutes} min while awake.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            ComplicationCard(
                title = ActiveComplication.CUSTOM.displayLabel(),
                active = state.activeComplication == ActiveComplication.CUSTOM,
                preview = PreviewState.Ready(DisplayFormatter.custom(state.custom), "'${state.custom}'"),
                updated = null,
                next = null,
                expanded = expanded == ComplicationCardId.CUSTOM,
                onToggle = { toggle(ComplicationCardId.CUSTOM) },
            ) {
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

            NotificationsCard(
                state = state,
                expanded = expanded == ComplicationCardId.NOTIFICATIONS,
                onToggle = { toggle(ComplicationCardId.NOTIFICATIONS) },
                onToggleEnabled = callbacks.onToggleNotifications,
                onGrantAccess = callbacks.onGrantNotificationAccess,
            )
        }

        Button(
            onClick = callbacks.onUpdateNow,
            // No-op for CUSTOM (its card has its own send button); also needs a watch and no send in flight.
            enabled = state.activeComplication != ActiveComplication.CUSTOM && state.watchSelected && !state.sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Update active now")
        }

        Text(
            state.versionLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingsHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

/** Unmistakable "ACTIVE" pill shown on the currently-active complication card. */
@Composable
private fun ActiveChip() {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            "ACTIVE",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ComplicationCard(
    title: String,
    active: Boolean,
    preview: PreviewState,
    updated: String?,
    next: String?,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    expandedContent: (@Composable () -> Unit)? = null,
) {
    val cardColors = if (active) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    val border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Card(modifier = Modifier.fillMaxWidth(), colors = cardColors, border = border) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val headerModifier = Modifier
                .fillMaxWidth()
                .let { if (onToggle != null) it.clickable { onToggle() } else it }
                .padding(12.dp)
            Column(modifier = headerModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (active) {
                            ActiveChip()
                        }
                        if (onToggle != null) {
                            Text(if (expanded) "  ▾" else "  ▸", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                FaceValue(preview, updated, next)
            }
            if (expanded && expandedContent != null) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { expandedContent() }
            }
        }
    }
}

@Composable
private fun NotificationsCard(
    state: HomeState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onGrantAccess: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Notifications", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.notificationsEnabled) {
                            Text(
                                "● on",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(if (expanded) "  ▾" else "  ▸", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val human = if (state.notificationsEnabled) "${state.notificationCount} unread" else "Off"
                Text(human, style = MaterialTheme.typography.headlineMedium)
                Text("Weekday slot overlay on the Clock face.", style = MaterialTheme.typography.bodySmall)
            }
            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Show count in weekday slot", style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = state.notificationsEnabled, onCheckedChange = onToggleEnabled)
                    }
                    if (state.notificationsEnabled && !state.notificationAccessGranted) {
                        Text("Notification access needed", style = MaterialTheme.typography.titleSmall)
                        Button(onClick = onGrantAccess, modifier = Modifier.fillMaxWidth()) {
                            Text("Grant notification access")
                        }
                    }
                }
            }
        }
    }
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
