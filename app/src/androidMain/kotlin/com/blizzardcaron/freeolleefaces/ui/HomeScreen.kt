package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.auto.ActiveFace
import com.blizzardcaron.freeolleefaces.format.DisplayFormatter
import com.blizzardcaron.freeolleefaces.format.TempUnit

private enum class FaceCardId { TEMPERATURE, STEPS, CUSTOM, NOTIFICATIONS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeState,
    callbacks: HomeCallbacks,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf<FaceCardId?>(null) }
    fun toggle(id: FaceCardId) { expanded = if (expanded == id) null else id }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Faces", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = callbacks.onOpenTimerSets) { Text("Timers") }
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

            FaceCard(
                title = "Temperature",
                badge = "active".takeIf { state.activeFace == ActiveFace.TEMPERATURE },
                preview = state.tempPreview,
                updated = state.tempUpdated,
                next = state.tempNext,
                expanded = expanded == FaceCardId.TEMPERATURE,
                onToggle = { toggle(FaceCardId.TEMPERATURE) },
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

            FaceCard(
                title = "Sun event",
                badge = "active".takeIf { state.activeFace == ActiveFace.SUN },
                preview = state.sunPreview,
                updated = state.sunUpdated,
                next = state.sunNext,
                expanded = false,
                onToggle = null,
            )

            FaceCard(
                title = "Steps",
                badge = "active".takeIf { state.activeFace == ActiveFace.STEPS },
                preview = state.stepsPreview,
                updated = state.stepsUpdated,
                next = null,
                expanded = expanded == FaceCardId.STEPS,
                onToggle = { toggle(FaceCardId.STEPS) },
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

            FaceCard(
                title = "Custom",
                badge = "active".takeIf { state.activeFace == ActiveFace.CUSTOM },
                preview = PreviewState.Ready(DisplayFormatter.custom(state.custom), "'${state.custom}'"),
                updated = null,
                next = null,
                expanded = expanded == FaceCardId.CUSTOM,
                onToggle = { toggle(FaceCardId.CUSTOM) },
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
                expanded = expanded == FaceCardId.NOTIFICATIONS,
                onToggle = { toggle(FaceCardId.NOTIFICATIONS) },
                onToggleEnabled = callbacks.onToggleNotifications,
                onGrantAccess = callbacks.onGrantNotificationAccess,
            )
        }

        Button(
            onClick = callbacks.onUpdateNow,
            // No-op for CUSTOM (its card has its own send button); also needs a watch and no send in flight.
            enabled = state.activeFace != ActiveFace.CUSTOM && state.watchSelected && !state.sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Update active now")
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

@Composable
private fun SettingsHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun FaceCard(
    title: String,
    badge: String?,
    preview: PreviewState,
    updated: String?,
    next: String?,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    expandedContent: (@Composable () -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
                        if (badge != null) {
                            Text(
                                "● $badge",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
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
