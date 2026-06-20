package com.blizzardcaron.freeolleefaces.activity

import kotlinx.serialization.Serializable

@Serializable
data class TrackPoint(
    val tMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float? = null,
    val altM: Double? = null,
)

@Serializable
data class ActivitySummary(
    val distanceM: Double,
    val movingTimeMs: Long,
    val elapsedTimeMs: Long,
    val avgPaceSecPerKm: Double,
)

@Serializable
data class ActivityTrack(
    val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val endedAbnormally: Boolean = false,
    val unit: ActivityUnit,
    val points: List<TrackPoint> = emptyList(),
    val summary: ActivitySummary? = null,
)
