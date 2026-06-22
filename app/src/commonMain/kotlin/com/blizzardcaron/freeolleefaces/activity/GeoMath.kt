package com.blizzardcaron.freeolleefaces.activity

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared great-circle distance. Single source of truth for the live session and track analytics. */
object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val DEG_TO_RAD = 0.017453292519943295 // PI / 180

    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val rLat1 = lat1 * DEG_TO_RAD
        val rLat2 = lat2 * DEG_TO_RAD
        val dLat = (lat2 - lat1) * DEG_TO_RAD
        val dLng = (lng2 - lng1) * DEG_TO_RAD
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(rLat1) * cos(rLat2) * sin(dLng / 2) * sin(dLng / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
