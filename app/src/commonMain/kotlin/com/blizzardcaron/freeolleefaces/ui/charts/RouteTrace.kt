package com.blizzardcaron.freeolleefaces.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.blizzardcaron.freeolleefaces.activity.ChartPoint
import com.blizzardcaron.freeolleefaces.activity.RoutePath

private const val STROKE_WIDTH = 3f
private const val MARKER_RADIUS = 8f
private const val MIN_POINTS = 2

/** Draws the route shape (normalized 0..1, aspect already preserved) with start/end markers. */
@Composable
fun RouteTrace(route: RoutePath, lineColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().aspectRatio(1f)) {
        val pts = route.points
        if (pts.size < MIN_POINTS) return@Canvas
        fun px(p: ChartPoint) = Offset((p.x * size.width).toFloat(), (size.height - p.y * size.height).toFloat())
        var prev = px(pts.first())
        for (i in 1 until pts.size) {
            val cur = px(pts[i])
            drawLine(lineColor, prev, cur, strokeWidth = STROKE_WIDTH)
            prev = cur
        }
        drawCircle(Color.Green, radius = MARKER_RADIUS, center = px(pts.first()))
        drawCircle(Color.Red, radius = MARKER_RADIUS, center = px(pts.last()))
    }
}
