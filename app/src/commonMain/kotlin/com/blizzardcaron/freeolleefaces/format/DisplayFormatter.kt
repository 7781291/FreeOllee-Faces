package com.blizzardcaron.freeolleefaces.format

import kotlin.math.roundToInt

object DisplayFormatter {

    private const val LENGTH = 6

    /** Width of the temperature digits field (degree glyph + unit letter fill the remaining 2 cells). */
    private const val TEMP_DIGITS_WIDTH = 4

    /** Steps clamp ceiling: no real day of walking comes close, so this only guards bogus aggregates. */
    private const val MAX_STEPS = 999_999L

    /** Divisor to abbreviate a step count to thousands (e.g. 100_234 -> "100k"). */
    private const val THOUSAND = 1000

    private const val INHG_PER_HPA = 0.0295299830714
    private const val INHG_DECIMALS = 2

    private const val FEET_PER_METER = 3.28084

    fun temperature(value: Double, unit: TempUnit = TempUnit.FAHRENHEIT, stale: Boolean = false): String {
        // The watch's segment font (firmware OW-FW-APP, font table indexed by ASCII) maps '#'
        // (mask 0x63) to segments a+b+f+g — the top square that reads as a degree '°'. There is no
        // glyph at Latin-1 0xB0, so we send '#' to get the degree ring, e.g. "  66#F" -> 66°F.
        val rounded = value.roundToInt()
        // Stale marking replaces the leading pad with 'E'. A 3-digit *negative* temp ("-100#F") fills
        // all 6 cells with no pad, and the only spare cell is the '-' — overwriting it would misreport
        // the sign. So such a value renders unmarked (value integrity beats the stale flag); markStale
        // is a no-op when there's no leading space.
        return markStale("${rounded.toString().padStart(TEMP_DIGITS_WIDTH)}#${unit.symbol}", stale)
    }

    /** Surface pressure in [LENGTH] cells: hPa integer (metric) or inHg 2-dp (imperial). */
    fun pressure(hpa: Double, imperial: Boolean, stale: Boolean = false): String {
        val value =
            if (imperial) formatDecimal(hpa * INHG_PER_HPA, INHG_DECIMALS) else hpa.roundToInt().toString()
        return markStale(value.padStart(LENGTH), stale)
    }

    /**
     * Terrain altitude in [LENGTH] cells: feet (imperial, 'f' suffix) or metres (metric, 'm');
     * right-justified like the other complications. 'E' marks a stale value when there's a leading pad.
     */
    fun altitude(meters: Double, imperial: Boolean, stale: Boolean = false): String {
        val (value, suffix) =
            if (imperial) (meters * FEET_PER_METER) to 'f' else meters to 'm'
        val num = value.roundToInt().toString()
        val fresh = if (num.length >= LENGTH) num.take(LENGTH) else "$num$suffix"
        return markStale(fresh.padStart(LENGTH), stale)
    }

    // Right-justified (leading spaces) so custom text aligns flush-right like the watch nameplate
    // and the other complications (temperature/steps/activity). Overflow keeps the leading 6 chars.
    fun custom(text: String): String =
        text.padStart(LENGTH, ' ').take(LENGTH)

    /**
     * Today's step count, right-justified in [LENGTH] chars (e.g. `" 12345"`). Negatives
     * clamp to 0; counts that would exceed 6 digits clamp to `"999999"` (no real day of
     * walking comes close, so this only guards against bogus aggregates).
     */
    fun steps(count: Long, stale: Boolean = false): String {
        val clamped = count.coerceIn(0L, MAX_STEPS)
        val plain = clamped.toString().padStart(LENGTH)
        if (!stale) return plain
        // 6-digit counts fill the row, leaving no pad for 'E' — abbreviate to thousands first so
        // "E " + a 4-char "Nk" value fits in 6. Smaller counts keep their pad for markStale.
        return if (plain.startsWith(' ')) {
            markStale(plain, stale = true)
        } else {
            "E " + abbreviateThousands(clamped)
        }
    }

    /**
     * Mark an already-formatted 6-char payload as stale by replacing its leading pad space with 'E'.
     * Caller guarantees there is a leading space to consume (temperatures and ≤5-digit steps always
     * have one). No-op when [stale] is false.
     */
    private fun markStale(formatted: String, stale: Boolean): String =
        if (stale && formatted.startsWith(' ')) "E" + formatted.drop(1) else formatted

    /** Floor [count] to whole thousands and render as "Nk" (e.g. 100_234 -> "100k"). For the
     *  6-digit range 100_000..999_999 this is always 4 chars ("100k".."999k"). */
    private fun abbreviateThousands(count: Long): String = "${count / THOUSAND}k"
}
