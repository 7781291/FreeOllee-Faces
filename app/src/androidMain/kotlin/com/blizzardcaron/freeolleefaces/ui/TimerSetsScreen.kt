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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.timer.TimerSet
import com.blizzardcaron.freeolleefaces.timer.TimerSetEditing
import com.blizzardcaron.freeolleefaces.timer.TimerSetsRepository

@Composable
fun TimerSetsScreen(
    sets: List<TimerSet>,
    activeId: String?,
    onOpen: (TimerSet) -> Unit,
    onNew: () -> Unit,
    onDuplicate: (TimerSet) -> Unit,
    onDelete: (TimerSet) -> Unit,
    onSend: (TimerSet) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Timer Sets", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Done") }
        }
        HorizontalDivider()

        val atMax = sets.size >= TimerSetsRepository.MAX_SETS
        Button(onClick = onNew, enabled = !atMax, modifier = Modifier.fillMaxWidth()) {
            Text(if (atMax) "Max ${TimerSetsRepository.MAX_SETS} sets" else "New set")
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (sets.isEmpty()) {
                Text("No sets yet. Tap \"New set\" to create one.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            for (set in sets) {
                TimerSetRow(
                    set = set,
                    active = set.id == activeId,
                    onOpen = { onOpen(set) },
                    onDuplicate = { onDuplicate(set) },
                    onDelete = { onDelete(set) },
                    onSend = { onSend(set) },
                )
            }
        }
    }
}

@Composable
private fun TimerSetRow(
    set: TimerSet,
    active: Boolean,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSend: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (set.name.isBlank()) "(unnamed)" else set.name,
                    style = MaterialTheme.typography.titleMedium)
                if (active) Text("● active", style = MaterialTheme.typography.labelMedium)
            }
            val count = set.slots.count { it.durationSeconds > 0 }
            val first = set.slots.firstOrNull { it.durationSeconds > 0 }?.durationSeconds
            val summary = if (first != null) {
                "$count of 10 set · first ${TimerSetEditing.formatHms(first)}"
            } else "all blank"
            Text(summary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpen) { Text("Edit") }
                TextButton(onClick = onSend) { Text("Send") }
                TextButton(onClick = onDuplicate) { Text("Duplicate") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
