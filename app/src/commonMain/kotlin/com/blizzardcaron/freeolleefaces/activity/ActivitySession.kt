package com.blizzardcaron.freeolleefaces.activity

import com.blizzardcaron.freeolleefaces.location.Coords
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure activity math: ingests GPS fixes, accumulates accuracy-gated, jitter-filtered haversine
 * distance, and computes a rolling-window recent pace. No coroutines, no platform — fully testable.
 */
class ActivitySession(
    private val startedAtMs: Long,
    private val accuracyGateM: Float = DEFAULT_ACCURACY_GATE_M,
    private val minMoveMeters: Double = DEFAULT_MIN_MOVE_METERS,
    private val paceWindowMs: Long = DEFAULT_PACE_WINDOW_MS,
    private val minPaceSpanMs: Long = DEFAULT_MIN_PACE_SPAN_MS,
) {
    private data class Mark(val tMs: Long, val cumulativeMeters: Double)

    private var lastAccepted: Coords? = null
    private val window = ArrayDeque<Mark>()

    var distanceMeters: Double = 0.0
        private set

    // each gate (accuracy, first-fix bootstrap, jitter floor) bails independently;
    // early returns are the clearest, safest form
    @Suppress("ReturnCount")
    fun onSample(coords: Coords, nowMs: Long) {
        val acc = coords.accuracyM
        if (acc != null && acc > accuracyGateM) return
        val prev = lastAccepted
        if (prev == null) {
            lastAccepted = coords
            window.addLast(Mark(nowMs, distanceMeters))
            return
        }
        val step = haversineMeters(prev.lat, prev.lng, coords.lat, coords.lng)
        if (step < minMoveMeters) return
        distanceMeters += step
        lastAccepted = coords
        window.addLast(Mark(nowMs, distanceMeters))
        evictOlderThan(nowMs - paceWindowMs)
    }

    private fun evictOlderThan(cutoffMs: Long) {
        while (window.size > 2 && window.first().tMs < cutoffMs) window.removeFirst()
    }

    // each missing/insufficient window precondition bails independently;
    // early returns are the clearest, safest form
    @Suppress("ReturnCount")
    fun recentPaceSecPerKm(nowMs: Long): Double? {
        evictOlderThan(nowMs - paceWindowMs)
        val oldest = window.firstOrNull() ?: return null
        val newest = window.lastOrNull() ?: return null
        val spanMs = newest.tMs - oldest.tMs
        val deltaMeters = newest.cumulativeMeters - oldest.cumulativeMeters
        if (spanMs < minPaceSpanMs || deltaMeters <= 0.0) return null
        val deltaKm = deltaMeters / METERS_PER_KM
        val spanSec = spanMs / MILLIS_PER_SECOND
        return spanSec / deltaKm
    }

    fun state(selectedMetric: ActivityMetric, nowMs: Long): ActivityState = ActivityState(
        running = true,
        selectedMetric = selectedMetric,
        distanceMeters = distanceMeters,
        recentPaceSecPerKm = recentPaceSecPerKm(nowMs),
        elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0L),
    )

    private companion object {
        const val DEFAULT_ACCURACY_GATE_M = 30f
        const val DEFAULT_MIN_MOVE_METERS = 2.0
        const val DEFAULT_PACE_WINDOW_MS = 30_000L
        const val DEFAULT_MIN_PACE_SPAN_MS = 5_000L
        const val METERS_PER_KM = 1000.0
        const val MILLIS_PER_SECOND = 1000.0
        const val EARTH_RADIUS_M = 6_371_000.0
        const val DEG_TO_RAD = 0.017453292519943295 // PI / 180
    }
}

private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val rLat1 = lat1 * DEG_TO_RAD_TOP
    val rLat2 = lat2 * DEG_TO_RAD_TOP
    val dLat = (lat2 - lat1) * DEG_TO_RAD_TOP
    val dLng = (lng2 - lng1) * DEG_TO_RAD_TOP
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(rLat1) * cos(rLat2) * sin(dLng / 2) * sin(dLng / 2)
    return EARTH_RADIUS_M_TOP * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private const val DEG_TO_RAD_TOP = 0.017453292519943295
private const val EARTH_RADIUS_M_TOP = 6_371_000.0
