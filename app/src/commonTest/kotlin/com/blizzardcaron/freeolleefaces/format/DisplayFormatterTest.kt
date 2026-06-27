package com.blizzardcaron.freeolleefaces.format

import kotlin.test.Test
import kotlin.test.assertEquals
import com.blizzardcaron.freeolleefaces.format.TempUnit

class DisplayFormatterTest {

    @Test
    fun `temperature rounds and right-justifies with degree F suffix - typical`() {
        // '#' is the watch font's degree glyph (renders as '°'); see DisplayFormatter.temperature.
        assertEquals("  72#F", DisplayFormatter.temperature(72.0))
        assertEquals("  72#F", DisplayFormatter.temperature(71.6)) // rounds up
        assertEquals("  72#F", DisplayFormatter.temperature(72.4)) // rounds down
        assertEquals("   0#F", DisplayFormatter.temperature(0.0))
    }

    @Test
    fun `temperature handles negative values`() {
        assertEquals(" -12#F", DisplayFormatter.temperature(-12.0))
    }

    @Test
    fun `temperature handles three-digit values`() {
        assertEquals(" 102#F", DisplayFormatter.temperature(102.0))
        assertEquals("-100#F", DisplayFormatter.temperature(-100.0))
    }

    @Test
    fun `temperature with explicit Celsius uses C suffix`() {
        assertEquals("  22#C", DisplayFormatter.temperature(22.0, TempUnit.CELSIUS))
        assertEquals(" -12#C", DisplayFormatter.temperature(-12.0, TempUnit.CELSIUS))
    }

    @Test
    fun `temperature with explicit Fahrenheit matches default overload`() {
        assertEquals(
            DisplayFormatter.temperature(72.0),
            DisplayFormatter.temperature(72.0, TempUnit.FAHRENHEIT)
        )
    }

    @Test
    fun `temperature default overload still produces F suffix`() {
        // Regression guard for v0.1 callers that pass just a Double.
        assertEquals("  72#F", DisplayFormatter.temperature(72.0))
    }

    @Test
    fun `temperature stale replaces leading pad with E - two digit`() {
        assertEquals("E 67#F", DisplayFormatter.temperature(67.0, stale = true))
        assertEquals("E 72#F", DisplayFormatter.temperature(72.0, TempUnit.FAHRENHEIT, stale = true))
    }

    @Test
    fun `temperature stale three digit consumes the single pad`() {
        assertEquals("E100#F", DisplayFormatter.temperature(100.0, stale = true))
        assertEquals("E102#C", DisplayFormatter.temperature(102.0, TempUnit.CELSIUS, stale = true))
    }

    @Test
    fun `temperature stale is still six chars`() {
        listOf(0.0, 67.0, 100.0, -12.0).forEach {
            assertEquals(6, DisplayFormatter.temperature(it, stale = true).length, "len for $it")
        }
    }

    @Test
    fun `temperature stale three-digit negative renders unmarked - documented limitation`() {
        // No spare cell for 'E' without clobbering the '-' sign; value integrity wins. See temperature().
        assertEquals("-100#F", DisplayFormatter.temperature(-100.0, stale = true))
    }

    @Test
    fun `temperature not stale is unchanged`() {
        assertEquals("  72#F", DisplayFormatter.temperature(72.0, stale = false))
    }

    @Test
    fun `custom right-justifies shorter strings`() {
        // Right-aligned to match the watch nameplate (and temperature/steps/activity).
        assertEquals("    hi", DisplayFormatter.custom("hi"))
        assertEquals("      ", DisplayFormatter.custom(""))
    }

    @Test
    fun `custom truncates longer strings`() {
        assertEquals("toolon", DisplayFormatter.custom("toolong"))
        assertEquals("123456", DisplayFormatter.custom("12345678"))
    }

    @Test
    fun `custom passes through exactly-6-char input`() {
        assertEquals("123456", DisplayFormatter.custom("123456"))
    }

