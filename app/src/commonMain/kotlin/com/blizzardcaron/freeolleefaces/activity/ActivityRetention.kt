package com.blizzardcaron.freeolleefaces.activity

/**
 * Retention policy for recorded tracks: keep the last [RETENTION_DAYS] days, hard-delete older.
 * Pure (no I/O) so it is fully testable; the store performs the deletion. The constant is hardcoded
 * for now but isolated here so a user-configurable window can replace it later.
 */
object ActivityRetention {
    const val RETENTION_DAYS = 7
    private const val MS_PER_DAY = 24L * 60 * 60 * 1000

    fun cutoffMs(nowMs: Long): Long = nowMs - RETENTION_DAYS * MS_PER_DAY

    fun idsToDelete(tracks: List<ActivityTrack>, nowMs: Long): List<String> {
        val cutoff = cutoffMs(nowMs)
        return tracks.filter { it.endedAtMs != null && it.endedAtMs < cutoff }.map { it.id }
    }
}
