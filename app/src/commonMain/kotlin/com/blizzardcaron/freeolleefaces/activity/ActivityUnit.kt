package com.blizzardcaron.freeolleefaces.activity

import kotlinx.serialization.Serializable

/** Display unit system for distance and pace. Internals stay SI; convert only at render time. */
@Serializable
enum class ActivityUnit(val distanceSuffix: String, private val metersPerUnit: Double) {
    IMPERIAL("mi", 1609.344),
    METRIC("km", 1000.0);

    /** Distance in this unit (miles or kilometres) for a metre value. */
    fun distance(meters: Double): Double = meters / metersPerUnit

    /** Seconds-per-display-unit (sec/mi or sec/km) for a canonical seconds-per-km pace. */
    fun paceSecondsPerUnit(secPerKm: Double): Double = secPerKm * (metersPerUnit / METERS_PER_KM)

    companion object {
        const val METERS_PER_MILE = 1609.344
        const val METERS_PER_KM = 1000.0
    }
}
