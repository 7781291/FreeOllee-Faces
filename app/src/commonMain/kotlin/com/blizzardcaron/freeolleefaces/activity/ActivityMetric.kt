package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.format.formatDecimal
import kotlin.math.roundToInt

/**
 * The metric currently shown on the watch name-tag. `render` is the only path that produces the
 * 6-char wire string. TODO (designed-for, not built): ORIENTATION, ALTITUDE, PRESSURE — add as
 * new entries plus render branches; the track schema (`TrackPoint.altM`) already reserves altitude.
 */
enum class ActivityMetric {
    PACE, DISTANCE, TIME, ORIENTATION, ALTITUDE;

    fun next(): ActivityMetric = entries[(ordinal + 1) % entries.size]

    fun render(state: ActivityState, unit: ActivityUnit): String = when (this) {
        PACE -> renderPace(state, unit)
        DISTANCE -> renderDistance(state, unit)
        TIME -> renderTime(state)
        ORIENTATION -> renderOrientation(state.headingDeg)
        ALTITUDE -> renderAltitude(state.altitudeM, unit)
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60
        const val SECONDS_PER_HOUR = 3600
        const val MAX_PACE_SECONDS = 99 * 60 + 59
        const val MAX_HOURS = 99
        const val MAX_DISTANCE_UNITS = 9999.0
        const val NAMEPLATE_WIDTH = 6
        const val MILLIS_PER_SECOND = 1000L
        const val DISTANCE_TAG = "d" // lowercase d is legible & distinct; uppercase 'D' looks like '0'
        const val FEET_PER_METER = 3.28084
        const val FULL_CIRCLE = 360
        const val COMPASS_DIGIT_WIDTH = 3

        fun renderOrientation(headingDeg: Float?): String {
            if (headingDeg == null) return "---#"
            val deg = (headingDeg.roundToInt() % FULL_CIRCLE + FULL_CIRCLE) % FULL_CIRCLE
            return "${deg.toString().padStart(COMPASS_DIGIT_WIDTH, '0')}#${cardinal8(headingDeg)}"
        }

        fun renderAltitude(altM: Double?, unit: ActivityUnit): String {
            if (altM == null) return "---"
            val (value, suffix) =
                if (unit == ActivityUnit.IMPERIAL) (altM * FEET_PER_METER) to 'f' else altM to 'm'
            val num = value.roundToInt().toString()
            return if (num.length >= NAMEPLATE_WIDTH) num.take(NAMEPLATE_WIDTH) else "$num$suffix"
        }

        // The watch nameplate cells render ':' blank and '.' as a dash, and have no legible mi/km
        // glyphs. So min/sec separators use a space (renders identically to the blank colon),
        // distance keeps its '.' (renders as a dash separator), the unit is shown only in-app, and
        // tags use legible glyphs: 'P' pace, lowercase 'd' distance, lowercase 't' time ('h' hours).
        fun renderPace(state: ActivityState, unit: ActivityUnit): String {
            val secPerKm = state.recentPaceSecPerKm
            if (secPerKm == null || secPerKm <= 0.0) return "P --"
            val secs = unit.paceSecondsPerUnit(secPerKm).roundToInt().coerceIn(0, MAX_PACE_SECONDS)
            val mm = secs / SECONDS_PER_MINUTE
            val ss = (secs % SECONDS_PER_MINUTE).toString().padStart(2, '0')
            return "P$mm $ss"
        }

        fun renderDistance(state: ActivityState, unit: ActivityUnit): String {
            val value = unit.distance(state.distanceMeters).coerceIn(0.0, MAX_DISTANCE_UNITS)
            val avail = NAMEPLATE_WIDTH - DISTANCE_TAG.length
            val num = listOf(2, 1, 0)
                .map { formatDecimal(value, it) }
                .firstOrNull { it.length <= avail }
                ?: formatDecimal(value, 0)
            return DISTANCE_TAG + num
        }

        fun renderTime(state: ActivityState): String {
            val totalSec = state.elapsedMs / MILLIS_PER_SECOND
            if (totalSec < SECONDS_PER_HOUR) {
                val mm = (totalSec / SECONDS_PER_MINUTE).toString().padStart(2, '0')
                val ss = (totalSec % SECONDS_PER_MINUTE).toString().padStart(2, '0')
                return "t$mm $ss"
            }
            val h = (totalSec / SECONDS_PER_HOUR).coerceAtMost(MAX_HOURS.toLong())
            val m = ((totalSec % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toString().padStart(2, '0')
            return "${h}h$m"
        }
    }
}
