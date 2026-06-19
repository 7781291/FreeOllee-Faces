package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BondedDevicesDialog(
    devices: List<BondedDevice>,
    onPick: (BondedDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Select watch") },
        text = {
            if (devices.isEmpty()) {
                Text("No paired devices. Pair the watch in Android Bluetooth settings first.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    devices.forEach { d ->
                        Text(
                            "${d.name ?: "Unknown"} — ${d.address}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(d) }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
    )
}
