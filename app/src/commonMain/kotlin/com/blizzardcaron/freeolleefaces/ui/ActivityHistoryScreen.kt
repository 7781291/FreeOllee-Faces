package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.activity.ActivityTrack
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Lists recorded activities (newest first), each with a quick summary and View / Delete actions. */
@Composable
fun ActivityHistoryScreen(
    tracks: List<ActivityTrack>,
    unit: ActivityUnit,
    callbacks: ActivityHistoryCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = callbacks.onBack) { Text("← Activity") }
        if (tracks.isEmpty()) {
            Text("No recorded activities yet.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tracks, key = { it.id }) { track ->
                    HistoryCard(track, unit, callbacks)
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(track: ActivityTrack, unit: ActivityUnit, callbacks: ActivityHistoryCallbacks) {
    var showRename by remember(track.id) { mutableStateOf(false) }
    var renameText by remember(track.id) { mutableStateOf(track.label.orEmpty()) }
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(track.label?.takeIf { it.isNotBlank() } ?: historyDateLabel(track.startedAtMs), fontWeight = FontWeight.Bold)
                        if (!track.label.isNullOrBlank()) Text(historyDateLabel(track.startedAtMs))
                        track.summary?.let {
                Text("Distance ${distanceText(it.distanceM, unit)}")
                Text("Time ${hms(it.elapsedTimeMs)}")
            }
            if (track.endedAbnormally) Text("Ended early", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { callbacks.onOpen(track.id) }) { Text("View") }
                OutlinedButton(onClick = { renameText = track.label.orEmpty(); showRename = true }) { Text("Label") }
                OutlinedButton(onClick = { callbacks.onDelete(track.id) }) { Text("Delete") }
            }
        }
    }
    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Label activity") },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    placeholder = { Text("e.g. Pickleball") },
                    )
            },
            confirmButton = {
                TextButton(onClick = {
                    callbacks.onRelabel(track.id, renameText)
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            },
            )
    }
    
    }
