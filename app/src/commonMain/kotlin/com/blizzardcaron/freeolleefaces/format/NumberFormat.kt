package com.blizzardcaron.freeolleefaces.format

import kotlin.math.absoluteValue
import kotlin.math.roundToLong

/** Base of the per-digit scaling factor used to shift [decimals] places into an integer. */
private const val DECIMAL_RADIX = 10

/** Digits per comma-separated group in [groupThousands]. */
private const val GROUP_SIZE = 3

/** "%.1f" equivalent: rounds to [decimals] places, always showing them. */
fun formatDecimal(value: Double, decimals: Int): String {
    val neg = value < 0
    val factor = generateSequence(1L) { it * DECIMAL_RADIX }.take(decimals + 1).last()
    val scaled = (value.absoluteValue * factor).roundToLong()
    val whole = scaled / factor
    val frac = scaled % factor
    val fracStr = frac.toString().padStart(decimals, '0')
    val body = if (decimals > 0) "$whole.$fracStr" else "$whole"
    return if (neg && scaled != 0L) "-$body" else body
}

/** "%,d" equivalent: groups thousands with commas. */
fun groupThousands(value: Long): String {
    val s = value.absoluteValue.toString()
    val grouped = s.reversed().chunked(GROUP_SIZE).joinToString(",").reversed()
    return if (value < 0) "-$grouped" else grouped
}
