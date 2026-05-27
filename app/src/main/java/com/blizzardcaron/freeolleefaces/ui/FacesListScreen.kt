package com.blizzardcaron.freeolleefaces.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.auto.ActiveFace
import androidx.compose.foundation.layout.Column

@Composable
fun FacesListScreen(
    active: ActiveFace,
    onSelect: (ActiveFace) -> Unit,
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
        FaceRow("Temperature", ActiveFace.TEMPERATURE, active, onSelect)
        FaceRow("Sun event", ActiveFace.SUN, active, onSelect)
        FaceRow("Custom", ActiveFace.CUSTOM, active, onSelect)
    }
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
