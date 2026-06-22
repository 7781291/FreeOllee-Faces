package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.format.formatDecimal
import com.blizzardcaron.freeolleefaces.instruments.Instrument
import com.blizzardcaron.freeolleefaces.instruments.InstrumentsState
import com.blizzardcaron.freeolleefaces.instruments.PressureSource
import com.blizzardcaron.freeolleefaces.instruments.cardinal8
import kotlin.math.roundToInt

/** The Instruments tab. Idle shows Start + unit toggle; running shows the four live readouts
 *  (compass/altitude/pressure/temperature), each as the faithful watch segment preview plus a
 *  rich in-app label, with the selected one highlighted, a MODE button, and Stop. */
@Composable
fun InstrumentsScreen(
    state: InstrumentsState,
    unit: ActivityUnit,
    tempUnit: TempUnit,
    watchSelected: Boolean,
    callbacks: InstrumentsCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.running) {
            RunningContent(state, unit, tempUnit, watchSelected, callbacks)
        } else {
            Button(onClick = callbacks.onStart, modifier = Modifier.fillMaxWidth()) {
                Text("Start instruments")
            }
            OutlinedButton(onClick = callbacks.onToggleUnit, modifier = Modifier.fillMaxWidth()) {
                Text("Units: ${if (unit == ActivityUnit.IMPERIAL) "Imperial" else "Metric"}")
            }
        }
    }
}

@Composable
private fun RunningContent(
    state: InstrumentsState,
    unit: ActivityUnit,
    tempUnit: TempUnit,
    watchSelected: Boolean,
    callbacks: InstrumentsCallbacks,
) {
    for (inst in Instrument.entries) {
        Readout(
            label = inst.name.lowercase().replaceFirstChar { it.uppercase() },
            rich = richLabel(inst, state, unit, tempUnit),
            watchValue = inst.render(state, unit, tempUnit),
            selected = state.selectedInstrument == inst,
        )
    }
    Text(pressureSourceNote(state.pressureSource), style = MaterialTheme.typography.bodySmall)
    if (state.onboardTempF == null) {
        Text("Enable Temperature in the Ollee app", style = MaterialTheme.typography.bodySmall)
    }
    Text(watchStatusText(watchSelected, state), style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = callbacks.onMode, modifier = Modifier.weight(1f)) { Text("MODE") }
        Button(onClick = callbacks.onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
    }
}

@Composable
private fun Readout(label: String, rich: String, watchValue: String, selected: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (selected) "▶ $label" else label)
            Text(rich, style = MaterialTheme.typography.bodySmall)
        }
        SegmentReadout(
            value = watchValue,
            cellHeight = if (selected) 36.dp else 26.dp,
            tone = LcdTone.Green,
        )
    }
}

private fun pressureSourceNote(source: PressureSource): String = when (source) {
    PressureSource.SENSOR -> "Pressure: barometer"
    PressureSource.NETWORK -> "Pressure: weather (approx.)"
    PressureSource.NONE -> "Pressure: unavailable"
}

private fun watchStatusText(watchSelected: Boolean, state: InstrumentsState): String = when {
    !watchSelected -> "No watch selected"
    state.watchReachable -> "Watch: showing ${state.lastPushText ?: "…"}"
    else -> "Watch unreachable"
}

private const val DASH = "—"

private fun richLabel(
    inst: Instrument,
    state: InstrumentsState,
    unit: ActivityUnit,
    tempUnit: TempUnit,
): String = when (inst) {
    Instrument.COMPASS -> compassLabel(state.headingDeg)
    Instrument.ALTITUDE -> altitudeLabel(state.altitudeM, unit)
    Instrument.PRESSURE -> pressureLabel(state.pressureHpa, unit)
    Instrument.TEMPERATURE -> temperatureLabel(state.onboardTempF, tempUnit)
}

private fun compassLabel(headingDeg: Float?): String =
    headingDeg?.let { "${it.roundToInt()}° ${cardinal8(it).trim()}" } ?: DASH

private fun altitudeLabel(altM: Double?, unit: ActivityUnit): String {
    if (altM == null) return DASH
    return if (unit == ActivityUnit.IMPERIAL) {
        "${(altM * FEET_PER_METER).roundToInt()} ft"
    } else {
        "${altM.roundToInt()} m"
    }
}

private fun pressureLabel(hpa: Double?, unit: ActivityUnit): String {
    if (hpa == null) return DASH
    return if (unit == ActivityUnit.IMPERIAL) {
        "${formatDecimal(hpa * INHG_PER_HPA, INHG_DECIMALS)} inHg"
    } else {
        "${hpa.roundToInt()} hPa"
    }
}

private fun temperatureLabel(tempF: Int?, tempUnit: TempUnit): String {
    if (tempF == null) return DASH
    return if (tempUnit == TempUnit.CELSIUS) {
        "${((tempF - FAHRENHEIT_OFFSET) * F_TO_C_NUM / F_TO_C_DEN).roundToInt()} °C"
    } else {
        "$tempF °F"
    }
}

private const val FEET_PER_METER = 3.28084
private const val INHG_PER_HPA = 0.0295299830714
private const val INHG_DECIMALS = 2
private const val FAHRENHEIT_OFFSET = 32
private const val F_TO_C_NUM = 5.0
private const val F_TO_C_DEN = 9.0
