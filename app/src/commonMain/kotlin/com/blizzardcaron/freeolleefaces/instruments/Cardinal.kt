package com.blizzardcaron.freeolleefaces.instruments

private val CARDINALS = listOf("N ", "NE", "E ", "SE", "S ", "SW", "W ", "NW")
private const val SECTORS = 8
private const val SECTOR_DEG = 45f
private const val HALF_SECTOR_DEG = 22.5f
private const val FULL_CIRCLE = 360f

/** 8-point cardinal for a compass bearing, padded to 2 chars for the nameplate. */
fun cardinal8(bearingDeg: Float): String {
    val norm = ((bearingDeg % FULL_CIRCLE) + FULL_CIRCLE) % FULL_CIRCLE
    val idx = ((norm + HALF_SECTOR_DEG) / SECTOR_DEG).toInt() % SECTORS
    return CARDINALS[idx]
}
