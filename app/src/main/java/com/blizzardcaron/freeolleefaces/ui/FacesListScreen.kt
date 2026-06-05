package com.blizzardcaron.freeolleefaces.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.auto.ActiveFace

/**
 * Two groups, mirroring the watch's two independent display registers:
 * - **Name tag** (`0x2F`, main digits): mutually exclusive — pick one with a radio.
 * - **Weekday slot** (`0x34`): independent overlays toggled on/off — currently just the
 *   notification count, which coexists with whichever name-tag face is active.
 */
@Composable
fun FacesListScreen(
    active: ActiveFace,
    notificationsEnabled: Boolean,
    onSelect: (ActiveFace) -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onOpenNotifications: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Faces", style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider()

        SectionLabel("Name tag")
        FaceRow("Temperature", ActiveFace.TEMPERATURE, active, onSelect)
        FaceRow("Sun event", ActiveFace.SUN, active, onSelect)
        FaceRow("Steps", ActiveFace.STEPS, active, onSelect)
        FaceRow("Custom", ActiveFace.CUSTOM, active, onSelect)

        HorizontalDivider()

        SectionLabel("Weekday slot")
        NotificationsRow(
            enabled = notificationsEnabled,
            onToggle = onToggleNotifications,
            onOpen = onOpenNotifications,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun FaceRow(
    label: String,
    face: ActiveFace,
    active: ActiveFace,
    onSelect: (ActiveFace) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(face) }
            .padding(vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = face == active, onClick = { onSelect(face) })
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Text("›", style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The notification overlay row: a Switch toggles it on/off, and tapping the row opens the detail
 * screen ([NotificationsScreen]) for access-grant and the live count. Unlike a [FaceRow] this is a
 * toggle, not a radio — the count is additive, not mutually exclusive with the name-tag faces.
 */
@Composable
private fun NotificationsRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(checked = enabled, onCheckedChange = onToggle)
            Text("Notifications", style = MaterialTheme.typography.bodyLarge)
        }
        Text("›", style = MaterialTheme.typography.bodyLarge)
    }
}
