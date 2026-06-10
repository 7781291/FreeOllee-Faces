package com.blizzardcaron.freeolleefaces.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The notification-overlay detail screen, opened from the "Weekday slot" section of the faces
 * list. The count is an independent overlay on the watch's `0x34` weekday slot — it coexists with
 * whichever name-tag face is active — so this lives outside the single-select face list.
 */
@Composable
fun NotificationsScreen(
    enabled: Boolean,
    accessGranted: Boolean,
    count: Int,
    onToggleEnabled: (Boolean) -> Unit,
    onGrantAccess: () -> Unit,
    onUpdateNow: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Notifications", style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider()

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Show count in weekday slot", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Overlays the watch's weekday slot with your unread count. Works alongside " +
                        "whichever name-tag face (Temperature, etc.) is active.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggleEnabled)
        }

        if (enabled && !accessGranted) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Notification access needed", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Grant notification access so the watch can show how many unread " +
                            "notifications you have, in the weekday slot of the Clock face.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = onGrantAccess,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Grant notification access") }
                }
            }
        }

        Text("$count unread", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Shown in the weekday slot on the Clock face. Persistent notifications are ignored; " +
                "zero restores the weekday.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = onUpdateNow,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Update now") }
    }
}
