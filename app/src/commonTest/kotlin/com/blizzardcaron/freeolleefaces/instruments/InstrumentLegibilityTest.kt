package com.blizzardcaron.freeolleefaces.instruments

import com.blizzardcaron.freeolleefaces.activity.ActivityUnit
import com.blizzardcaron.freeolleefaces.format.TempUnit
import com.blizzardcaron.freeolleefaces.glyph.NameplateSanitizer
import kotlin.test.Test
import kotlin.test.assertEquals

class InstrumentLegibilityTest {
    @Test fun everyProducedStringSurvivesSanitizerUnchanged() {
        val units = ActivityUnit.entries
        val temps = TempUnit.entries
        for (deg in 0..359) for (u in units) for (t in temps) {
            val s = InstrumentsState(
                headingDeg = deg.toFloat(), altitudeM = -413.0 + deg, pressureHpa = 950.0 + deg, onboardTempF = deg - 50,
            )
            for (inst in Instrument.entries) {
                val raw = inst.render(s, u, t)
                assertEquals(raw, NameplateSanitizer.sanitize(raw), "instrument=$inst deg=$deg unit=$u temp=$t")
                check(raw.length <= NameplateSanitizer.MAX_CELLS) { "too long: '$raw'" }
            }
        }
    }
}
