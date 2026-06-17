package com.blizzardcaron.freeolleefaces.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.ble.ConnectionStatus
import com.blizzardcaron.freeolleefaces.timer.Reorder
import com.blizzardcaron.freeolleefaces.timer.TimerSet
import com.blizzardcaron.freeolleefaces.timer.TimerSetEditing
import com.blizzardcaron.freeolleefaces.timer.TimerSlot

@Composable
fun TimerSetEditScreen(
    set: TimerSet,
    onSave: (TimerSet) -> Unit,
    onSend: (TimerSet) -> Unit,
    onBack: () -> Unit,
    connectionStatus: ConnectionStatus,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var working by remember(set.id) { mutableStateOf(set) }
    BackHandler { onBack() }

    fun updateSlot(index: Int, transform: (TimerSlot) -> TimerSlot) {
        working = working.copy(
            slots = working.slots.mapIndexed { i, s -> if (i == index) transform(s) else s },
        )
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppBar(title = "Edit set", onBack = onBack, connectionStatus = connectionStatus, onReconnect = onReconnect)

        OutlinedTextField(
            value = working.name,
            onValueChange = { working = working.copy(name = it) },
            label = { Text("Set name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { working = working.copy(slots = TimerSetEditing.sortByTime(working.slots)) },
            ) { Text("Sort by time") }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            working.slots.forEachIndexed { index, slot ->
                SlotEditor(
                    index = index,
                    slot = slot,
                    onLabelChange = { newLabel -> updateSlot(index) { s -> s.copy(label = newLabel) } },
                    onDurationChange = { secs -> updateSlot(index) { s -> s.copy(durationSeconds = secs) } },
                    onFillDown = { working = working.copy(slots = TimerSetEditing.fillDown(working.slots, index)) },
                    onDuplicate = { working = working.copy(slots = TimerSetEditing.duplicateToNext(working.slots, index)) },
                    canMoveUp = index > 0,
                    canMoveDown = index < working.slots.lastIndex,
                    onMoveUp = { working = working.copy(slots = Reorder.moveUp(working.slots, index)) },
                    onMoveDown = { working = working.copy(slots = Reorder.moveDown(working.slots, index)) },
                )
            }
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onSave(working) }, modifier = Modifier.weight(1f)) { Text("Save") }
            Button(onClick = { onSend(working) }, modifier = Modifier.weight(1f)) { Text("Save & send") }
        }
    }
}

@Composable
private fun SlotEditor(
    index: Int,
    slot: TimerSlot,
    onLabelChange: (String) -> Unit,
    onDurationChange: (Int) -> Unit,
    onFillDown: () -> Unit,
    onDuplicate: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val (h, m, s) = TimerSetEditing.secondsToHms(slot.durationSeconds)
    var menu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Slot ${index + 1}", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("▲") }
                    TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("▼") }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("...") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Fill down") },
                                onClick = { menu = false; onFillDown() })
                            DropdownMenuItem(text = { Text("Duplicate to next") },
                                onClick = { menu = false; onDuplicate() })
                        }
                    }
                }
            }
            OutlinedTextField(
                value = slot.label,
                onValueChange = onLabelChange,
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("H", h) { onDurationChange(TimerSetEditing.hmsToSeconds(it, m, s)) }
                NumberField("M", m) { onDurationChange(TimerSetEditing.hmsToSeconds(h, it, s)) }
                NumberField("S", s) { onDurationChange(TimerSetEditing.hmsToSeconds(h, m, it)) }
            }
        }
    }
}

