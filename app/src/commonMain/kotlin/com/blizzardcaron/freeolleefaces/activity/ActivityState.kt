package com.blizzardcaron.freeolleefaces.activity

/** Immutable live state of the running activity (not persisted). */
data class ActivityState(
    val running: Boolean = false,
    val selectedMetric: ActivityMetric = ActivityMetric.PACE,
    val distanceMeters: Double = 0.0,
    val recentPaceSecPerKm: Double? = null,
    val elapsedMs: Long = 0L,
    val watchReachable: Boolean = true,
    val lastPushText: String? = null,
    val headingDeg: Float? = null,
    val altitudeM: Double? = null,
)
