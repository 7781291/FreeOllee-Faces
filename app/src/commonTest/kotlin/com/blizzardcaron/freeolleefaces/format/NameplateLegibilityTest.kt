package com.blizzardcaron.freeolleefaces.format

import com.blizzardcaron.freeolleefaces.activity.ActivityMetric
import com.blizzardcaron.freeolleefaces.activity.ActivityState
import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.glyph.NameplateGlyphs
import com.blizzardcaron.freeolleefaces.glyph.NameplateSanitizer
import com.blizzardcaron.freeolleefaces.format.BatteryReadout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guard: every face that writes the nameplate must emit only glyphs the watch renders legibly.
 * A failure here is a real defect (the watch would show garbage/blanks) — fix the producer, not
 * this test. `custom()` is excluded: it is arbitrary user input, sanitized at the send boundary.
 */
class NameplateLegibilityTest {
    private fun assertLegible(out: String, ctx: String) {
        // The nameplate is exactly 6 cells; longer payloads are truncated by the sanitizer (dropping
        // trailing glyphs like a percent 'P'), so an over-length render is itself a defect.
        assertTrue(out.length <= NameplateSanitizer.MAX_CELLS, "$ctx -> \"$out\" exceeds ${NameplateSanitizer.MAX_CELLS} cells")
        out.forEach { c -> assertTrue(NameplateGlyphs.isLegible(c), "$ctx -> \"$out\" has illegible '$c'") }
    }

    @Test
    fun activity_metric_render_is_always_legible() {
        val distances = listOf(0.0, 5.0, 95.0, 1609.0, 16093.0, 160930.0, 1_609_344.0)
        val paces = listOf<Double?>(null, 0.0, 300.0, 725.0, 6000.0)
        val times = listOf(0L, 9_000L, 65_000L, 1_565_000L, 3_725_000L, 35_900_000L, 359_000_000L)
        for (u in ActivityUnit.entries) {
            for (d in distances) for (p in paces) for (t in times) for (m in ActivityMetric.entries) {
                val state = ActivityState(
                    distanceMeters = d, recentPaceSecPerKm = p, elapsedMs = t, selectedMetric = m,
                )
                assertLegible(m.render(state, u), "metric=$m unit=$u")
            }
        }
    }

    @Test
    fun temperature_render_is_always_legible() {
        for (unit in TempUnit.entries) {
            for (temp in -40..120) {
                for (stale in listOf(false, true)) {
                    assertLegible(
                        DisplayFormatter.temperature(temp.toDouble(), unit, stale),
                        "temp=$temp unit=$unit stale=$stale",
                    )
                }
            }
        }
    }

    @Test
    fun steps_render_is_always_legible() {
        val counts = listOf(0L, 5L, 999L, 12_345L, 100_234L, 999_999L, 1_500_000L)
        for (count in counts) for (stale in listOf(false, true)) {
            assertLegible(DisplayFormatter.steps(count, stale), "steps=$count stale=$stale")
        }
    }

    @Test
    fun battery_render_is_always_legible() {
        val millivolts = listOf(2000, 2400, 2550, 2700, 2850, 3000, 3200)
        for (mv in millivolts) for (readout in BatteryReadout.entries) for (stale in listOf(false, true)) {
            assertLegible(DisplayFormatter.battery(mv, readout, stale), "mv=$mv readout=$readout stale=$stale")
        }
    }
}
