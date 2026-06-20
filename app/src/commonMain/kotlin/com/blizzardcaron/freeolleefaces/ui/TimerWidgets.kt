package com.blizzardcaron.freeolleefaces.ui

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private const val HOURS_PER_HALF_DAY = 12

/** Small fixed-width integer field labeled H/M/S, shared by the timer editor and the Quick Timer card. */
@Composable
internal fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { onChange(it.filter(Char::isDigit).take(2).toIntOrNull() ?: 0) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(80.dp),
    )
}

/** 12-hour clock + AM/PM back to the 0..23 hour stored by the alarm/timer models (12 AM = 0, 12 PM = 12). */
internal fun hour24(hour12: Int, pm: Boolean): Int =
    (hour12 % HOURS_PER_HALF_DAY) + if (pm) HOURS_PER_HALF_DAY else 0

/** The 12-hour dial value (1..12) for a 0..23 hour (0 and 12 both show 12). */
internal fun hour12Of(hour24: Int): Int =
    if (hour24 % HOURS_PER_HALF_DAY == 0) HOURS_PER_HALF_DAY else hour24 % HOURS_PER_HALF_DAY

/** True when a 0..23 hour is in the PM half. */
internal fun isPm(hour24: Int): Boolean = hour24 >= HOURS_PER_HALF_DAY

/**
 * Hour entry for the 12-hour clock. Unlike [NumberField] it keeps a local edit buffer so the
 * field can pass through empty/out-of-range text while typing (e.g. clearing "12" to type "8"),
 * committing only 1..12. NumberField's 0-renders-empty convention can't express that here:
 * hour 0 doesn't exist on a 12-hour clock, and coercing the transient 0 back to a digit made
 * hours 2-9 unreachable by normal typing.
 */
@Composable
internal fun HourField(value: Int, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val t = raw.filter(Char::isDigit).take(2)
            text = t
            t.toIntOrNull()?.takeIf { it in 1..HOURS_PER_HALF_DAY }?.let(onCommit)
        },
        label = { Text("H") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(80.dp),
    )
}
