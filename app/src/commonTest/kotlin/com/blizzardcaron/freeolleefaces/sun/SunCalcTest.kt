package com.blizzardcaron.freeolleefaces.sun

import com.blizzardcaron.freeolleefaces.format.SunEventKind
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.math.abs

class SunCalcTest {

    /** Assert two UTC LocalDateTimes are within `toleranceMinutes` of each other. */
    private fun assertCloseTime(expected: LocalDateTime, actual: LocalDateTime, toleranceMinutes: Long = 2) {
        val expSec = expected.toInstant(TimeZone.UTC).epochSeconds
        val actSec = actual.toInstant(TimeZone.UTC).epochSeconds
        val diff = abs(expSec - actSec)
        assertTrue(
            diff <= toleranceMinutes * 60,
            "expected ~$expected but got $actual (diff = ${diff}s)",
        )
    }

    @Test
    fun `Greenwich on 2026-03-20 reports sunrise around 06-02 UTC`() {
        // NOAA solar calculator: Greenwich (51.4779, -0.0015) on 2026-03-20.
        // Sunrise ~ 06:02 UTC, sunset ~ 18:13 UTC. (Equinox-ish.)
        val now = LocalDateTime(2026, 3, 20, 0, 0, 0).toInstant(TimeZone.UTC)
        val result = SunCalc.nextEvent(now, lat = 51.4779, lng = -0.0015, zone = TimeZone.of("UTC"))
        assertNotNull(result)
        assertEquals(SunEventKind.SUNRISE, result.kind)
        val expected = LocalDateTime(2026, 3, 20, 6, 2, 0)
        assertCloseTime(expected, result.time)
    }

    @Test
    fun `Greenwich on 2026-03-20 after sunrise returns sunset`() {
        val now = LocalDateTime(2026, 3, 20, 12, 0, 0).toInstant(TimeZone.UTC)
        val result = SunCalc.nextEvent(now, lat = 51.4779, lng = -0.0015, zone = TimeZone.of("UTC"))
        assertNotNull(result)
        assertEquals(SunEventKind.SUNSET, result.kind)
        val expected = LocalDateTime(2026, 3, 20, 18, 13, 0)
        assertCloseTime(expected, result.time)
    }

    @Test
    fun `Denver on 2026-07-04 around noon returns sunset`() {
        // NOAA: Denver (39.7392, -104.9903) on 2026-07-04.
        // Sunset ~ 02:33 UTC on 2026-07-05 (20:33 MDT, UTC-6).
        val now = LocalDateTime(2026, 7, 4, 18, 0, 0).toInstant(TimeZone.UTC)
        val result = SunCalc.nextEvent(now, lat = 39.7392, lng = -104.9903, zone = TimeZone.of("UTC"))
        assertNotNull(result)
        assertEquals(SunEventKind.SUNSET, result.kind)
        val expected = LocalDateTime(2026, 7, 5, 2, 33, 0)
        assertCloseTime(expected, result.time, toleranceMinutes = 3)
    }

    @Test
    fun `Tromso on 2026-06-21 has no sunrise or sunset within 24 hours`() {
        // Tromsø, Norway (69.6492, 18.9553) at summer solstice — midnight sun.
        val now = LocalDateTime(2026, 6, 21, 12, 0, 0).toInstant(TimeZone.UTC)
        val result = SunCalc.nextEvent(now, lat = 69.6492, lng = 18.9553, zone = TimeZone.of("UTC"))
        assertNull(result)
    }

    @Test
    fun `nextEvent localizes to requested zone`() {
        // Same Greenwich sunrise (~06:02 UTC), localized to America/New_York (EDT, UTC-4 on this
        // date) should read ~02:02 local — i.e. 4 hours behind the UTC-localized wall clock.
        val now = LocalDateTime(2026, 3, 20, 0, 0, 0).toInstant(TimeZone.UTC)
        val utcResult = SunCalc.nextEvent(now, lat = 51.4779, lng = -0.0015, zone = TimeZone.of("UTC"))
        val nyResult = SunCalc.nextEvent(now, lat = 51.4779, lng = -0.0015, zone = TimeZone.of("America/New_York"))
        assertNotNull(utcResult)
        assertNotNull(nyResult)
        assertEquals(6, utcResult.time.hour) // sanity: Greenwich sunrise ~06 UTC
        // EDT is 4 hours behind UTC.
        assertEquals(utcResult.time.hour - 4, nyResult.time.hour)
        assertEquals(utcResult.time.minute, nyResult.time.minute)
    }
}