    @Test
    fun `steps right-justifies the raw count in six chars`() {
        assertEquals("     0", DisplayFormatter.steps(0))
        assertEquals("     5", DisplayFormatter.steps(5))
        assertEquals("  8421", DisplayFormatter.steps(8421))
        assertEquals(" 12345", DisplayFormatter.steps(12345))
        assertEquals(" 99999", DisplayFormatter.steps(99999))
    }

    @Test
    fun `steps fits an exactly-six-digit count with no leading space`() {
        assertEquals("100000", DisplayFormatter.steps(100_000))
        assertEquals("999999", DisplayFormatter.steps(999_999))
    }

    @Test
    fun `steps clamps physically impossible counts to six nines`() {
        assertEquals("999999", DisplayFormatter.steps(1_000_000))
        assertEquals("999999", DisplayFormatter.steps(Long.MAX_VALUE))
    }

    @Test
    fun `steps clamps negative counts to zero`() {
        assertEquals("     0", DisplayFormatter.steps(-1))
        assertEquals("     0", DisplayFormatter.steps(Long.MIN_VALUE))
    }

    @Test
    fun `steps output is always exactly six chars`() {
        listOf(0L, 9L, 99L, 12_345L, 999_999L, 1_000_000L, -5L).forEach {
            assertEquals(6, DisplayFormatter.steps(it).length, "len for $it")
        }
    }

    @Test
    fun `steps stale small count replaces leading pad with E`() {
        assertEquals("E    0", DisplayFormatter.steps(0, stale = true))
        assertEquals("E 8432", DisplayFormatter.steps(8432, stale = true))
    }

    @Test
    fun `steps stale five digit count consumes the single pad`() {
        assertEquals("E12345", DisplayFormatter.steps(12345, stale = true))
        assertEquals("E99999", DisplayFormatter.steps(99999, stale = true))
    }

    @Test
    fun `steps stale six digit count abbreviates to thousands`() {
        assertEquals("E 100k", DisplayFormatter.steps(100_000, stale = true))
        assertEquals("E 100k", DisplayFormatter.steps(100_234, stale = true)) // floors to nearest k
        assertEquals("E 999k", DisplayFormatter.steps(999_999, stale = true))
    }

    @Test
    fun `steps stale clamps impossible counts before abbreviating`() {
        assertEquals("E 999k", DisplayFormatter.steps(1_000_000, stale = true))
    }

    @Test
    fun `steps stale is always exactly six chars`() {
        listOf(0L, 5L, 8432L, 12_345L, 99_999L, 100_000L, 999_999L, 1_000_000L, -5L).forEach {
            assertEquals(6, DisplayFormatter.steps(it, stale = true).length, "len for $it")
        }
    }

    @Test
    fun `steps not stale six digit stays full`() {
        assertEquals("100234", DisplayFormatter.steps(100_234, stale = false))
    }

    @Test fun battery_volts_twoDecimalsWithV() {
        assertEquals(" 2.85V", DisplayFormatter.battery(2850, BatteryReadout.VOLTS))
        assertEquals(" 3.00V", DisplayFormatter.battery(3000, BatteryReadout.VOLTS))
        assertEquals(" 2.40V", DisplayFormatter.battery(2400, BatteryReadout.VOLTS))
    }

    @Test fun battery_volts_stalePrefixesE() {
        assertEquals("E2.85V", DisplayFormatter.battery(2850, BatteryReadout.VOLTS, stale = true))
    }

    @Test fun battery_percent_linearWithPSuffix() {
        assertEquals("   50P", DisplayFormatter.battery(2700, BatteryReadout.PERCENT))
        assertEquals("   75P", DisplayFormatter.battery(2850, BatteryReadout.PERCENT))
        assertEquals("  100P", DisplayFormatter.battery(3200, BatteryReadout.PERCENT))
    }

    @Test fun battery_percent_clampsBothRails() {
        assertEquals(0, DisplayFormatter.batteryPercent(2000))
        assertEquals(0, DisplayFormatter.batteryPercent(2400))
        assertEquals(100, DisplayFormatter.batteryPercent(3000))
        assertEquals(100, DisplayFormatter.batteryPercent(3500))
        assertEquals(50, DisplayFormatter.batteryPercent(2700))
        assertEquals(75, DisplayFormatter.batteryPercent(2850))
    }
}
