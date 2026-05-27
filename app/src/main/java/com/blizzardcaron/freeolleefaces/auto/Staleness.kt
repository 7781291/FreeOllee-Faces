package com.blizzardcaron.freeolleefaces.auto

import com.blizzardcaron.freeolleefaces.format.TempUnit

/**
 * True when a cached temperature can be pushed without re-fetching: it exists, was fetched in the
 * current unit, and is younger than the auto-update interval.
 */
fun isTempCacheFresh(
    fetchedMs: Long?,
    cacheUnit: TempUnit?,
    currentUnit: TempUnit,
    intervalMin: Int,
    nowMs: Long,
): Boolean {
    if (fetchedMs == null || cacheUnit == null) return false
    if (cacheUnit != currentUnit) return false
    return nowMs - fetchedMs < intervalMin * 60_000L
}
