package com.blizzardcaron.freeolleefaces.activity

/** Persists recorded activity tracks. Platform impl owns the file plumbing. */
interface ActivityTrackStore {
    /** Insert or replace by [ActivityTrack.id]. */
    fun save(track: ActivityTrack)

    /** The most recently started track, or null if none recorded. */
    fun latest(): ActivityTrack?

    /** All tracks, newest first — foundation for the future Strava/GPX exporter. */
    fun list(): List<ActivityTrack>

    /** Permanently remove the track with [id] (no-op if absent). */
    fun delete(id: String)

    /**
     * Permanently remove every track whose `endedAtMs` is non-null and strictly less than
     * [endedBeforeMs]. Running / never-ended tracks are never pruned. Returns the count removed.
     */
    fun prune(endedBeforeMs: Long): Int
}

/** Inert store: records nothing. Default for tests and non-Android construction. */
object NoopActivityTrackStore : ActivityTrackStore {
    override fun save(track: ActivityTrack) = Unit
    override fun latest(): ActivityTrack? = null
    override fun list(): List<ActivityTrack> = emptyList()
    override fun delete(id: String) = Unit
    override fun prune(endedBeforeMs: Long): Int = 0
}
