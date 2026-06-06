package com.blizzardcaron.freeolleefaces.location

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
    val min = (nowMs - fetchedMs).coerceAtLeast(0) / 60_000L
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 60 * 24 -> "${min / 60}h ago"
        else -> "${min / (60 * 24)}d ago"
    }
}
