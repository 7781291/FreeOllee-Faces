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

        val horizon = now.plus(24 * 60 * 60, DateTimeUnit.SECOND)
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
        val lngHour = lng / 15.0
        val approxTime = n + ((if (isSunrise) 6.0 else 18.0) - lngHour) / 24.0

        // Solar mean anomaly.
        val M = (0.9856 * approxTime) - 3.289

        // True longitude.
        var L = M + (1.916 * sinDeg(M)) + (0.020 * sinDeg(2 * M)) + 282.634
        L = mod(L, 360.0)

        // Right ascension.
        var RA = atanDeg(0.91764 * tanDeg(L))
        RA = mod(RA, 360.0)

        // Adjust RA into same quadrant as L.
        val LQuadrant = (L.toInt() / 90) * 90.0
        val RAQuadrant = (RA.toInt() / 90) * 90.0
        RA = RA + (LQuadrant - RAQuadrant)
        RA /= 15.0 // -> hours

        // Solar declination.
        val sinDec = 0.39782 * sinDeg(L)
        val cosDec = cosDeg(asinDeg(sinDec))

        // Local hour angle.
        val cosH = (cosDeg(ZENITH_DEG) - (sinDec * sinDeg(lat))) / (cosDec * cosDeg(lat))
        if (cosH > 1.0 || cosH < -1.0) return null // polar — sun never reaches this zenith

        val Hdeg = if (isSunrise) 360.0 - acosDeg(cosH) else acosDeg(cosH)
        val H = Hdeg / 15.0 // -> hours

        // Local mean time of event.
        val T = H + RA - (0.06571 * approxTime) - 6.622

        // Convert to UTC.
        var UT = T - lngHour
        UT = mod(UT, 24.0)

        // Build the resulting UTC instant.
        val totalSeconds = (UT * 3600.0).toLong()
        val hours = (totalSeconds / 3600).toInt()
        val minutes = ((totalSeconds % 3600) / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
        return LocalDateTime(date, LocalTime(hours, minutes, seconds)).toInstant(TimeZone.UTC)
    }

    // ===== Degree-based trig helpers =====
    private fun toRadians(d: Double) = d * PI / 180.0
    private fun toDegrees(r: Double) = r * 180.0 / PI
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
