package com.blizzardcaron.freeolleefaces.sun

import com.blizzardcaron.freeolleefaces.format.SunEventKind
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

data class NextEvent(val kind: SunEventKind, val time: LocalDateTime)

object SunCalc {

    // Standard "official" zenith for sunrise/sunset: 90.833° (includes refraction + solar disc).
    private const val ZENITH_DEG = 90.833

    // ===== Unit / time conversion constants =====
    private const val DEGREES_PER_HOUR = 15.0
    private const val HOURS_PER_DAY = 24.0
    private const val DEGREES_FULL_CIRCLE = 360.0
    private const val DEGREES_PER_QUADRANT = 90
    private const val DEGREES_HALF_CIRCLE = 180.0
    private const val SUNRISE_APPROX_HOUR = 6.0
    private const val SUNSET_APPROX_HOUR = 18.0
    private const val SECONDS_PER_HOUR = 3600.0
    private const val SECONDS_PER_HOUR_INT = 3600
    private const val SECONDS_PER_MINUTE = 60
    private const val HORIZON_SECONDS = 24 * 60 * 60

    /**
     * Returns the next sunrise OR sunset (whichever is soonest) strictly after [now], in the
     * requested [zone], or `null` if neither event occurs within the next 24 hours
     * (polar day/night).
     */
    fun nextEvent(now: Instant, lat: Double, lng: Double, zone: TimeZone): NextEvent? {
        val today = now.toLocalDateTime(TimeZone.UTC).date
        // Compute today's events and tomorrow's events in UTC, carrying each event's UTC instant
        // alongside its localized LocalDateTime so we can compare against [now] / the 24h horizon.
        val candidates = buildList {
            for (offset in 0..1) {
                val date = today.plus(offset, DateTimeUnit.DAY)
                val rise = computeEventUtc(date, lat, lng, isSunrise = true)
                val set = computeEventUtc(date, lat, lng, isSunrise = false)
                if (rise != null) add(Candidate(SunEventKind.SUNRISE, rise, rise.toLocalDateTime(zone)))
                if (set != null) add(Candidate(SunEventKind.SUNSET, set, set.toLocalDateTime(zone)))
            }
        }

        val horizon = now.plus(HORIZON_SECONDS, DateTimeUnit.SECOND)
        return candidates
            .filter { it.instant > now && it.instant < horizon || it.instant == horizon }
            .minByOrNull { it.instant }
            ?.let { NextEvent(it.kind, it.time) }
    }

    private data class Candidate(val kind: SunEventKind, val instant: Instant, val time: LocalDateTime)

    /**
     * Compute one event (sunrise or sunset) on [date] for ([lat], [lng]) and return its UTC
     * instant, or null if no event occurs that day at that latitude (polar).
     *
     * Algorithm: NOAA general solar position calculation
     * (https://gml.noaa.gov/grad/solcalc/calcdetails.html).
     */
    private fun computeEventUtc(
        date: LocalDate,
        lat: Double,
        lng: Double,
        isSunrise: Boolean,
    ): Instant? {
        // Day of year, 1-based.
        val n = date.dayOfYear.toDouble()

        // Approximate time of event in fractional days (sunrise uses 6, sunset uses 18).
        val lngHour = lng / DEGREES_PER_HOUR
        val approxTime = n + ((if (isSunrise) SUNRISE_APPROX_HOUR else SUNSET_APPROX_HOUR) - lngHour) / HOURS_PER_DAY

        // Solar mean anomaly.
        val M = (0.9856 * approxTime) - 3.289

        // True longitude.
        var L = M + (1.916 * sinDeg(M)) + (0.020 * sinDeg(2 * M)) + 282.634
        L = mod(L, DEGREES_FULL_CIRCLE)

        // Right ascension.
        var RA = atanDeg(0.91764 * tanDeg(L))
        RA = mod(RA, DEGREES_FULL_CIRCLE)

        // Adjust RA into same quadrant as L.
        val LQuadrant = (L.toInt() / DEGREES_PER_QUADRANT) * DEGREES_PER_QUADRANT.toDouble()
        val RAQuadrant = (RA.toInt() / DEGREES_PER_QUADRANT) * DEGREES_PER_QUADRANT.toDouble()
        RA = RA + (LQuadrant - RAQuadrant)
        RA /= DEGREES_PER_HOUR // -> hours

        // Solar declination.
        val sinDec = 0.39782 * sinDeg(L)
        val cosDec = cosDeg(asinDeg(sinDec))

        // Local hour angle.
        val cosH = (cosDeg(ZENITH_DEG) - (sinDec * sinDeg(lat))) / (cosDec * cosDeg(lat))
        if (cosH > 1.0 || cosH < -1.0) return null // polar — sun never reaches this zenith

        val Hdeg = if (isSunrise) DEGREES_FULL_CIRCLE - acosDeg(cosH) else acosDeg(cosH)
        val H = Hdeg / DEGREES_PER_HOUR // -> hours

        // Local mean time of event.
        val T = H + RA - (0.06571 * approxTime) - 6.622

        // Convert to UTC.
        var UT = T - lngHour
        UT = mod(UT, HOURS_PER_DAY)

        // Build the resulting UTC instant.
        val totalSeconds = (UT * SECONDS_PER_HOUR).toLong()
        val hours = (totalSeconds / SECONDS_PER_HOUR_INT).toInt()
        val minutes = ((totalSeconds % SECONDS_PER_HOUR_INT) / SECONDS_PER_MINUTE).toInt()
        val seconds = (totalSeconds % SECONDS_PER_MINUTE).toInt()
        return LocalDateTime(date, LocalTime(hours, minutes, seconds)).toInstant(TimeZone.UTC)
    }

    // ===== Degree-based trig helpers =====
    private fun toRadians(d: Double) = d * PI / DEGREES_HALF_CIRCLE
    private fun toDegrees(r: Double) = r * DEGREES_HALF_CIRCLE / PI
    private fun sinDeg(d: Double) = sin(toRadians(d))
    private fun cosDeg(d: Double) = cos(toRadians(d))
    private fun tanDeg(d: Double) = tan(toRadians(d))
    private fun asinDeg(x: Double) = toDegrees(kotlin.math.asin(x))
    private fun acosDeg(x: Double) = toDegrees(acos(x))
    private fun atanDeg(x: Double) = toDegrees(kotlin.math.atan(x))

    private fun mod(x: Double, m: Double): Double {
        val r = x % m
        return if (r < 0) r + m else r
    }
}
