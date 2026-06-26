package com.blizzardcaron.freeolleefaces.activity

import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityMetricInstrumentRenderTest {
    private val metric = ActivityUnit.METRIC
    private val imperial = ActivityUnit.IMPERIAL

    @Test fun orientation_null_heading_renders_placeholder() {
        val s = ActivityState(headingDeg = null)
        assertEquals("---#", ActivityMetric.ORIENTATION.render(s, metric))
    }

    @Test fun orientation_renders_degrees_and_cardinal() {
        val s = ActivityState(headingDeg = 90f)
        assertEquals("090#E ", ActivityMetric.ORIENTATION.render(s, metric))
    }

    @Test fun altitude_metric_appends_m() {
        val s = ActivityState(altitudeM = 1234.0)
        assertEquals("1234m", ActivityMetric.ALTITUDE.render(s, metric))
    }

    @Test fun altitude_imperial_converts_to_feet() {
        val s = ActivityState(altitudeM = 100.0)
        assertEquals("328f", ActivityMetric.ALTITUDE.render(s, imperial))
    }

    @Test fun altitude_null_renders_placeholder() {
        val s = ActivityState(altitudeM = null)
        assertEquals("---", ActivityMetric.ALTITUDE.render(s, metric))
    }

    @Test fun pressure_metric_renders_whole_hpa() {
        val s = ActivityState(pressureHpa = 1013.0)
        assertEquals("  1013", ActivityMetric.PRESSURE.render(s, metric))
    }

    @Test fun pressure_imperial_renders_inhg() {
        val s = ActivityState(pressureHpa = 1013.0)
        assertEquals(" 29.91", ActivityMetric.PRESSURE.render(s, imperial))
    }

    @Test fun pressure_null_renders_placeholder() {
        val s = ActivityState(pressureHpa = null)
        assertEquals("----", ActivityMetric.PRESSURE.render(s, metric))
    }
}
