package com.blizzardcaron.freeolleefaces.format

import java.time.LocalTime
import kotlin.math.roundToInt

enum class SunEventKind { SUNRISE, SUNSET }

object DisplayFormatter {

    private const val LENGTH = 6

    fun temperature(value: Double, unit: TempUnit = TempUnit.FAHRENHEIT, stale: Boolean = false): String {
        // The watch's segment font (firmware OW-FW-APP, font table indexed by ASCII) maps '#'
        // (0x23) to segments a+b+f+g — the top square that reads as a degree '°'. There is no
        // glyph at Latin-1 0xB0, so we send '#' to get the degree ring, e.g. "  66#F" -> 66°F.
        val rounded = value.roundToInt()
        return markStale("%4d#${unit.symbol}".format(rounded), stale)
    }

    fun sunTime(kind: SunEventKind, time: LocalTime): String {
        val hour24 = time.hour
        val minute = time.minute
        val isAm = hour24 < 12
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        val eventChar = if (kind == SunEventKind.SUNRISE) 'r' else 's'

        return if (hour12 < 10) {
            // single-digit hour: include am/pm marker -> "H:MMar" or "H:MMps"
            val ampm = if (isAm) 'a' else 'p'
            "%d:%02d%c%c".format(hour12, minute, ampm, eventChar)
        } else {
            // two-digit hour (10, 11, 12): drop am/pm -> "HH:MMr" or "HH:MMs"
            "%d:%02d%c".format(hour12, minute, eventChar)
        }
    }

    fun custom(text: String): String =
        text.padEnd(LENGTH, ' ').take(LENGTH)

    /**
     * Today's step count, right-justified in [LENGTH] chars (e.g. `" 12345"`). Negatives
     * clamp to 0; counts that would exceed 6 digits clamp to `"999999"` (no real day of
     * walking comes close, so this only guards against bogus aggregates).
     */
    fun steps(count: Long): String {
        val clamped = count.coerceIn(0L, 999_999L)
        return "%${LENGTH}d".format(clamped)
    }

    /**
     * Mark an already-formatted 6-char payload as stale by replacing its leading pad space with 'E'.
     * Caller guarantees there is a leading space to consume (temperatures and ≤5-digit steps always
     * have one). No-op when [stale] is false.
     */
    private fun markStale(formatted: String, stale: Boolean): String =
        if (stale && formatted.startsWith(' ')) "E" + formatted.drop(1) else formatted
}
