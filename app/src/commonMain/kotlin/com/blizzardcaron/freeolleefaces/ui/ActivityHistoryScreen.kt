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
    Card(elevation = CardDefaults.cardElevation()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(historyDateLabel(track.startedAtMs), fontWeight = FontWeight.Bold)
            track.summary?.let {
                Text("Distance ${distanceText(it.distanceM, unit)}")
                Text("Time ${hms(it.elapsedTimeMs)}")
            }
            if (track.endedAbnormally) Text("Ended early", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { callbacks.onOpen(track.id) }) { Text("View") }
                OutlinedButton(onClick = { callbacks.onDelete(track.id) }) { Text("Delete") }
            }
        }
    }
}
