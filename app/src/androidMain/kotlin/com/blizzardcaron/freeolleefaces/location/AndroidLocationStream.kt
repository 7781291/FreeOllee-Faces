package com.blizzardcaron.freeolleefaces.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import com.blizzardcaron.freeolleefaces.activity.LocationStream
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Continuous GPS fixes via the platform [LocationManager] (no Play Services — GrapheneOS-safe). */
class AndroidLocationStream(
    private val context: Context,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val minDistanceM: Float = DEFAULT_MIN_DISTANCE_M,
) : LocationStream {

    @SuppressLint("MissingPermission")
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION])
    override fun stream(): Flow<Coords> = callbackFlow {
        val lm = context.getSystemService(LocationManager::class.java)
            ?: error("LocationManager unavailable")
        val listener = LocationListenerCompat { loc -> trySend(loc.toCoords()) }
        LocationManagerCompat.requestLocationUpdates(
            lm,
            LocationManager.GPS_PROVIDER,
            androidx.core.location.LocationRequestCompat.Builder(minIntervalMs)
                .setMinUpdateDistanceMeters(minDistanceM)
                .build(),
            listener,
            Looper.getMainLooper(),
        )
        awaitClose { lm.removeUpdates(listener) }
    }

    private fun Location.toCoords() = Coords(
        lat = latitude,
        lng = longitude,
        accuracyM = if (hasAccuracy()) accuracy else null,
        provider = provider,
        altM = if (hasAltitude()) altitude else null,
        bearingDeg = if (hasBearing()) bearing else null,
        speedMps = if (hasSpeed()) speed else null,
    )

    private companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 1000L
        const val DEFAULT_MIN_DISTANCE_M = 0f
    }
}
