package com.blizzardcaron.freeolleefaces.ui

import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.format.formatDecimal
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
private const val MILLIS_PER_SECOND = 1000L
private const val DISTANCE_DECIMALS = 2
private const val TWO_DIGITS = 2

/** Activity formatting shared by the live, history, and detail screens. */
fun hms(ms: Long): String {
    val total = ms / MILLIS_PER_SECOND
    val h = total / SECONDS_PER_HOUR
    val m = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val s = total % SECONDS_PER_MINUTE
    val mm = m.toString().padStart(TWO_DIGITS, '0')
    val ss = s.toString().padStart(TWO_DIGITS, '0')
    return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
}

fun distanceText(meters: Double, unit: ActivityUnit): String {
    val v = unit.distance(meters)
    return "${formatDecimal(v, DISTANCE_DECIMALS)} ${unit.distanceSuffix}"
}

fun paceText(secPerKm: Double?, unit: ActivityUnit): String {
    if (secPerKm == null || secPerKm <= 0.0) return "--:-- /${unit.distanceSuffix}"
    val secs = unit.paceSecondsPerUnit(secPerKm).toInt()
    val mm = secs / SECONDS_PER_MINUTE
    val ss = (secs % SECONDS_PER_MINUTE).toString().padStart(TWO_DIGITS, '0')
    return "$mm:$ss /${unit.distanceSuffix}"
}

/** Local `YYYY-MM-DD HH:mm` label for a recorded activity's start time. */
fun historyDateLabel(startedAtMs: Long): String {
    val dt = Instant.fromEpochMilliseconds(startedAtMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = dt.monthNumber.toString().padStart(TWO_DIGITS, '0')
    val day = dt.dayOfMonth.toString().padStart(TWO_DIGITS, '0')
    val hour = dt.hour.toString().padStart(TWO_DIGITS, '0')
    val minute = dt.minute.toString().padStart(TWO_DIGITS, '0')
    return "${dt.year}-$month-$day $hour:$minute"
}
