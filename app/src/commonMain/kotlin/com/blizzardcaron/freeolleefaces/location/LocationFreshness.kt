package com.blizzardcaron.freeolleefaces.location

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val MILLIS_PER_MINUTE = 60_000L

/** Saved coordinates older than this are silently re-fetched on launch. */
const val LOCATION_STALE_MS: Long = 7L * 24 * 60 * 60 * 1000

/** True when there is no saved fetch time, or it is at least [staleMs] old. */
fun isLocationStale(fetchedMs: Long?, nowMs: Long, staleMs: Long = LOCATION_STALE_MS): Boolean {
    if (fetchedMs == null) return true
    return nowMs - fetchedMs >= staleMs
}

/** Human "age" of a saved fix, or null if never fetched. e.g. "just now", "5m ago", "3h ago", "5d ago". */
fun freshnessLabel(fetchedMs: Long?, nowMs: Long): String? {
    if (fetchedMs == null) return null
    val min = (nowMs - fetchedMs).coerceAtLeast(0) / MILLIS_PER_MINUTE
    return when {
        min < 1 -> "just now"
        min < MINUTES_PER_HOUR -> "${min}m ago"
        min < MINUTES_PER_HOUR * HOURS_PER_DAY -> "${min / MINUTES_PER_HOUR}h ago"
        else -> "${min / (MINUTES_PER_HOUR * HOURS_PER_DAY)}d ago"
    }
}
