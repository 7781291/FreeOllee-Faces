package com.blizzardcaron.freeolleefaces.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.blizzardcaron.freeolleefaces.activity.ChartPoint

private const val CHART_HEIGHT_DP = 120
private const val STROKE_WIDTH = 3f
private const val MIN_POINTS = 2

/** A minimal dependency-free line chart: plots [points] (already in data space) auto-scaled to the box. */
@Composable
fun LineChart(points: List<ChartPoint>, lineColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT_DP.dp)) {
        if (points.size < MIN_POINTS) return@Canvas
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val spanX = (maxX - minX).takeIf { it != 0.0 } ?: 1.0
        val spanY = (maxY - minY).takeIf { it != 0.0 } ?: 1.0
        fun px(p: ChartPoint) = Offset(
            x = ((p.x - minX) / spanX * size.width).toFloat(),
            y = (size.height - (p.y - minY) / spanY * size.height).toFloat(),
        )
        var prev = px(points.first())
        for (i in 1 until points.size) {
            val cur = px(points[i])
            drawLine(lineColor, prev, cur, strokeWidth = STROKE_WIDTH)
            prev = cur
        }
    }
}
