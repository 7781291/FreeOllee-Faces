package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.activity.ActivityMetric
import com.blizzardcaron.freeolleefaces.activity.ActivityState
import com.blizzardcaron.freeolleefaces.activity.ActivitySummary
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit

/** The Activity tab. Idle shows Start + unit toggle + last-activity summary; running shows the
 *  three live readouts (selected one highlighted), a MODE button, and Stop. */
@Composable
fun ActivityScreen(
    state: ActivityState,
    unit: ActivityUnit,
    watchSelected: Boolean,
    lastSummary: ActivitySummary?,
    callbacks: ActivityCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.running) {
            RunningContent(state, unit, watchSelected, callbacks)
        } else {
            IdleContent(unit, lastSummary, callbacks)
        }
    }
}

@Composable
private fun IdleContent(
    unit: ActivityUnit,
    lastSummary: ActivitySummary?,
    callbacks: ActivityCallbacks,
) {
    Button(onClick = callbacks.onStart, modifier = Modifier.fillMaxWidth()) { Text("Start activity") }
    OutlinedButton(onClick = callbacks.onShowLive, modifier = Modifier.fillMaxWidth()) {
        Text("Instrument glance")
    }
    OutlinedButton(onClick = callbacks.onToggleUnit, modifier = Modifier.fillMaxWidth()) {
        Text("Units: ${if (unit == ActivityUnit.IMPERIAL) "Miles" else "Kilometres"}")
    }
    OutlinedButton(onClick = callbacks.onOpenHistory, modifier = Modifier.fillMaxWidth()) {
        Text("History")
    }
    if (lastSummary != null) {
        Card(elevation = CardDefaults.cardElevation()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Last activity", fontWeight = FontWeight.Bold)
                Text("Distance ${distanceText(lastSummary.distanceM, unit)}")
                Text("Time ${hms(lastSummary.elapsedTimeMs)}")
                Text("Avg pace ${paceText(lastSummary.avgPaceSecPerKm, unit)}")
            }
        }
    }
}

@Composable
private fun RunningContent(
    state: ActivityState,
    unit: ActivityUnit,
    watchSelected: Boolean,
    callbacks: ActivityCallbacks,
) {
    // Show each metric exactly as the watch renders it (faithful segment preview). The visible
    // set depends on mode: recording shows pace/distance/time, the live glance shows the instruments.
    if (state.recording) {
        MetricReadout("Pace", ActivityMetric.PACE, state, unit)
        MetricReadout("Distance", ActivityMetric.DISTANCE, state, unit)
        MetricReadout("Time", ActivityMetric.TIME, state, unit)
    } else {
        MetricReadout("Compass", ActivityMetric.ORIENTATION, state, unit)
        MetricReadout("Altitude", ActivityMetric.ALTITUDE, state, unit)
        MetricReadout("Pressure", ActivityMetric.PRESSURE, state, unit)
    }
    val watchStatusText = if (!watchSelected) {
        if (state.recording) "No watch — recording only" else "No watch — glance only"
    } else if (state.watchReachable) {
        "Watch: showing ${state.lastPushText ?: "…"}"
    } else if (state.recording) {
        "Watch unreachable — recording continues"
    } else {
        "Watch unreachable"
    }
    Text(watchStatusText, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = callbacks.onMode, modifier = Modifier.weight(1f)) { Text("MODE") }
        if (state.recording) {
            Button(onClick = callbacks.onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
        } else {
            Button(onClick = callbacks.onStart, modifier = Modifier.weight(1f)) { Text("Record") }
        }
    }
    if (!state.recording) {
        OutlinedButton(onClick = callbacks.onStop, modifier = Modifier.fillMaxWidth()) { Text("Close glance") }
    }
}

@Composable
private fun MetricReadout(label: String, metric: ActivityMetric, state: ActivityState, unit: ActivityUnit) {
    Readout(label, metric.render(state, unit), state.selectedMetric == metric)
}

@Composable
private fun Readout(label: String, watchValue: String, selected: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (selected) "▶ $label" else label, modifier = Modifier.weight(1f))
        SegmentReadout(
            value = watchValue,
            cellHeight = if (selected) 36.dp else 26.dp,
            tone = LcdTone.Green,
        )
    }
}
