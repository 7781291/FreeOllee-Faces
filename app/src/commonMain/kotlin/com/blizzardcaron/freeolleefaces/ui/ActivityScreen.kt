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
    OutlinedButton(onClick = callbacks.onToggleUnit, modifier = Modifier.fillMaxWidth()) {
        Text("Units: ${if (unit == ActivityUnit.IMPERIAL) "Miles" else "Kilometres"}")
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
    // Show each metric exactly as the watch renders it (faithful segment preview).
    Readout("Pace", ActivityMetric.PACE.render(state, unit), state.selectedMetric == ActivityMetric.PACE)
    Readout("Distance", ActivityMetric.DISTANCE.render(state, unit), state.selectedMetric == ActivityMetric.DISTANCE)
    Readout("Time", ActivityMetric.TIME.render(state, unit), state.selectedMetric == ActivityMetric.TIME)
    val watchStatusText = if (!watchSelected) {
        "No watch — recording only"
    } else if (state.watchReachable) {
        "Watch: showing ${state.lastPushText ?: "…"}"
    } else {
        "Watch unreachable — recording continues"
    }
    Text(watchStatusText, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = callbacks.onMode, modifier = Modifier.weight(1f)) { Text("MODE") }
        Button(onClick = callbacks.onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
    }
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

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
private const val MILLIS_PER_SECOND = 1000L

private fun hms(ms: Long): String {
    val total = ms / MILLIS_PER_SECOND
    val h = total / SECONDS_PER_HOUR
    val m = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val s = total % SECONDS_PER_MINUTE
    val mm = m.toString().padStart(2, '0')
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
}

private fun distanceText(meters: Double, unit: ActivityUnit): String {
    val v = unit.distance(meters)
    return "${com.blizzardcaron.freeolleefaces.format.formatDecimal(v, 2)} ${unit.distanceSuffix}"
}

private fun paceText(secPerKm: Double?, unit: ActivityUnit): String {
    if (secPerKm == null || secPerKm <= 0.0) return "--:-- /${unit.distanceSuffix}"
    val secs = unit.paceSecondsPerUnit(secPerKm).toInt()
    val mm = secs / SECONDS_PER_MINUTE
    val ss = (secs % SECONDS_PER_MINUTE).toString().padStart(2, '0')
    return "$mm:$ss /${unit.distanceSuffix}"
}
