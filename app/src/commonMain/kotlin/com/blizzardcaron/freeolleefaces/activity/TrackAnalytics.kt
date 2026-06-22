package com.blizzardcaron.freeolleefaces.activity

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

data class ChartPoint(val x: Double, val y: Double)
data class ElevationSeries(val points: List<ChartPoint>)
data class SpeedSeries(val points: List<ChartPoint>)
data class RoutePath(val points: List<ChartPoint>)

/** Pure derivations from a recorded track's points for the detail-screen charts and route trace. */
object TrackAnalytics {
    private const val MS_PER_SEC = 1000.0
    private const val DEG_TO_RAD = PI / 180.0
    private const val HALF = 2.0

    private fun cumulativeDistances(points: List<TrackPoint>): List<Double> {
        val out = ArrayList<Double>(points.size)
        var acc = 0.0
        points.forEachIndexed { i, pt ->
            if (i > 0) {
                val prev = points[i - 1]
                acc += GeoMath.haversineMeters(prev.lat, prev.lng, pt.lat, pt.lng)
            }
            out += acc
        }
        return out
    }

    fun elevation(points: List<TrackPoint>): ElevationSeries {
        val dist = cumulativeDistances(points)
        val pts = points.mapIndexedNotNull { i, pt -> pt.altM?.let { ChartPoint(dist[i], it) } }
        return ElevationSeries(pts)
    }

    fun speed(points: List<TrackPoint>): SpeedSeries {
        val dist = cumulativeDistances(points)
        val pts = ArrayList<ChartPoint>()
        for (i in 1 until points.size) {
            val dtSec = (points[i].tMs - points[i - 1].tMs) / MS_PER_SEC
            if (dtSec <= 0.0) continue
            val seg = dist[i] - dist[i - 1]
            pts += ChartPoint(dist[i], seg / dtSec)
        }
        return SpeedSeries(pts)
    }

    fun route(points: List<TrackPoint>): RoutePath {
        val distinct = points.distinctBy { it.lat to it.lng }
        if (distinct.size < 2) return RoutePath(emptyList())
        val minLat = points.minOf { it.lat }
        val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }
        val maxLng = points.maxOf { it.lng }
        val midLatRad = ((minLat + maxLat) / HALF) * DEG_TO_RAD
        val spanX = (maxLng - minLng) * cos(midLatRad)
        val spanY = maxLat - minLat
        val span = max(max(spanX, spanY), Double.MIN_VALUE)
        val pts = points.map {
            ChartPoint(((it.lng - minLng) * cos(midLatRad)) / span, (it.lat - minLat) / span)
        }
        return RoutePath(pts)
    }
}
