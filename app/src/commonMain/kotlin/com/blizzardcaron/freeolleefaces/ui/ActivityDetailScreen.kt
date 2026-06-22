package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.activity.ActivityTrack
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.activity.TrackAnalytics
import com.blizzardcaron.freeolleefaces.ui.charts.LineChart
import com.blizzardcaron.freeolleefaces.ui.charts.RouteTrace

private const val MIN_SERIES_POINTS = 2

/** Detail for one recorded activity: summary, route trace, and elevation/speed charts. */
@Composable
fun ActivityDetailScreen(
    track: ActivityTrack,
    unit: ActivityUnit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val elevation = TrackAnalytics.elevation(track.points)
    val speed = TrackAnalytics.speed(track.points)
    val route = TrackAnalytics.route(track.points)
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text("← History") }
        Text(historyDateLabel(track.startedAtMs), fontWeight = FontWeight.Bold)
        track.summary?.let {
            Text("Distance ${distanceText(it.distanceM, unit)}")
            Text("Time ${hms(it.elapsedTimeMs)}")
            Text("Avg pace ${paceText(it.avgPaceSecPerKm, unit)}")
        }
        if (track.endedAbnormally) Text("Ended early", fontWeight = FontWeight.Bold)

        Text("Route", fontWeight = FontWeight.Bold)
        if (route.points.size >= MIN_SERIES_POINTS) {
            RouteTrace(route, Color.Cyan)
        } else {
            Text("Not enough points for a route.")
        }

        Text("Elevation", fontWeight = FontWeight.Bold)
        if (elevation.points.size >= MIN_SERIES_POINTS) {
            LineChart(elevation.points, Color.Magenta)
        } else {
            Text("No elevation data.")
        }

        Text("Speed", fontWeight = FontWeight.Bold)
        if (speed.points.size >= MIN_SERIES_POINTS) {
            LineChart(speed.points, Color.Yellow)
        } else {
            Text("No speed data.")
        }
    }
}
