package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.format.formatDecimal
import kotlin.math.roundToInt

/**
 * The metric currently shown on the watch name-tag. `render` is the only path that produces the
 * 6-char wire string. TODO (designed-for, not built): ORIENTATION, ALTITUDE, PRESSURE — add as
 * new entries plus render branches; the track schema (`TrackPoint.altM`) already reserves altitude.
 */
enum class ActivityMetric {
    PACE, DISTANCE, TIME;

    fun next(): ActivityMetric = entries[(ordinal + 1) % entries.size]

    fun render(state: ActivityState, unit: ActivityUnit): String = when (this) {
        PACE -> renderPace(state, unit)
        DISTANCE -> renderDistance(state, unit)
        TIME -> renderTime(state)
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60
        const val SECONDS_PER_HOUR = 3600
        const val MAX_PACE_SECONDS = 99 * 60 + 59
        const val MAX_HOURS = 99
        const val MAX_DISTANCE_UNITS = 9999.0
        const val NAMEPLATE_WIDTH = 6
        const val PACE_BODY_WIDTH = 5 // 6 minus the leading 'P'
        const val MILLIS_PER_SECOND = 1000L

        fun renderPace(state: ActivityState, unit: ActivityUnit): String {
            val secPerKm = state.recentPaceSecPerKm
            if (secPerKm == null || secPerKm <= 0.0) return "P --:-"
            val secs = unit.paceSecondsPerUnit(secPerKm).roundToInt().coerceIn(0, MAX_PACE_SECONDS)
            val mm = secs / SECONDS_PER_MINUTE
            val ss = (secs % SECONDS_PER_MINUTE).toString().padStart(2, '0')
            return "P" + "$mm:$ss".padStart(PACE_BODY_WIDTH)
        }

        fun renderDistance(state: ActivityState, unit: ActivityUnit): String {
            val value = unit.distance(state.distanceMeters).coerceIn(0.0, MAX_DISTANCE_UNITS)
            val avail = NAMEPLATE_WIDTH - unit.distanceSuffix.length
            val num = listOf(2, 1, 0)
                .map { formatDecimal(value, it) }
                .firstOrNull { it.length <= avail }
                ?: formatDecimal(value, 0)
            return num + unit.distanceSuffix
        }

        fun renderTime(state: ActivityState): String {
            val totalSec = state.elapsedMs / MILLIS_PER_SECOND
            if (totalSec < SECONDS_PER_HOUR) {
                val mm = (totalSec / SECONDS_PER_MINUTE).toString().padStart(2, '0')
                val ss = (totalSec % SECONDS_PER_MINUTE).toString().padStart(2, '0')
                return "T$mm:$ss"
            }
            val h = (totalSec / SECONDS_PER_HOUR).coerceAtMost(MAX_HOURS.toLong())
            val m = ((totalSec % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toString().padStart(2, '0')
            return "$h:${m}h"
        }
    }
}
